package com.fatgerg.windup

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** A chunk of speech ready to play: 16-bit mono samples and their sample rate. */
class Pcm(val samples: ShortArray, val rate: Int)

/**
 * Google Cloud's Chirp 3 HD voices. The first million characters each month are free,
 * which is roughly a thousand minutes of speech.
 */
class GoogleVoice(private val settings: Settings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun synth(text: String, voice: String, rate: Float): Pcm {
        val name = if (voice.contains("-")) voice else "en-GB-Chirp3-HD-$voice"
        val body = JSONObject()
            .put("input", JSONObject().put("text", text))
            .put("voice", JSONObject().put("languageCode", name.substringBefore("-Chirp", "en-GB")).put("name", name))
            .put("audioConfig", JSONObject()
                .put("audioEncoding", "LINEAR16")
                .put("sampleRateHertz", RATE)
                .put("speakingRate", rate.toDouble().coerceIn(0.25, 2.0)))
        val request = Request.Builder()
            .url("https://texttospeech.googleapis.com/v1/text:synthesize")
            .header("X-Goog-Api-Key", settings.googleKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = try {
                    JSONObject(raw).getJSONObject("error").getString("message")
                } catch (_: Exception) { raw.take(200) }
                throw RuntimeException("Google voice ${resp.code}: $msg")
            }
            val bytes = Base64.decode(JSONObject(raw).getString("audioContent"), Base64.DEFAULT)
            return Wav.read(bytes) ?: Wav.rawPcm(bytes, RATE)
        }
    }

    companion object {
        const val RATE = 24000

        /** All 30 Chirp 3 HD voices, with Google's own one-word description of each. */
        val ALL = listOf(
            "Achird" to "male, friendly", "Algenib" to "male, gravelly", "Algieba" to "male, smooth",
            "Alnilam" to "male, firm", "Charon" to "male, informative", "Enceladus" to "male, breathy",
            "Fenrir" to "male, excitable", "Iapetus" to "male, clear", "Orus" to "male, firm",
            "Puck" to "male, upbeat", "Rasalgethi" to "male, informative", "Sadachbia" to "male, lively",
            "Sadaltager" to "male, knowledgeable", "Schedar" to "male, even", "Umbriel" to "male, easy-going",
            "Zubenelgenubi" to "male, casual",
            "Achernar" to "female, soft", "Aoede" to "female, breezy", "Autonoe" to "female, bright",
            "Callirrhoe" to "female, easy-going", "Despina" to "female, smooth", "Erinome" to "female, clear",
            "Gacrux" to "female, mature", "Kore" to "female, firm", "Laomedeia" to "female, upbeat",
            "Leda" to "female, youthful", "Pulcherrima" to "female, forward", "Sulafat" to "female, warm",
            "Vindemiatrix" to "female, gentle", "Zephyr" to "female, bright",
        )
    }
}

/** The phone's own text-to-speech, used when there's no Google key or Google can't be reached. */
class PhoneVoice(private val context: Context) {
    private var tts: TextToSpeech? = null
    private val ready = CountDownLatch(1)
    @Volatile private var ok = false

    fun init() {
        tts = TextToSpeech(context) { status ->
            ok = status == TextToSpeech.SUCCESS
            ready.countDown()
        }
    }

    fun awaitReady(): Boolean {
        ready.await(15, TimeUnit.SECONDS)
        return ok
    }

    /** Every downloaded English voice on the phone, British first. */
    fun englishVoices(): List<Voice> {
        val t = tts ?: return emptyList()
        return try {
            val en = t.voices.orEmpty()
                .filter { it.locale.language == "en" }
                .filter { !it.isNetworkConnectionRequired }
            // Voices listed but not downloaded fall back to the default voice, so they'd all sound the same.
            val installed = en.filter {
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            installed.ifEmpty { en }
                .sortedWith(compareBy<Voice>({ countryOrder(it.locale.country) }, { it.name }))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The phone's voices with friendly labels like "British 2" or "Irish 1", for the drop-down. */
    fun labelledVoices(): List<Pair<String, String>> {
        val counts = HashMap<String, Int>()
        return englishVoices().map { v ->
            val accent = accentName(v.locale.country)
            val n = (counts[accent] ?: 0) + 1
            counts[accent] = n
            v.name to "$accent $n"
        }
    }

    /** Plays a voice straight out of the phone, for choosing one. */
    fun preview(voiceName: String, text: String, rate: Float, pitch: Float) {
        val t = tts ?: return
        t.setLanguage(Locale.UK)
        pick(voiceName)?.let { t.setVoice(it) }
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    private fun pick(voiceName: String): Voice? {
        val all = englishVoices()
        return all.firstOrNull { it.name == voiceName } ?: all.firstOrNull { it.locale.country == "GB" } ?: all.firstOrNull()
    }

    /** [voiceName] is one of [englishVoices]' names, or "" for the first British voice. */
    fun synth(text: String, rate: Float, pitch: Float, voiceName: String = ""): Pcm? {
        val t = tts ?: return null
        if (!awaitReady()) return null
        t.setLanguage(Locale.UK)
        pick(voiceName)?.let { t.setVoice(it) }
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        val file = File(context.cacheDir, "say.wav")
        val id = UUID.randomUUID().toString()
        val done = CountDownLatch(1)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { done.countDown() }
            override fun onError(utteranceId: String?, errorCode: Int) { done.countDown() }
        })
        file.delete()
        if (t.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) return null
        done.await(30, TimeUnit.SECONDS)
        if (!file.exists() || file.length() < 64) return null
        return Wav.read(file.readBytes())
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    companion object {
        private val ACCENTS = listOf(
            "GB" to "British", "IE" to "Irish", "AU" to "Australian", "NZ" to "New Zealand",
            "IN" to "Indian", "ZA" to "South African", "NG" to "Nigerian", "CA" to "Canadian",
            "US" to "American",
        )

        fun accentName(country: String): String =
            ACCENTS.firstOrNull { it.first == country }?.second ?: "English ($country)"

        fun countryOrder(country: String): Int =
            ACCENTS.indexOfFirst { it.first == country }.let { if (it < 0) 99 else it }
    }
}

object Wav {
    /** Reads a 16-bit PCM WAV, mixing down to mono if needed. Returns null if it isn't one. */
    fun read(bytes: ByteArray): Pcm? {
        if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 22050
        var channels = 1
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = bb.getShort(body + 2).toInt().coerceAtLeast(1)
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                if (bits != 16) return null
                val len = min(if (size > 0) size else bytes.size - body, bytes.size - body)
                val frames = len / 2 / channels
                val out = ShortArray(frames) { f ->
                    var sum = 0
                    for (c in 0 until channels) sum += bb.getShort(body + (f * channels + c) * 2)
                    (sum / channels).toShort()
                }
                return Pcm(out, rate)
            }
            if (size < 0) return null
            pos = body + size + (size and 1)
        }
        return null
    }

    /** Headerless 16-bit little-endian mono audio. */
    fun rawPcm(bytes: ByteArray, rate: Int): Pcm {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return Pcm(ShortArray(bytes.size / 2) { bb.getShort(it * 2) }, rate)
    }
}
