package com.fatgerg.windup

import android.content.Context
import android.content.SharedPreferences

/** Everything on the settings screen and the prank form, stored on the phone only. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("windup_settings", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = prefs.getString(key, def) ?: def
    private fun putStr(key: String, v: String) = prefs.edit().putString(key, v).apply()

    // ---- Keys and model ----

    var claudeKey: String
        get() = str("claude_key")
        set(v) = putStr("claude_key", v.trim())

    var model: String
        get() = str("model", DEFAULT_MODEL)
        set(v) = putStr("model", v.trim().ifEmpty { DEFAULT_MODEL })

    /** Google Cloud Text-to-Speech API key. Empty means use the phone's own voice. */
    var googleKey: String
        get() = str("google_key")
        set(v) = putStr("google_key", v.trim())

    // ---- The prank form ----

    var characterId: String
        get() = str("character", "derek_parcels")
        set(v) = putStr("character", v)

    var friendName: String
        get() = str("friend_name")
        set(v) = putStr("friend_name", v)

    var friendDetails: String
        get() = str("friend_details")
        set(v) = putStr("friend_details", v)

    var myName: String
        get() = str("my_name", "Greg")
        set(v) = putStr("my_name", v.trim())

    /** Roughly how many AI replies before the reveal. */
    var maxTurns: Int
        get() = prefs.getInt("max_turns", 6)
        set(v) = prefs.edit().putInt("max_turns", v).apply()

    /** True: Google's voices (needs the Google key). False: the phone's own text-to-speech voices. */
    var useGoogleVoices: Boolean
        get() = prefs.getBoolean("use_google", true)
        set(v) = prefs.edit().putBoolean("use_google", v).apply()

    /** Google voices are used only when chosen in Settings AND there's a key. */
    val googleOn: Boolean get() = useGoogleVoices && googleKey.isNotBlank()

    /** A chosen Google voice for one character, or "" to use the character's own. */
    fun voiceFor(characterId: String): String = str("voice_$characterId")
    fun setVoiceFor(characterId: String, voice: String) = putStr("voice_$characterId", voice)

    /** A chosen phone voice for one character, or "" for the phone's first British voice. */
    fun phoneVoiceFor(characterId: String): String = str("phone_voice_$characterId")
    fun setPhoneVoiceFor(characterId: String, voice: String) = putStr("phone_voice_$characterId", voice)

    // ---- Line levels ----

    /** Seconds of quiet that mean the friend has finished talking. */
    var endOfTurnSeconds: Float
        get() = prefs.getFloat("eot", 0.7f)
        set(v) = prefs.edit().putFloat("eot", v).apply()

    /** Loudness (0-100) above which the line counts as someone talking. */
    var talkThreshold: Int
        get() = prefs.getInt("threshold", 12)
        set(v) = prefs.edit().putInt("threshold", v).apply()

    /** Volume of the character's voice going into the call phone, 10-100. */
    var outputLevel: Int
        get() = prefs.getInt("outlevel", 60)
        set(v) = prefs.edit().putInt("outlevel", v).apply()

    /** Let the friend talk over the character, which stops it mid-sentence. */
    var allowInterrupt: Boolean
        get() = prefs.getBoolean("interrupt", true)
        set(v) = prefs.edit().putBoolean("interrupt", v).apply()

    /** How loud the friend must be to interrupt, 1-90. Higher than the talk threshold to ignore echo. */
    var interruptThreshold: Int
        get() = prefs.getInt("interrupt_threshold", 25)
        set(v) = prefs.edit().putInt("interrupt_threshold", v).apply()

    // ---- Safety ----

    var revealWords: String
        get() = str("reveal_words", DEFAULT_REVEAL_WORDS)
        set(v) = putStr("reveal_words", v)

    fun revealWordList(): List<String> = revealWords.split(",", "\n")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"

        /** Hearing any of these ends the prank at once with a friendly reveal, before Claude sees anything. */
        const val DEFAULT_REVEAL_WORDS =
            "call the police, calling the police, ring the police, phone the police, " +
                "is this a scam, this is a scam, scam, fraud, " +
                "is this real, is this for real, who is this really, " +
                "i'm scared, i am scared, you're scaring me, frightened, " +
                "leave me alone, stop calling, i'm reporting, report you"
    }
}
