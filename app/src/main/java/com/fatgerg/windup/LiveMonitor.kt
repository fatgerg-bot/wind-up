package com.fatgerg.windup

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Plays the call live to Bluetooth headphones: your friend's side straight from the call
 * line, with the character's voice mixed in as it speaks. The character's voice still goes
 * down the cable to the call phone as normal; this is only a copy for your ears.
 *
 * The line sets the pace: each 32 ms frame from the call line goes out with the next 32 ms
 * of the character's speech mixed in.
 */
class LiveMonitor(private val context: Context) {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var speech = ShortArray(0)
    private var speechPos = 0
    @Volatile var on = false
        private set
    @Volatile private var lastLine = 0L

    /** The connected Bluetooth headphones, if there are any. */
    fun headphones(): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val types = buildList {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        for (t in types) outs.firstOrNull { it.type == t }?.let { return it }
        return null
    }

    /** Returns the headphones' name, or null if none are connected. */
    fun start(): String? {
        val bt = headphones() ?: return null
        synchronized(lock) {
            stopLocked()
            val minBuf = AudioTrack.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, RATE)) // about half a second
                .build()
            t.setPreferredDevice(bt)
            t.play()
            track = t
            speech = ShortArray(0)
            speechPos = 0
            on = true
        }
        return bt.productName?.toString()?.ifBlank { null } ?: "Bluetooth headphones"
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        on = false
        track?.let {
            try { it.pause(); it.flush(); it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
    }

    /** One frame of the call line (16 kHz). Never blocks the listening thread. */
    fun line(frame: ShortArray, count: Int) {
        if (!on) return
        lastLine = System.currentTimeMillis()
        synchronized(lock) {
            val t = track ?: return
            val out = ShortArray(count) { i ->
                var s = frame[i].toInt()
                if (speechPos < speech.size) s += (speech[speechPos++] * SPEECH_GAIN).toInt()
                s.coerceIn(-32768, 32767).toShort()
            }
            if (speechPos >= speech.size && speech.isNotEmpty()) {
                speech = ShortArray(0)
                speechPos = 0
            }
            try { t.write(out, 0, out.size, AudioTrack.WRITE_NON_BLOCKING) } catch (_: Exception) {}
        }
    }

    /** The character has just started saying this. */
    fun speech(pcm: Pcm) {
        // No call line running (e.g. trying a voice before Get ready): nothing to mix it into.
        if (!on || System.currentTimeMillis() - lastLine > 500) return
        val part = CallRecorder.resample(pcm.samples, pcm.samples.size, pcm.rate)
        synchronized(lock) {
            val left = if (speechPos < speech.size) speech.copyOfRange(speechPos, speech.size) else ShortArray(0)
            speech = left + part
            speechPos = 0
        }
    }

    /** The character was cut off: stop its voice in the headphones too. */
    fun cutSpeech() = synchronized(lock) {
        speech = ShortArray(0)
        speechPos = 0
    }

    companion object {
        private const val RATE = CallRecorder.RATE
        private const val SPEECH_GAIN = 0.8f
    }
}
