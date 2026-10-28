package com.fatgerg.windup

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.PI
import kotlin.math.sin

/** Finds the cable going to the call phone, so the character never comes out of the Pixel's speaker. */
object AudioRoutes {
    private val CABLE_TYPES = listOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    )

    fun cableInput(context: Context): AudioDeviceInfo? = find(context, AudioManager.GET_DEVICES_INPUTS)
    fun cableOutput(context: Context): AudioDeviceInfo? = find(context, AudioManager.GET_DEVICES_OUTPUTS)

    fun hasCable(context: Context) = cableInput(context) != null && cableOutput(context) != null

    private fun find(context: Context, flag: Int): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(flag)
        for (type in CABLE_TYPES) devices.firstOrNull { it.type == type }?.let { return it }
        return null
    }

    fun describe(context: Context): String {
        val input = cableInput(context)
        val output = cableOutput(context)
        return when {
            input != null && output != null -> "Cable connected: ${output.productName}"
            output != null -> "Cable output only, no mic line detected"
            else -> "No cable: practising on this phone's mic and speaker"
        }
    }
}

/**
 * The character's voice. Speech is made a sentence at a time, and the next sentence is made
 * while the current one plays, so replies start quickly. [stopNow] cuts it off mid-word.
 */
class Speaker(
    private val context: Context,
    private val settings: Settings,
    private val google: GoogleVoice,
    private val phone: PhoneVoice,
    private val log: (String) -> Unit,
) {
    private val synthExec = Executors.newSingleThreadExecutor()
    @Volatile private var interrupted = false
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var googleFailed = false

    /** When set, everything the character says is added to the call recording. */
    @Volatile var recorder: CallRecorder? = null

    /** When set, everything the character says is also played live to your headphones. */
    @Volatile var monitor: LiveMonitor? = null

    val usingGoogle: Boolean get() = settings.googleOn && !googleFailed

    /** Makes the audio for one piece of text in this character's voice. */
    fun synth(text: String, character: Character): Pcm? {
        if (text.isBlank()) return null
        if (usingGoogle) {
            try {
                val voice = settings.voiceFor(character.id).ifBlank { character.googleVoice }
                return google.synth(text, voice, character.speakingRate)
            } catch (e: Exception) {
                log("${e.message}. Using the phone's own voice for now.")
                googleFailed = true
            }
        }
        return phone.synth(text, character.speakingRate, character.phonePitch, settings.phoneVoiceFor(character.id))
    }

    /** Lets Google be tried again, for example after the key has been changed. */
    fun resetGoogle() { googleFailed = false }

    /** Speaks the text. Returns false if it was cut off. */
    fun speak(text: String, character: Character): Boolean {
        interrupted = false
        val parts = sentences(text)
        if (parts.isEmpty()) return true
        var next: Future<Pcm?> = synthExec.submit(Callable<Pcm?> { synth(parts[0], character) })
        for (i in parts.indices) {
            val pcm = try { next.get() } catch (_: Exception) { null }
            if (i + 1 < parts.size) {
                val following = parts[i + 1]
                next = synthExec.submit(Callable<Pcm?> { synth(following, character) })
            }
            if (interrupted) return false
            if (pcm != null) play(pcm)
            if (interrupted) return false
        }
        return true
    }

    /** Plays audio made earlier (the opening line is made before they answer). */
    fun playPrepared(pcm: Pcm): Boolean {
        interrupted = false
        play(pcm)
        return !interrupted
    }

    fun stopNow() {
        interrupted = true
        try { currentTrack?.pause(); currentTrack?.flush() } catch (_: Exception) {}
        monitor?.cutSpeech()
    }

    /** A steady tone for checking the cable and setting the call phone's mic level. */
    fun testTone(seconds: Int = 3) {
        interrupted = false
        val rate = 24000
        val n = rate * seconds
        val fade = rate / 100
        val pcm = ShortArray(n) { i ->
            var a = 0.5f
            if (i < fade) a *= i / fade.toFloat()
            if (i > n - fade) a *= (n - i) / fade.toFloat()
            (sin(2 * PI * 1000.0 * i / rate) * a * Short.MAX_VALUE).toInt().toShort()
        }
        play(Pcm(pcm, rate), record = false)
    }

    fun shutdown() {
        stopNow()
        synthExec.shutdownNow()
    }

    private fun play(pcm: Pcm, record: Boolean = true) {
        if (pcm.samples.isEmpty()) return
        val rec = if (record) recorder else null
        var startAt = 0
        val g = settings.outputLevel.coerceIn(10, 100) / 100f
        val tail = pcm.rate / 10 // 100 ms of silence so the last word isn't clipped
        val data = ShortArray(pcm.samples.size + tail) { i ->
            if (i < pcm.samples.size) (pcm.samples[i] * g).toInt().toShort() else 0
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        AudioRoutes.cableOutput(context)?.let { track.setPreferredDevice(it) }
        currentTrack = track
        try {
            track.write(data, 0, data.size)
            if (interrupted) return
            startAt = rec?.position() ?: 0
            if (record) monitor?.speech(pcm)
            track.play()
            val end = System.currentTimeMillis() + data.size * 1000L / pcm.rate + 300
            while (System.currentTimeMillis() < end) {
                if (interrupted) break
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                if (track.playbackHeadPosition >= data.size) break
                Thread.sleep(15)
            }
        } finally {
            currentTrack = null
            if (rec != null) {
                // Only what was actually heard goes in the recording, so a cut-off line stays cut off.
                val played = try { track.playbackHeadPosition } catch (_: Exception) { 0 }
                rec.addSpeech(startAt, pcm, played.coerceAtMost(pcm.samples.size))
            }
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    companion object {
        /** Splits a reply into sentences, joining short ones so the voice doesn't sound choppy. */
        fun sentences(text: String): List<String> {
            val raw = text.trim().split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            val out = ArrayList<String>()
            val buf = StringBuilder()
            for (s in raw) {
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(s)
                if (buf.length >= 40) {
                    out.add(buf.toString())
                    buf.clear()
                }
            }
            if (buf.isNotEmpty()) {
                if (out.isNotEmpty() && buf.length < 20) out[out.size - 1] = out.last() + " " + buf
                else out.add(buf.toString())
            }
            return out
        }
    }
}
