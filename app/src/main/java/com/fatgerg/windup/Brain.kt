package com.fatgerg.windup

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Claude playing the character. Keeps the whole call so it remembers what's been said. */
class Brain(private val settings: Settings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val history = ArrayList<Pair<String, String>>() // role to text
    private var system = ""

    /** Starts a new call. The opening line has already been spoken by the app. */
    fun start(systemPrompt: String, openingLine: String) {
        system = systemPrompt
        history.clear()
        history.add("user" to "[The call has connected and they've picked up.]")
        history.add("assistant" to openingLine)
    }

    /** Remembers a line the app spoke itself (a typed line or the instant reveal). */
    fun noteSpoken(text: String) {
        if (history.lastOrNull()?.first == "assistant") {
            val (_, prev) = history.removeAt(history.size - 1)
            history.add("assistant" to "$prev $text")
        } else {
            if (history.isEmpty()) history.add("user" to "[The call has connected.]")
            history.add("assistant" to text)
        }
    }

    /** Sends what the friend said (plus any app note) and returns the character's spoken reply. */
    fun reply(input: String): String {
        addUser(input)
        val body = JSONObject()
            .put("model", settings.model)
            .put("max_tokens", 160)
            .put("system", system)
            .put("messages", JSONArray().apply {
                for ((role, text) in history) {
                    put(JSONObject().put("role", role).put("content", text))
                }
            })

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", settings.claudeKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // Take the failed turn back out, so the next try starts clean.
                if (history.lastOrNull()?.first == "user") history.removeAt(history.size - 1)
                val msg = try {
                    JSONObject(raw).getJSONObject("error").getString("message")
                } catch (_: Exception) { raw.take(200) }
                throw RuntimeException("Claude API ${resp.code}: $msg")
            }
            val content = JSONObject(raw).getJSONArray("content")
            val text = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") text.append(block.optString("text"))
            }
            val spoken = cleanForSpeech(text.toString())
            history.add("assistant" to spoken.ifEmpty { "Sorry, you cut out there, say again?" })
            return spoken
        }
    }

    private fun addUser(text: String) {
        if (history.lastOrNull()?.first == "user") {
            val (_, prev) = history.removeAt(history.size - 1)
            history.add("user" to "$prev\n$text")
        } else {
            history.add("user" to text)
        }
    }

    companion object {
        /** Removes anything a voice shouldn't read out: stage directions, markdown, emoji. */
        fun cleanForSpeech(s: String): String = s
            .replace(Regex("\\*[^*]*\\*"), " ")
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("^\\s*[A-Z][A-Za-z ]{1,30}:\\s+"), "") // "Derek: ..." name labels
            .replace(Regex("[#*_`~>|]"), " ")
            .replace(Regex("[\\p{So}\\p{Cn}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
