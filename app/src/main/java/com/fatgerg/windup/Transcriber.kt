package com.fatgerg.windup

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/** On-device speech to text (Moonshine via sherpa-onnx). Works offline and makes no beeps. */
class Transcriber(assets: AssetManager) {
    private val recognizer: OfflineRecognizer

    init {
        val dir = "asr"
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = "$dir/encoder_model.ort",
                    mergedDecoder = "$dir/decoder_model_merged.ort",
                ),
                tokens = "$dir/tokens.txt",
                numThreads = 2,
            ),
        )
        recognizer = OfflineRecognizer(assetManager = assets, config = config)
    }

    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, CallListener.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()
}
