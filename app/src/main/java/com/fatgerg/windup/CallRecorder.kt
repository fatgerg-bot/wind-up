package com.fatgerg.windup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records both sides of the call into one mono track.
 *
 * The friend's side comes in frame by frame from the call line, and that sets the clock.
 * Each time the character speaks, its clean audio is mixed in at the moment it started,
 * so the recording sounds like the real conversation.
 */
class CallRecorder {
    private val lock = Any()
    private var buf = ShortArray(RATE * 60)
    private var len = 0      // samples of call line written so far: the recording's clock
    private var end = 0      // furthest sample written by either side
    @Volatile var recording = false
        private set

    fun start() {
        synchronized(lock) {
            buf = ShortArray(RATE * 60)
            len = 0
            end = 0
            recording = true
        }
    }

    /** Where "now" is in the recording, for lining up the character's speech. */
    fun position(): Int = synchronized(lock) { len }

    /** One frame of the call line (16 kHz). */
    fun addLine(samples: ShortArray, count: Int) {
        if (!recording) return
        synchronized(lock) {
            if (len + count > MAX_SAMPLES) return
            ensure(len + count)
            for (i in 0 until count) buf[len + i] = mix(buf[len + i], samples[i].toInt())
            len += count
            if (len > end) end = len
        }
    }

    /** The character's speech, the first [played] samples of [pcm], starting at [at]. */
    fun addSpeech(at: Int, pcm: Pcm, played: Int) {
        if (!recording || played <= 0) return
        val part = resample(pcm.samples, played.coerceAtMost(pcm.samples.size), pcm.rate)
        synchronized(lock) {
            val stop = (at + part.size).coerceAtMost(MAX_SAMPLES)
            if (stop <= at) return
            ensure(stop)
            for (i in at until stop) buf[i] = mix(buf[i], (part[i - at] * SPEECH_GAIN).toInt())
            if (stop > end) end = stop
        }
    }

    /** Stops and hands back everything recorded, or null if it was too short to keep. */
    fun stop(): ShortArray? = synchronized(lock) {
        recording = false
        if (end < RATE) null else buf.copyOf(end)
    }

    private fun ensure(size: Int) {
        if (size <= buf.size) return
        var n = buf.size
        while (n < size) n *= 2
        buf = buf.copyOf(n.coerceAtMost(MAX_SAMPLES))
    }

    private fun mix(a: Short, b: Int): Short = (a + b).coerceIn(-32768, 32767).toShort()

    companion object {
        const val RATE = 16000
        private const val MAX_SAMPLES = RATE * 60 * 30 // 30 minutes
        private const val SPEECH_GAIN = 0.8f

        /** Simple linear resampling to 16 kHz. */
        fun resample(src: ShortArray, count: Int, rate: Int): ShortArray {
            if (rate == RATE) return src.copyOf(count)
            val outLen = (count.toLong() * RATE / rate).toInt()
            val step = rate.toDouble() / RATE
            return ShortArray(outLen) { i ->
                val pos = i * step
                val j = pos.toInt().coerceAtMost(count - 1)
                val k = (j + 1).coerceAtMost(count - 1)
                val f = pos - j
                (src[j] * (1 - f) + src[k] * f).toInt().toShort()
            }
        }

        /**
         * Saves to the phone's Recordings/Wind-Up folder (Music/Wind-Up on older Android),
         * where the Files app and anything you share to can see it.
         */
        fun save(context: Context, samples: ShortArray, name: String): Uri? {
            val folder = if (Build.VERSION.SDK_INT >= 31) Environment.DIRECTORY_RECORDINGS
                else Environment.DIRECTORY_MUSIC
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "$folder/Wind-Up")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            return try {
                resolver.openOutputStream(uri)?.use { writeWav(it, samples) } ?: throw RuntimeException("Couldn't open the file")
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }

        private fun writeWav(out: OutputStream, samples: ShortArray) {
            val dataLen = samples.size * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(dataLen)
            }
            out.write(header.array())
            val body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) body.putShort(s)
            out.write(body.array())
        }
    }
}
