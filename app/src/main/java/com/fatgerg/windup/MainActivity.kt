package com.fatgerg.windup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Plum = Color(0xFF1B1530)
private val Card = Color(0xFF2A2147)
private val Coral = Color(0xFFFF7A59)
private val Sun = Color(0xFFFFC94A)
private val Green = Color(0xFF2FBF71)
private val Red = Color(0xFFE5484D)
private val Muted = Color(0xFFA99FC7)

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        requestBasics()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Plum, surface = Plum, primary = Coral)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Plum) {
                    var onSettings by remember { mutableStateOf(false) }
                    if (onSettings) SettingsScreen(onBack = { onSettings = false })
                    else MainScreen(onSettings = { onSettings = true })
                }
            }
        }
    }

    private fun requestBasics() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun send(action: String, text: String? = null) = CallService.send(this, action, text)

    // ---- Main screen ---------------------------------------------------------------------

    @Composable
    private fun MainScreen(onSettings: () -> Unit) {
        val status by CallState.status.collectAsState()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.logo_mark),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text("Wind-Up", color = Coral, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onSettings) { Text("Settings", color = Muted) }
            }
            Spacer(Modifier.height(8.dp))
            if (status == Status.IDLE) PrankForm() else CallPanel(status)
        }
    }

    @Composable
    private fun PrankForm() {
        val characters = remember { Characters.all(this) }
        var charId by remember { mutableStateOf(settings.characterId) }
        var friend by remember { mutableStateOf(settings.friendName) }
        var details by remember { mutableStateOf(settings.friendDetails) }
        var me by remember { mutableStateOf(settings.myName) }
        var turns by remember { mutableStateOf(settings.maxTurns.toString()) }
        var voice by remember { mutableStateOf(settings.voiceFor(settings.characterId)) }
        val log by CallState.log.collectAsState()
        val character = characters.firstOrNull { it.id == charId } ?: characters.first()

        fun saveForm() {
            settings.characterId = character.id
            settings.friendName = friend
            settings.friendDetails = details
            settings.myName = me
            turns.toIntOrNull()?.let { settings.maxTurns = it.coerceIn(2, 20) }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Label("Who's calling")
            Picker(
                current = character.name,
                options = characters.map { it.id to it.name },
                onPick = { id ->
                    charId = id
                    settings.characterId = id
                    voice = settings.voiceFor(id)
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Portrait(character.id, 84)
                Spacer(Modifier.width(12.dp))
                Text(character.voiceDesign, color = Muted, fontSize = 13.sp)
            }

            Label("Voice")
            if (settings.googleKey.isBlank()) {
                Text("Using the phone's own voice. Add a Google key in Settings for the realistic voices.",
                    color = Sun, fontSize = 12.sp)
            } else {
                val own = GoogleVoice.ALL.firstOrNull { it.first == character.googleVoice }
                Picker(
                    current = if (voice.isBlank()) "Character's own: ${character.googleVoice} (${own?.second ?: ""})"
                        else "$voice (${GoogleVoice.ALL.firstOrNull { it.first == voice }?.second ?: ""})",
                    options = listOf("" to "Character's own: ${character.googleVoice}") +
                        GoogleVoice.ALL.map { it.first to "${it.first} (${it.second})" },
                    onPick = { v ->
                        voice = v
                        settings.setVoiceFor(character.id, v)
                    },
                )
            }

            Label("Your friend")
            Field("First name", friend, { friend = it })
            Field("A few harmless details (job, pet, hobby…)", details, { details = it }, lines = 3)
            Field("Your name, for the reveal", me, { me = it })
            Field("Reveal after about this many replies", turns, { turns = it }, number = true)

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (!hasMic()) { requestBasics(); return@OutlinedButton }
                    saveForm()
                    val p = Prank(character, friend.trim(), details.trim(), me.trim(), settings.maxTurns)
                    send(CallService.ACTION_HEAR_VOICE, Characters.openingLine(p))
                }, modifier = Modifier.weight(1f)) { Text("Hear the opening") }
                OutlinedButton(onClick = {
                    if (!hasMic()) { requestBasics(); return@OutlinedButton }
                    send(CallService.ACTION_TEST_TONE)
                },
                    modifier = Modifier.weight(1f)) { Text("Test tone") }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (!hasMic()) { requestBasics(); return@Button }
                    saveForm()
                    send(CallService.ACTION_PREPARE)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("Get ready", fontSize = 22.sp, color = Plum, fontWeight = FontWeight.Bold) }

            if (log.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                log.takeLast(4).forEach { Text(it.text, color = Muted, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    @Composable
    private fun ColumnScope.CallPanel(status: Status) {
        val level by CallState.level.collectAsState()
        val speaking by CallState.speech.collectAsState()
        val log by CallState.log.collectAsState()
        val route by CallState.route.collectAsState()
        val detail by CallState.detail.collectAsState()
        var sayText by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        val name = Characters.byId(this@MainActivity, settings.characterId).name

        LaunchedEffect(log.size) {
            if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
        }

        Text(route, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))

        val lamp = when (status) {
            Status.LISTENING, Status.HEARING -> Green
            Status.THINKING, Status.PREPARING -> Sun
            Status.SPEAKING -> Coral
            Status.READY -> Green
            Status.DONE, Status.IDLE -> Muted
        }
        Row(
            Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Portrait(settings.characterId, 52)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(18.dp).background(lamp, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(status.label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (detail.isNotEmpty()) Text(detail, color = Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Call line level", color = Muted, fontSize = 12.sp)
        LinearProgressIndicator(
            progress = { level / 100f },
            modifier = Modifier.fillMaxWidth().height(12.dp),
            color = if (speaking) Green else Muted,
            trackColor = Card,
        )
        Spacer(Modifier.height(10.dp))

        when (status) {
            Status.READY -> BigButton("They've answered: go!", Green) { send(CallService.ACTION_ANSWERED) }
            Status.LISTENING, Status.HEARING, Status.THINKING, Status.SPEAKING ->
                BigButton("Reveal now", Coral) { send(CallService.ACTION_REVEAL) }
            else -> {}
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { send(CallService.ACTION_STOP_TALKING) }, modifier = Modifier.weight(1f)) {
                Text("Stop talking")
            }
            Button(
                onClick = { send(CallService.ACTION_HANG_UP) },
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                modifier = Modifier.weight(1f),
            ) { Text(if (status == Status.PREPARING || status == Status.READY) "Cancel" else "Finished") }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = sayText,
                onValueChange = { sayText = it },
                label = { Text("Make $name say…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (sayText.isNotBlank()) {
                    send(CallService.ACTION_SAY, sayText.trim())
                    sayText = ""
                }
            }) { Text("Say") }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = Card)

        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(log) { line ->
                val (who, colour) = when (line.who) {
                    Who.FRIEND -> "FRIEND" to Green
                    Who.CHARACTER -> name.substringBefore(",").substringBefore(" from").uppercase() to Coral
                    Who.APP -> "APP" to Muted
                }
                Column(Modifier.padding(vertical = 5.dp)) {
                    Text("${line.time}  $who", color = colour, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(line.text, color = if (line.who == Who.APP) Muted else Color.White, fontSize = 15.sp)
                }
            }
        }
    }

    // ---- Settings screen -----------------------------------------------------------------

    @Composable
    private fun SettingsScreen(onBack: () -> Unit) {
        var claudeKey by remember { mutableStateOf(settings.claudeKey) }
        var model by remember { mutableStateOf(settings.model) }
        var googleKey by remember { mutableStateOf(settings.googleKey) }
        var eot by remember { mutableStateOf(settings.endOfTurnSeconds.toString()) }
        var threshold by remember { mutableStateOf(settings.talkThreshold.toString()) }
        var outLevel by remember { mutableStateOf(settings.outputLevel.toString()) }
        var interrupt by remember { mutableStateOf(settings.allowInterrupt) }
        var interruptLevel by remember { mutableStateOf(settings.interruptThreshold.toString()) }
        var words by remember { mutableStateOf(settings.revealWords) }

        fun save() {
            settings.claudeKey = claudeKey
            settings.model = model
            settings.googleKey = googleKey
            eot.toFloatOrNull()?.let { settings.endOfTurnSeconds = it.coerceIn(0.3f, 3f) }
            threshold.toIntOrNull()?.let { settings.talkThreshold = it.coerceIn(1, 90) }
            outLevel.toIntOrNull()?.let { settings.outputLevel = it.coerceIn(10, 100) }
            settings.allowInterrupt = interrupt
            interruptLevel.toIntOrNull()?.let { settings.interruptThreshold = it.coerceIn(1, 90) }
            settings.revealWords = words
            requestBasics()
            onBack()
        }

        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = Coral, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("Cancel", color = Muted) }
                Button(onClick = { save() }) { Text("Save") }
            }

            Section("Claude (the jokes)")
            Field("Claude API key (stays on this phone)", claudeKey, { claudeKey = it }, secret = true)
            Field("Model", model, { model = it })

            Section("Google (the voices)")
            Field("Google Cloud Text-to-Speech API key", googleKey, { googleKey = it }, secret = true)
            Text("Leave it empty to use the phone's own voice. The first million characters a month are free.",
                color = Muted, fontSize = 12.sp)

            Section("Call line levels")
            Field("Character's volume into the call phone, 10-100", outLevel, { outLevel = it }, number = true)
            Field("Talk threshold, 1-90 (how loud before it counts)", threshold, { threshold = it }, number = true)
            Field("Quiet seconds that mean they've finished", eot, { eot = it }, number = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Let them talk over the character", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = interrupt, onCheckedChange = { interrupt = it })
            }
            Text("Turn this off if the character keeps cutting itself off.", color = Muted, fontSize = 12.sp)
            Field("How loud they must be to interrupt, 1-90", interruptLevel, { interruptLevel = it }, number = true)

            Section("Safety net")
            Text("Hearing any of these ends the joke straight away with a friendly reveal.",
                color = Muted, fontSize = 12.sp)
            Field("Reveal words, comma separated", words, { words = it }, lines = 4)
            TextButton(onClick = { words = Settings.DEFAULT_REVEAL_WORDS }) { Text("Reset to the built-in list") }

            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }, modifier = Modifier.fillMaxWidth()) { Text("Stop the phone putting Wind-Up to sleep") }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    // ---- Small pieces ----------------------------------------------------------------------

    /** The character's cartoon, from res/drawable-nodpi/portrait_<id>.png. Nothing if there isn't one. */
    @Composable
    private fun Portrait(characterId: String, sizeDp: Int) {
        val res = remember(characterId) {
            resources.getIdentifier("portrait_$characterId", "drawable", packageName)
        }
        if (res != 0) {
            Image(
                painter = painterResource(res),
                contentDescription = null,
                modifier = Modifier.size(sizeDp.dp).clip(CircleShape),
            )
        }
    }

    @Composable
    private fun BigButton(text: String, colour: Color, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = colour),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text(text, fontSize = 22.sp, color = Plum, fontWeight = FontWeight.Bold) }
    }

    @Composable
    private fun Picker(current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
        var open by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text("$current  ▾", color = Color.White, fontSize = 14.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onPick(id); open = false })
                }
            }
        }
    }

    @Composable
    private fun Label(title: String) {
        Spacer(Modifier.height(14.dp))
        Text(title.uppercase(), color = Sun, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
    }

    @Composable
    private fun Section(title: String) = Label(title)

    @Composable
    private fun Field(
        label: String,
        value: String,
        onChange: (String) -> Unit,
        secret: Boolean = false,
        number: Boolean = false,
        lines: Int = 1,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = lines == 1,
            minLines = lines,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal)
                else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}
