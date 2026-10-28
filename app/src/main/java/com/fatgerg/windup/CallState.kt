package com.fatgerg.windup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Status(val label: String) {
    IDLE("Not ready"),
    PREPARING("Getting ready…"),
    READY("Ready: dial on the call phone"),
    LISTENING("Listening"),
    HEARING("They're talking"),
    THINKING("Thinking"),
    SPEAKING("Speaking"),
    DONE("Prank revealed"),
}

enum class Who { FRIEND, CHARACTER, APP }

data class LogLine(val time: String, val who: Who, val text: String)

/** Shared between the call service and the screen. */
object CallState {
    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    /** Line loudness 0-100, for the level meter. */
    private val _level = MutableStateFlow(0)
    val level: StateFlow<Int> = _level.asStateFlow()

    private val _speech = MutableStateFlow(false)
    val speech: StateFlow<Boolean> = _speech.asStateFlow()

    private val _route = MutableStateFlow("")
    val route: StateFlow<String> = _route.asStateFlow()

    /** "Reply 3 of about 6", or "Google voice", shown under the status. */
    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()

    /** True while the call is being recorded. */
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** True while the call is playing live to Bluetooth headphones. */
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** The last saved recording, for the Share button. */
    private val _lastRecording = MutableStateFlow<android.net.Uri?>(null)
    val lastRecording: StateFlow<android.net.Uri?> = _lastRecording.asStateFlow()

    fun setRecording(v: Boolean) { _recording.value = v }
    fun setListening(v: Boolean) { _listening.value = v }
    fun setLastRecording(u: android.net.Uri?) { _lastRecording.value = u }

    fun setStatus(s: Status) { _status.value = s }
    fun setLevel(l: Int) { _level.value = l }
    fun setSpeech(v: Boolean) { if (_speech.value != v) _speech.value = v }
    fun setRoute(r: String) { _route.value = r }
    fun setDetail(d: String) { _detail.value = d }

    fun add(who: Who, text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
        _log.update { (it + LogLine(time, who, text)).takeLast(300) }
    }

    fun clearLog() { _log.value = emptyList() }

    fun logAsText(characterName: String): String = _log.value.joinToString("\n") { l ->
        val who = when (l.who) {
            Who.FRIEND -> "FRIEND"
            Who.CHARACTER -> characterName.uppercase()
            Who.APP -> "APP"
        }
        "${l.time}  $who: ${l.text}"
    }
}
