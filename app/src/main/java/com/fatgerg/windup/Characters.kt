package com.fatgerg.windup

import android.content.Context
import org.json.JSONObject
import kotlin.random.Random

/** One reason a character might ring: what they say first and how the call should go. */
data class Reason(
    val id: String,
    val title: String,
    val opening: String,
    val plot: String,
)

data class Character(
    val id: String,
    val name: String,
    val voiceDesign: String,
    val persona: String,
    val reasons: List<Reason>,
    val googleVoice: String,
    val speakingRate: Float,
    val phonePitch: Float,
)

/** Everything about one prank: who's calling, why, who to, and how silly. */
data class Prank(
    val character: Character,
    val reason: Reason,
    val friendName: String,
    val details: String,
    val myName: String,
    val maxTurns: Int,
    val silliness: Int,
)

/** Loads the characters from assets/characters.json and builds Claude's instructions. */
object Characters {
    /** The reason id that means "ask Claude to invent a brand-new reason for this call". */
    const val INVENT = "invent"

    private class Data(
        val shared: String,
        val silliness: List<Pair<String, String>>, // name to instructions, levels 1..5
        val characters: List<Character>,
    )

    @Volatile private var cache: Data? = null

    private fun load(context: Context): Data {
        cache?.let { return it }
        val raw = context.assets.open("characters.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val levels = root.optJSONArray("silliness_levels")
        val silliness = if (levels == null) emptyList() else (0 until levels.length()).map { i ->
            val l = levels.getJSONObject(i)
            l.getString("name") to l.getString("text")
        }
        val arr = root.getJSONArray("characters")
        val list = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val rs = c.optJSONArray("reasons")
            val reasons = if (rs == null) {
                // An older-style character with a single opening line.
                listOf(Reason("main", "The usual", c.optString("opening_line"), ""))
            } else {
                (0 until rs.length()).map { j ->
                    val r = rs.getJSONObject(j)
                    Reason(r.getString("id"), r.optString("title", r.getString("id")), r.getString("opening"), r.optString("plot"))
                }
            }
            Character(
                id = c.getString("id"),
                name = c.getString("name"),
                voiceDesign = c.optString("voice_design"),
                persona = c.getString("persona"),
                reasons = reasons,
                googleVoice = c.optString("google_voice", "Puck"),
                speakingRate = c.optDouble("speaking_rate", 1.0).toFloat(),
                phonePitch = c.optDouble("phone_pitch", 1.0).toFloat(),
            )
        }
        return Data(root.getString("shared_rules"), silliness, list).also { cache = it }
    }

    fun all(context: Context): List<Character> = load(context).characters

    fun byId(context: Context, id: String): Character =
        all(context).firstOrNull { it.id == id } ?: all(context).first()

    /** Names of the silliness levels, 1 to 5. */
    fun sillinessNames(context: Context): List<String> =
        load(context).silliness.map { it.first }.ifEmpty { listOf("Believable", "Cheeky", "Silly", "Daft", "Bonkers") }

    /**
     * Picks this call's reason. A chosen one is used as is; otherwise it's a surprise that's
     * never the same as last time, and at silliness 4 or 5 there's a one-in-three chance
     * Claude makes up a brand-new reason instead.
     */
    fun pickReason(character: Character, chosenId: String, lastId: String, silliness: Int): Reason {
        if (chosenId.isNotBlank()) character.reasons.firstOrNull { it.id == chosenId }?.let { return it }
        if (silliness >= 4 && Random.nextInt(3) == 0) return Reason(INVENT, "Something new", "", "")
        val fresh = character.reasons.filter { it.id != lastId }.ifEmpty { character.reasons }
        return fresh.random()
    }

    private fun fill(template: String, prank: Prank, sillyText: String = ""): String = template
        .replace("{target_name}", prank.friendName.ifBlank { "the person" })
        .replace("{details}", prank.details.ifBlank { "none given" })
        .replace("{prankster_name}", prank.myName.ifBlank { "a friend" })
        .replace("{max_turns}", prank.maxTurns.toString())
        .replace("{silliness}", sillyText)

    fun openingLine(prank: Prank): String = fill(prank.reason.opening, prank)

    /** The friendly reveal the app says itself when the friend sounds worried. */
    fun instantReveal(prank: Prank): String {
        val me = prank.myName.ifBlank { "a friend of yours" }
        val them = prank.friendName.ifBlank { "there" }
        return "Sorry $them, don't worry, this is just a joke. You've been pranked! " +
            "$me set this up. Nothing's wrong at all. Have a lovely day!"
    }

    private fun sillyText(context: Context, level: Int): String {
        val levels = load(context).silliness
        if (levels.isEmpty()) return "Level $level of 5."
        return levels[(level - 1).coerceIn(0, levels.size - 1)].second
    }

    fun systemPrompt(context: Context, prank: Prank): String {
        val data = load(context)
        val r = prank.reason
        val why = "\n\nWHY YOU'RE RINGING THIS TIME: ${r.title}\n" +
            "You opened the call by saying: \"${r.opening}\"\n" + r.plot
        return fill(
            data.shared + "\n\nYOUR CHARACTER: ${prank.character.name}\n" + prank.character.persona + why + APP_NOTES,
            prank,
            sillyText(context, prank.silliness),
        )
    }

    /** Instructions for Claude to invent a brand-new reason for this character to ring. */
    fun inventPrompt(context: Context, prank: Prank): String {
        val c = prank.character
        val usual = c.reasons.joinToString("; ") { it.title }
        return fill(
            """
You write premises for a friendly comedy prank-call app. Invent ONE brand-new reason for this character to ring {target_name}.

CHARACTER: ${c.name}
${c.persona}

Their usual reasons, which you must NOT reuse: $usual.
Details about {target_name}: {details}.
How silly: {silliness}

Rules: the premise must be harmless and kind. The character never asks for money, bank details, passwords, addresses or personal information, never claims to be the police, a bank, the NHS or any real organisation or real living person, and never suggests anyone is hurt, in trouble or in danger. It can be a wrong number now and then.

Reply with ONLY a JSON object, nothing else:
{"title": "a few words", "opening": "the first thing the character says when they pick up, one or two short sentences, in character", "plot": "two or three sentences on how the call should unfold"}
""".trim(),
            prank,
            sillyText(context, prank.silliness),
        )
    }

    /** Reads Claude's invented reason, or null if it isn't usable. */
    fun parseInvented(text: String): Reason? = try {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val o = JSONObject(text.substring(start, end + 1))
        val opening = o.getString("opening").trim()
        if (opening.isBlank()) null
        else Reason(INVENT, o.optString("title", "Something new").trim(), opening, o.optString("plot").trim())
    } catch (_: Exception) {
        null
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
