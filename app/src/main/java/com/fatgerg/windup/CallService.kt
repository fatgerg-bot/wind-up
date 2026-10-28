package com.fatgerg.windup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Runs one prank call: makes the opening line ready before they answer, then listens to the
 * call phone, transcribes each turn, asks Claude what the character says, and speaks it.
 * Runs as a foreground service so the Pixel doesn't put it to sleep mid-call.
 */
class CallService : Service() {
    private lateinit var settings: Settings
    private lateinit var phoneVoice: PhoneVoice
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private var listener: CallListener? = null
    private var transcriber: Transcriber? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // One worker, so turns, replies and speech happen strictly one at a time.
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var watchdog: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var prank: Prank? = null
    private var opening: Pcm? = null
    @Volatile private var inCall = false
    @Volatile private var revealed = false
    @Volatile private var finished = false
    @Volatile private var replies = 0
    @Volatile private var repliesAfterReveal = 0
    @Volatile private var lastHeard = 0L
    @Volatile private var lastSpoke = 0L
    @Volatile private var silenceNudges = 0
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        phoneVoice = PhoneVoice(this).also { it.init() }
        speaker = Speaker(this, settings, GoogleVoice(settings), phoneVoice) { CallState.add(Who.APP, it) }
        brain = Brain(settings)
        goForeground("Wind-Up ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> prepare()
            ACTION_ANSWERED -> answered()
            ACTION_REVEAL -> revealNow()
            ACTION_SAY -> intent.getStringExtra(EXTRA_TEXT)?.let { sayTyped(it) }
            ACTION_STOP_TALKING -> speaker.stopNow()
            ACTION_HANG_UP -> hangUp()
            ACTION_HEAR_VOICE -> intent.getStringExtra(EXTRA_TEXT)?.let { hearVoice(it) }
            ACTION_TEST_TONE -> testTone()
        }
        return START_NOT_STICKY
    }

    private fun currentPrank(): Prank = Prank(
        character = Characters.byId(this, settings.characterId),
        friendName = settings.friendName.trim(),
        details = settings.friendDetails.trim(),
        myName = settings.myName.trim(),
        maxTurns = settings.maxTurns.coerceIn(2, 20),
    )

    // ---- Before the call ------------------------------------------------------------------

    /** Loads everything and makes the opening line, so it plays the instant they answer. */
    private fun prepare() {
        if (inCall) return
        CallState.clearLog()
        CallState.setStatus(Status.PREPARING)
        acquireWakeLock()
        scope.launch(worker) {
            val p = currentPrank()
            prank = p
            revealed = false
            finished = false
            replies = 0
            repliesAfterReveal = 0
            speaker.resetGoogle()
            if (settings.claudeKey.isBlank()) {
                failPrepare("No Claude API key yet. Add it in Settings.")
                return@launch
            }
            if (p.friendName.isBlank()) CallState.add(Who.APP, "Tip: add your friend's name, it makes the joke land.")
            CallState.add(Who.APP, "${p.character.name} is getting ready")
            try {
                if (transcriber == null) {
                    CallState.add(Who.APP, "Loading speech recognition…")
                    transcriber = Transcriber(assets)
                }
            } catch (e: Throwable) {
                failPrepare("Couldn't load speech recognition: ${e.message}")
                return@launch
            }
            opening = speaker.synth(Characters.openingLine(p), p.character)
            if (opening == null) {
                failPrepare("Couldn't make the voice. Check the Google key, or the phone's text-to-speech.")
                return@launch
            }
            CallState.setDetail(if (speaker.usingGoogle) "Google voice" else "Phone's own voice")
            val cable = AudioRoutes.hasCable(this@CallService)
            CallState.setRoute(AudioRoutes.describe(this@CallService))
            startListener(cable)
            CallState.add(Who.APP, "Ready. Dial your friend on the call phone, then tap \"They've answered\" the moment they pick up.")
            CallState.setStatus(Status.READY)
        }
    }

    private suspend fun failPrepare(message: String) {
        CallState.add(Who.APP, message)
        CallState.setStatus(Status.IDLE)
        releaseWakeLock()
        withContext(Dispatchers.Main) { stopSelfIfIdle() }
    }

    private fun startListener(cable: Boolean) {
        listener?.stop()
        listener = CallListener(
            this, settings,
            onTurnStarted = {
                lastHeard = System.currentTimeMillis()
                if (!busy && !finished) CallState.setStatus(Status.HEARING)
            },
            onTurn = { samples -> scope.launch(worker) { handleTurn(samples) } },
            onBargeIn = {
                speaker.stopNow()
                CallState.add(Who.APP, "They talked over the character")
            },
            onDiscard = { if (!busy && inCall && !finished) CallState.setStatus(Status.LISTENING) },
        ).also {
            it.interruptAllowed = cable
            it.muted = true // nothing to hear until they answer
            it.start()
        }
    }

    // ---- The call -------------------------------------------------------------------------

    private fun answered() {
        val p = prank
        val pcm = opening
        if (p == null || pcm == null || inCall) return
        inCall = true
        goForeground("Prank call in progress")
        scope.launch(worker) {
            val line = Characters.openingLine(p)
            brain.start(Characters.systemPrompt(this@CallService, p), line)
            listener?.muted = false
            speakNow(line) { speaker.playPrepared(pcm) }
            if (!finished) CallState.setStatus(Status.LISTENING)
        }
        startWatchdog()
    }

    private suspend fun handleTurn(samples: FloatArray) {
        val p = prank ?: return
        if (!inCall || finished) return
        busy = true
        try {
            CallState.setStatus(Status.THINKING)
            val text = try {
                transcriber?.transcribe(samples).orEmpty()
            } catch (e: Throwable) {
                CallState.add(Who.APP, "Speech recognition error: ${e.message}")
                ""
            }
            if (text.isBlank()) return
            CallState.add(Who.FRIEND, text)
            silenceNudges = 0

            // Worried or angry? Stop the joke straight away, before Claude sees anything.
            val word = revealWord(text)
            if (word != null && !revealed) {
                CallState.add(Who.APP, "Heard \"$word\", revealing now")
                instantReveal(p)
                return
            }

            if (revealed) {
                repliesAfterReveal++
                think(text)
                if (repliesAfterReveal >= 1) finishUp()
                return
            }

            replies++
            if (replies > p.maxTurns + 1) {
                // Claude was asked to reveal but didn't say the words, so the app does it.
                instantReveal(p)
                return
            }
            val note = if (replies >= p.maxTurns) "[Do the reveal now]" else "[Reply $replies of about ${p.maxTurns}]"
            CallState.setDetail("Reply $replies of about ${p.maxTurns}")
            think("$text\n$note")
        } finally {
            busy = false
            if (inCall && !finished) CallState.setStatus(Status.LISTENING)
        }
    }

    /** Asks Claude for the character's reply and speaks it. Runs on the worker. */
    private suspend fun think(input: String) {
        val p = prank ?: return
        busy = true
        try {
            CallState.setStatus(Status.THINKING)
            val reply = try {
                // Blocks the worker on purpose: suspending here would let the next turn start
                // a second, overlapping Claude call and reply.
                brain.reply(input)
            } catch (e: Exception) {
                CallState.add(Who.APP, e.message ?: "Couldn't reach Claude")
                val filler = "Sorry, you're breaking up a bit there. Say that again?"
                if (!finished) speakNow(filler) { speaker.speak(filler, p.character) }
                return
            }
            if (reply.isBlank() || finished) return
            if (Characters.isReveal(reply)) markRevealed()
            speakNow(reply) { speaker.speak(reply, p.character) }
        } finally {
            busy = false
            if (inCall && !finished) CallState.setStatus(Status.LISTENING)
        }
    }

    /** Speaks, telling the listener so echo isn't heard as the friend. */
    private suspend fun speakNow(text: String, play: () -> Boolean) {
        CallState.add(Who.CHARACTER, text)
        CallState.setStatus(Status.SPEAKING)
        listener?.speaking = true
        val whole = try { play() } catch (e: Exception) {
            CallState.add(Who.APP, "Voice error: ${e.message}")
            true
        }
        if (whole) Thread.sleep(250) // the call phone's echo dies away before listening again
        listener?.speaking = false
        lastSpoke = System.currentTimeMillis()
        if (!whole) CallState.add(Who.APP, "(cut off)")
    }

    private fun revealWord(text: String): String? {
        val t = " " + text.lowercase()
            .replace("’", "'")
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ") + " "
        return settings.revealWordList().firstOrNull { t.contains(" $it ") }
    }

    private suspend fun instantReveal(p: Prank) {
        val line = Characters.instantReveal(p)
        markRevealed()
        brain.noteSpoken(line)
        listener?.muted = true // the safety reveal is always heard in full, never talked over
        speakNow(line) { speaker.speak(line, p.character) }
        finishUp()
    }

    private fun markRevealed() {
        if (revealed) return
        revealed = true
        CallState.setDetail("Revealed")
    }

    /** The joke's done: stop listening and hand back to Greg. */
    private fun finishUp() {
        finished = true
        listener?.muted = true
        CallState.setStatus(Status.DONE)
        CallState.add(Who.APP, "Prank over. Chat to them yourself on the call phone, or hang up.")
    }

    // ---- Buttons ---------------------------------------------------------------------------

    private fun revealNow() {
        val p = prank ?: return
        if (!inCall || revealed) return
        speaker.stopNow()
        scope.launch(worker) {
            // A double tap, or a reveal/hang-up that happened while this waited its turn.
            if (!inCall || finished || revealed) return@launch
            if (settings.claudeKey.isBlank()) {
                instantReveal(p)
                return@launch
            }
            CallState.add(Who.APP, "Reveal button pressed")
            think("[Do the reveal now]")
            if (!revealed && !finished) {
                // Claude didn't say the magic words, so say them for it.
                instantReveal(p)
            }
        }
    }

    private fun sayTyped(text: String) {
        val p = prank ?: currentPrank()
        scope.launch(worker) {
            val before = CallState.status.value
            if (inCall) brain.noteSpoken(text)
            if (inCall && Characters.isReveal(text)) markRevealed()
            speakNow(text) { speaker.speak(text, p.character) }
            // speakNow left it on SPEAKING; put back READY / DONE when not mid-call.
            if (inCall && !finished) CallState.setStatus(Status.LISTENING)
            else CallState.setStatus(if (inCall) Status.DONE else before)
        }
    }

    /** Plays a line in the chosen character's voice, for trying voices before a call. */
    private fun hearVoice(text: String) {
        if (inCall) return
        scope.launch(worker) {
            speaker.resetGoogle()
            val c = Characters.byId(this@CallService, settings.characterId)
            CallState.setRoute(AudioRoutes.describe(this@CallService))
            try {
                speaker.speak(text, c)
                CallState.add(Who.APP, if (speaker.usingGoogle) "That was the Google voice" else "That was the phone's own voice")
            } catch (e: Exception) {
                CallState.add(Who.APP, "Voice error: ${e.message}")
            }
            withContext(Dispatchers.Main) { stopSelfIfIdle() }
        }
    }

    private fun testTone() {
        if (inCall) return
        scope.launch(worker) {
            CallState.setRoute(AudioRoutes.describe(this@CallService))
            CallState.add(Who.APP, "Test tone, 3 seconds")
            speaker.testTone(3)
            withContext(Dispatchers.Main) { stopSelfIfIdle() }
        }
    }

    private fun hangUp() {
        speaker.stopNow()
        watchdog?.cancel()
        val wasInCall = inCall
        inCall = false
        finished = true
        scope.launch(worker) {
            listener?.stop()
            listener = null
            opening = null
            if (wasInCall) saveLog()
            releaseWakeLock()
            CallState.setStatus(Status.IDLE)
            CallState.setDetail("")
            withContext(Dispatchers.Main) { stopSelfIfIdle() }
        }
    }

    // ---- Silence on the line ---------------------------------------------------------------

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive && inCall) {
                delay(1000)
                if (busy || finished) continue
                if (CallState.status.value == Status.HEARING) continue
                val now = System.currentTimeMillis()
                if (revealed) {
                    // Revealed and they've gone quiet: hand back to Greg.
                    if (now - maxOf(lastSpoke, lastHeard) > SILENCE_MS) finishUp()
                    continue
                }
                val quietSince = maxOf(lastSpoke, lastHeard)
                if (lastSpoke > 0 && now - quietSince > SILENCE_MS && silenceNudges < 2) {
                    silenceNudges++
                    lastSpoke = now
                    scope.launch(worker) { if (!busy && !finished) think("[Silence on the line]") }
                }
            }
        }
    }

    // ---- Housekeeping ----------------------------------------------------------------------

    private fun stopSelfIfIdle() {
        // The status check stops a finished "Hear the opening" killing a "Get ready" queued behind it.
        if (!inCall && listener == null && CallState.status.value == Status.IDLE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "windup:call").apply {
            acquire(60 * 60 * 1000L) // at most an hour
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun saveLog() {
        try {
            val dir = getExternalFilesDir("calls") ?: return
            dir.mkdirs()
            val name = "call-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.UK).format(Date()) + ".txt"
            File(dir, name).writeText(CallState.logAsText(prank?.character?.name ?: "Character"))
            CallState.add(Who.APP, "Call script saved as $name")
        } catch (_: Exception) {}
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Prank call", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("Wind-Up")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    override fun onDestroy() {
        inCall = false
        watchdog?.cancel()
        listener?.stop()
        releaseWakeLock()
        speaker.shutdown()
        phoneVoice.shutdown()
        transcriber?.release()
        scope.cancel()
        worker.close()
        CallState.setStatus(Status.IDLE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "call"
        private const val SILENCE_MS = 9000L
        const val ACTION_PREPARE = "prepare"
        const val ACTION_ANSWERED = "answered"
        const val ACTION_REVEAL = "reveal"
        const val ACTION_SAY = "say"
        const val ACTION_STOP_TALKING = "stop_talking"
        const val ACTION_HANG_UP = "hang_up"
        const val ACTION_HEAR_VOICE = "hear_voice"
        const val ACTION_TEST_TONE = "tone"
        const val EXTRA_TEXT = "text"

        fun send(context: Context, action: String, text: String? = null) {
            val i = Intent(context, CallService::class.java).setAction(action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            context.startForegroundService(i)
        }
    }
}
