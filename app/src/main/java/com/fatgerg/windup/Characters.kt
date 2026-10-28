package com.fatgerg.windup

import android.content.Context
import org.json.JSONObject

data class Character(
    val id: String,
    val name: String,
    val voiceDesign: String,
    val openingLine: String,
    val persona: String,
    val googleVoice: String,
    val speakingRate: Float,
    val phonePitch: Float,
)

/** Who's ringing up today: everything about the prank, filled in on the form. */
data class Prank(
    val character: Character,
    val friendName: String,
    val details: String,
    val myName: String,
    val maxTurns: Int,
)

/** Loads the characters from assets/characters.json and builds Claude's instructions. */
object Characters {
    @Volatile private var cache: Pair<String, List<Character>>? = null

    private fun load(context: Context): Pair<String, List<Character>> {
        cache?.let { return it }
        val raw = context.assets.open("characters.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val shared = root.getString("shared_rules")
        val arr = root.getJSONArray("characters")
        val list = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            Character(
                id = c.getString("id"),
                name = c.getString("name"),
                voiceDesign = c.optString("voice_design"),
                openingLine = c.getString("opening_line"),
                persona = c.getString("persona"),
                googleVoice = c.optString("google_voice", "Puck"),
                speakingRate = c.optDouble("speaking_rate", 1.0).toFloat(),
                phonePitch = c.optDouble("phone_pitch", 1.0).toFloat(),
            )
        }
        return (shared to list).also { cache = it }
    }

    fun all(context: Context): List<Character> = load(context).second

    fun byId(context: Context, id: String): Character =
        all(context).firstOrNull { it.id == id } ?: all(context).first()

    private fun fill(template: String, prank: Prank): String = template
        .replace("{target_name}", prank.friendName.ifBlank { "the person" })
        .replace("{details}", prank.details.ifBlank { "none given" })
        .replace("{prankster_name}", prank.myName.ifBlank { "a friend" })
        .replace("{max_turns}", prank.maxTurns.toString())

    fun openingLine(prank: Prank): String = fill(prank.character.openingLine, prank)

    /** The friendly reveal the app says itself when the friend sounds worried. */
    fun instantReveal(prank: Prank): String {
        val me = prank.myName.ifBlank { "a friend of yours" }
        val them = prank.friendName.ifBlank { "there" }
        return "Sorry $them, don't worry, this is just a joke. You've been pranked! " +
            "$me set this up. Nothing's wrong at all. Have a lovely day!"
    }

    fun systemPrompt(context: Context, prank: Prank): String {
        val shared = load(context).first
        return fill(
            shared + "\n\nYOUR CHARACTER: ${prank.character.name}\n" + prank.character.persona + APP_NOTES,
            prank,
        )
    }

    /** Words in the reveal line that tell the app the joke is over. */
    fun isReveal(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("pranked") || t.contains("a prank") || t.contains("been had") ||
            t.contains("wound up") || t.contains("set this up") || t.contains("just a joke")
    }

    private val APP_NOTES = """


THE CALL SO FAR
- You have already said your opening line. The person's replies come to you as speech-to-text transcripts, so expect misheard words and guess sensibly.
- Notes from the app come in square brackets and are never spoken by the person:
  - [Reply N of about M] tells you how far through the call you are.
  - [Do the reveal now] means do the reveal in this reply.
  - [Silence on the line] means they haven't said anything for a while. Check they're still there, in character.
- Output only the words to be spoken: no names, labels, brackets, asterisks or stage directions.
""".trimEnd()
}
