package com.fatgerg.windup

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Listens to the call phone's earpiece line and cuts it into turns: each time the friend
 * speaks and then goes quiet, [onTurn] gets the audio.
 *
 * A frame counts as talking only when it's loud enough AND the voice detector thinks it's a
 * human voice. While the character is speaking, the friend can interrupt by talking louder
 * than the interrupt threshold for a moment; [onBargeIn] is called and their words are kept.
 */
class CallListener(
    private val context: Context,
    private val settings: Settings,
    private val onTurnStarted: () -> Unit,
    private val onTurn: (FloatArray) -> Unit,
    private val onBargeIn: () -> Unit,
    private val onDiscard: () -> Unit = {},
) {
    @Volatile private var running = false

    /** True while the character is talking. */
    @Volatile var speaking = false
        set(value) {
            if (value && !field) pendingReset = true
            field = value
        }

    /** Ignore everything (after the call, or while the app speaks without interruptions). */
    @Volatile var muted = false
        set(value) {
            if (value) pendingReset = true
            field = value
        }

    /** Set from other threads; the listening thread does the actual reset, so nothing clashes. */
    @Volatile private var pendingReset = false

    /** Interruptions only make sense with the cable; on the phone's speaker it would hear itself. */
    @Volatile var interruptAllowed = true

    private var thread: Thread? = null
    private var vad: Vad? = null

    private val preRoll = ArrayDeque<FloatArray>()
    private val segment = ArrayList<FloatArray>()
    private var inSpeech = false
    private var speechRun = 0
    private var quietFrames = 0
    private var speechFrames = 0

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "call-listener").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun loadVad() {
        if (vad != null) return
        vad = try {
            Vad(
                assetManager = context.assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "vad/silero_vad.onnx",
                        windowSize = FRAME,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ),
            )
        } catch (e: Throwable) {
            CallState.add(Who.APP, "Voice detector unavailable, using loudness only")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        loadVad()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME * 2 * 10),
        )
        AudioRoutes.cableInput(context)?.let { record.setPreferredDevice(it) }
        val buf = ShortArray(FRAME)
        try {
            record.startRecording()
            while (running) {
                var got = 0
                while (got < FRAME && running) {
                    val n = record.read(buf, got, FRAME - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < FRAME) continue
                val samples = FloatArray(FRAME) { buf[it] / 32768f }
                val level = levelOf(samples)
                CallState.setLevel(level)
                if (pendingReset) {
                    pendingReset = false
                    resetSegment()
                }
                if (muted) {
                    CallState.setSpeech(false)
                    continue
                }
                if (speaking) watchForBargeIn(samples, level) else process(samples, level)
            }
        } catch (e: Exception) {
            CallState.add(Who.APP, "Listening stopped: ${e.message}")
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            CallState.setLevel(0)
            CallState.setSpeech(false)
            vad?.release()
            vad = null
        }
    }

    private fun isVoice(samples: FloatArray, level: Int, threshold: Int): Boolean {
        if (level < threshold) return false
        val v = vad ?: return true
        return try { v.compute(samples) >= VOICE_PROBABILITY } catch (_: Throwable) { true }
    }

    /** While the character talks: only a sustained, louder voice counts, so echo doesn't. */
    private fun watchForBargeIn(samples: FloatArray, level: Int) {
        if (!interruptAllowed || !settings.allowInterrupt) {
            CallState.setSpeech(false)
            return
        }
        val voice = isVoice(samples, level, settings.interruptThreshold)
        CallState.setSpeech(voice)
        preRoll.addLast(samples)
        while (preRoll.size > BARGE_FRAMES + 4) preRoll.removeFirst()
        speechRun = if (voice) speechRun + 1 else 0
        if (speechRun >= BARGE_FRAMES) {
            speaking = false
            onBargeIn()
            inSpeech = true
            segment.clear()
            segment.addAll(preRoll)
            preRoll.clear()
            speechFrames = BARGE_FRAMES
            quietFrames = 0
            onTurnStarted()
        }
    }

    private fun process(samples: FloatArray, level: Int) {
        val voice = isVoice(samples, level, settings.talkThreshold)
        CallState.setSpeech(voice)
        val endFrames = (settings.endOfTurnSeconds * FPS).toInt().coerceAtLeast(8)

        if (!inSpeech) {
            preRoll.addLast(samples)
            while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            speechRun = if (voice) speechRun + 1 else 0
            if (speechRun >= START_FRAMES) {
                inSpeech = true
                segment.clear()
                segment.addAll(preRoll)
                preRoll.clear()
                quietFrames = 0
                speechFrames = speechRun
                onTurnStarted()
            }
            return
        }

        segment.add(samples)
        if (voice) {
            quietFrames = 0
            speechFrames++
        } else {
            quietFrames++
        }
        if (quietFrames >= endFrames || segment.size > FPS * 30) {
            finishSegment(quietFrames)
        }
    }

    private fun finishSegment(trailingQuiet: Int) {
        val keep = (segment.size - trailingQuiet + 6).coerceIn(0, segment.size)
        val total = segment.take(keep)
        val enough = speechFrames >= (FPS * 0.3f).toInt()
        resetSegment()
        if (enough && total.isNotEmpty()) {
            val out = FloatArray(total.sumOf { it.size })
            var i = 0
            for (chunk in total) { chunk.copyInto(out, i); i += chunk.size }
            onTurn(out)
        } else {
            onDiscard()
        }
    }

    private fun resetSegment() {
        inSpeech = false
        segment.clear()
        preRoll.clear()
        speechRun = 0
        quietFrames = 0
        speechFrames = 0
    }

    companion object {
        const val SAMPLE_RATE = 16000
        /** 512 samples = 32 ms, the window size the voice detector expects. */
        const val FRAME = 512
        const val FPS = SAMPLE_RATE / FRAME.toFloat()
        private const val VOICE_PROBABILITY = 0.5f
        private const val START_FRAMES = 3      // ~0.1 s of voice to start a turn
        private const val PRE_ROLL_FRAMES = 10  // ~0.3 s kept from before the start
        private const val BARGE_FRAMES = 12     // ~0.4 s of loud voice to interrupt

        /** Maps loudness from -60 dBFS..0 dBFS to 0..100. */
        fun levelOf(samples: FloatArray): Int {
            var sum = 0.0
            for (s in samples) sum += s * s
            val rms = sqrt(sum / samples.size.coerceAtLeast(1))
            if (rms <= 1e-6) return 0
            val db = 20 * log10(rms)
            return (((db + 60) / 60) * 100).toInt().coerceIn(0, 100)
        }
    }
}
