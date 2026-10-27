package at.askmannersdorf.ergebnis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import at.askmannersdorf.ergebnis.data.AppSettings
import at.askmannersdorf.ergebnis.data.SmsCounter
import at.askmannersdorf.ergebnis.flyer.FlyerFormat
import at.askmannersdorf.ergebnis.flyer.ShareTarget
import at.askmannersdorf.ergebnis.model.Goal

private val AskGreen = Color(0xFF0A3F1C)
private val AskLime = Color(0xFFB9F23C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = AskGreen, onPrimary = Color.White,
                    secondary = AskLime, onSecondary = AskGreen,
                    surface = Color.White, background = Color(0xFFF2F5F3),
                )
            ) {
                val vm: AppViewModel = viewModel()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                Column(
                    Modifier.fillMaxSize().background(Color(0xFFF2F5F3)).safeDrawingPadding().imePadding()
                ) {
                    Header(showSettings) { showSettings = !showSettings }
                    if (showSettings) {
                        BackHandler { showSettings = false }
                        SettingsScreen(vm.settings) { vm.saveSettings(it); showSettings = false }
                    } else {
                        MainScreen(vm, this@MainActivity)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(settingsOpen: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(AskGreen).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "ASK ERGEBNIS", color = Color.White, fontSize = 22.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggle) {
            Text(if (settingsOpen) "Zurück" else "Einstellungen", color = AskLime)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AskGreen)
            content()
        }
    }
}

@Composable
private fun MainScreen(vm: AppViewModel, activity: ComponentActivity) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        MatchSection(vm)
        SmsSection(vm)
        FlyerSection(vm, activity)
        Spacer(Modifier.height(24.dp))
    }
}

// ── 1. Spiel ────────────────────────────────────────────────────────

@Composable
private fun MatchSection(vm: AppViewModel) {
    var showRaw by remember { mutableStateOf(false) }
    val m = vm.match
    var gameMenu by remember { mutableStateOf(false) }
    Section("1 · Spiel") {
        Column {
            OutlinedButton(onClick = { if (vm.games.isNotEmpty()) gameMenu = true else vm.loadSchedule() }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    vm.selectedGame?.label() ?: if (vm.scheduleLoading) "Spielplan wird geladen …" else "Spielplan laden",
                    modifier = Modifier.weight(1f), maxLines = 2,
                )
                Text(" ▾")
            }
            DropdownMenu(gameMenu, { gameMenu = false }) {
                vm.games.forEach { g ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                g.label(),
                                fontWeight = if (g == vm.selectedGame) FontWeight.Bold else FontWeight.Normal,
                                color = if (g.isPlayed) Color.Unspecified else Color.Gray,
                            )
                        },
                        onClick = { vm.selectedGame = g; gameMenu = false },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(vm.scheduleStatus.orEmpty(), fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::loadSchedule, enabled = !vm.scheduleLoading) { Text("Neu laden") }
        }
        Button(onClick = vm::fetchMatch, enabled = !vm.fetching, modifier = Modifier.fillMaxWidth()) {
            if (vm.fetching) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Wird abgefragt …")
            } else Text(if (vm.selectedGame != null) "Ergebnis abrufen (Liveticker)" else "Ergebnis abrufen")
        }
        vm.fetchLog.forEach { Text("• $it", fontSize = 13.sp, color = Color.DarkGray) }

        OutlinedTextField(m.homeTeam, { vm.updateMatch(m.copy(homeTeam = it)) }, label = { Text("Heim") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(m.awayTeam, { vm.updateMatch(m.copy(awayTeam = it)) }, label = { Text("Gast") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                m.homeGoals?.toString() ?: "", { vm.updateMatch(m.copy(homeGoals = it.filter(Char::isDigit).take(2).toIntOrNull())) },
                label = { Text("Tore Heim") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                m.awayGoals?.toString() ?: "", { vm.updateMatch(m.copy(awayGoals = it.filter(Char::isDigit).take(2).toIntOrNull())) },
                label = { Text("Tore Gast") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                m.halfHome?.toString() ?: "", { vm.updateMatch(m.copy(halfHome = it.filter(Char::isDigit).take(2).toIntOrNull())) },
                label = { Text("Halbzeit Heim") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                m.halfAway?.toString() ?: "", { vm.updateMatch(m.copy(halfAway = it.filter(Char::isDigit).take(2).toIntOrNull())) },
                label = { Text("Halbzeit Gast") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        if (m.date.isNotBlank() || m.source.isNotBlank()) {
            Text(listOf(m.date, m.source.takeIf { it.isNotBlank() }?.let { "Quelle: $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = Color.Gray)
        }

        HorizontalDivider()
        Text("Tore in Reihenfolge (Haken = Tor für ASK)", fontWeight = FontWeight.SemiBold)
        Text("Gegentore ohne Haken eintragen, damit der Zwischenstand in der SMS stimmt.", fontSize = 12.sp, color = Color.Gray)
        m.goals.forEachIndexed { i, g -> GoalRow(g, { vm.updateGoal(i, it) }, { vm.removeGoal(i) }) }
        OutlinedButton(onClick = vm::addGoal) { Text("+ Tor hinzufügen") }

        if (vm.rawText.isNotBlank()) {
            TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Rohdaten ausblenden" else "Rohdaten der Abfrage anzeigen") }
            if (showRaw) Text(vm.rawText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun GoalRow(g: Goal, onChange: (Goal) -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(g.minute, { onChange(g.copy(minute = it)) }, label = { Text("Min.") }, singleLine = true, modifier = Modifier.width(76.dp))
        OutlinedTextField(g.scorer, { onChange(g.copy(scorer = it)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
        Checkbox(g.forAsk, { onChange(g.copy(forAsk = it)) })
        TextButton(onClick = onRemove) { Text("✕") }
    }
}

// ── 2. SMS ──────────────────────────────────────────────────────────

@Composable
private fun SmsSection(vm: AppViewModel) {
    var confirm by remember { mutableStateOf(false) }
    var editRecipients by remember { mutableStateOf(false) }
    val (chars, parts) = SmsCounter.count(vm.smsText)
    val recipients = vm.recipients

    Section("2 · Ergebnis-SMS") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                vm.opponentShort, vm::onOpponentShortChanged, label = { Text("Gegner kurz") },
                singleLine = true, modifier = Modifier.weight(1.4f),
            )
            OutlinedTextField(
                vm.u23Result, vm::onU23Changed, label = { Text("U23 (z. B. 4-0)") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            vm.smsText, vm::onSmsTextChanged, label = { Text("SMS-Text") },
            modifier = Modifier.fillMaxWidth(), minLines = 4,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$chars Zeichen · $parts SMS" + if (vm.smsEdited) " · von Hand geändert" else "",
                fontSize = 13.sp, color = if (parts > 1) Color(0xFFB45309) else Color.Gray, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = vm::regenerateSms, enabled = vm.match.isComplete) { Text("Text neu erzeugen") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (recipients.isEmpty()) "Noch keine Empfänger hinterlegt" else "Empfänger: ${recipients.size} Nummern",
                modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = { editRecipients = true }) { Text(if (recipients.isEmpty()) "Hinzufügen" else "Bearbeiten") }
        }
        Button(
            onClick = { confirm = true },
            enabled = !vm.smsBusy && vm.smsText.isNotBlank() && recipients.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("SMS an alle Empfänger senden") }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                vm.testNumber, { vm.testNumber = it }, label = { Text("Testnummer (optional)") }, singleLine = true,
                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            OutlinedButton(onClick = vm::sendTestSms, enabled = !vm.smsBusy && vm.testNumber.isNotBlank() && vm.smsText.isNotBlank()) { Text("Test") }
        }
        if (vm.smsBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        vm.smsStatus?.let { Text(it, fontSize = 13.sp) }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("SMS wirklich senden?") },
            text = { Text("An ${recipients.size} Empfänger:\n\n${vm.smsText}") },
            confirmButton = { Button(onClick = { confirm = false; vm.sendSms() }) { Text("Jetzt senden") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
        )
    }
    if (editRecipients) RecipientsDialog(vm) { editRecipients = false }
}

/** Empfängerliste bearbeiten: eintippen/einfügen oder CSV-Export (z. B. Esendex-Gruppe) importieren. */
@Composable
private fun RecipientsDialog(vm: AppViewModel, onClose: () -> Unit) {
    var text by remember { mutableStateOf(vm.recipients.joinToString("\n")) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.importRecipients(uri); onClose() }
    }
    val count = at.askmannersdorf.ergebnis.data.PhoneNumbers.parse(text).size
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Empfänger") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Eine Nummer pro Zeile (z. B. 0664 1234567). Du kannst auch eine Liste einfügen – Namen und Trennzeichen werden ignoriert.", fontSize = 13.sp)
                OutlinedTextField(
                    text, { text = it }, modifier = Modifier.fillMaxWidth().height(220.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                Text("$count gültige Nummern erkannt", fontSize = 13.sp, color = Color.Gray)
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/vnd.ms-excel", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("CSV-Datei importieren (ergänzt die Liste)") }
            }
        },
        confirmButton = { Button(onClick = { vm.saveRecipients(text); onClose() }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

// ── 3. Flyer ────────────────────────────────────────────────────────

@Composable
private fun FlyerSection(vm: AppViewModel, activity: ComponentActivity) {
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.setPhoto(it) }
    }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.setOpponentLogo(it) }
    }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    Section("3 · Flyer für Social Media") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Flyer erstellen", modifier = Modifier.weight(1f))
            Switch(vm.flyerEnabled, { vm.flyerEnabled = it; vm.regenerateFlyer() })
        }
        if (!vm.flyerEnabled) return@Section

        Button(onClick = { photoPicker.launch(imageOnly) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.photo == null) "Mannschaftsfoto wählen" else "Anderes Foto wählen")
        }
        OutlinedButton(onClick = { logoPicker.launch(imageOnly) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.opponentLogo == null) "Gegner-Logo wählen (optional)" else "Gegner-Logo ändern")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.showMinutes, { vm.showMinutes = it; vm.regenerateFlyer() })
            Text("Minuten anzeigen (z. B. „Srok 8., 85.“)")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlyerFormat.entries.forEach { f ->
                FilterChip(
                    selected = vm.format == f,
                    onClick = { vm.format = f; vm.regenerateFlyer() },
                    label = { Text(f.label + if (f == FlyerFormat.STORY) " (Status/Story)" else " (Beitrag)") },
                )
            }
        }

        val bmp = vm.flyer
        if (bmp == null) {
            Text(
                if (!vm.match.isComplete) "Zuerst Teams und Ergebnis eintragen bzw. abrufen."
                else "Flyer wird erstellt …",
                color = Color.Gray, fontSize = 13.sp,
            )
        } else {
            Image(
                bmp.asImageBitmap(), contentDescription = "Flyer-Vorschau",
                modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth,
            )
            if (vm.photo == null) Text("Tipp: Mit Mannschaftsfoto sieht der Flyer viel besser aus.", fontSize = 13.sp, color = Color.Gray)
            OutlinedTextField(
                vm.postText, vm::onPostTextChanged, label = { Text("Beitragstext (Facebook / Instagram)") },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
            )
            if (vm.postEdited) TextButton(onClick = vm::regeneratePost) { Text("Beitragstext neu erzeugen") }
            Button(
                onClick = { vm.shareFlyer(activity, ShareTarget.BUSINESS_SUITE) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AskLime, contentColor = AskGreen),
            ) { Text("Meta Business Suite (Facebook + Instagram)", fontWeight = FontWeight.Bold) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareButton("WhatsApp", Modifier.weight(1f)) { vm.shareFlyer(activity, ShareTarget.WHATSAPP) }
                ShareButton("Instagram", Modifier.weight(1f)) { vm.shareFlyer(activity, ShareTarget.INSTAGRAM) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareButton("Facebook", Modifier.weight(1f)) { vm.shareFlyer(activity, ShareTarget.FACEBOOK) }
                ShareButton("Andere …", Modifier.weight(1f)) { vm.shareFlyer(activity, ShareTarget.OTHER) }
            }
            OutlinedButton(onClick = vm::saveFlyer, modifier = Modifier.fillMaxWidth()) { Text("In Galerie speichern") }
        }
        vm.flyerStatus?.let { Text(it, fontSize = 13.sp) }
    }
}

@Composable
private fun ShareButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = AskLime, contentColor = AskGreen),
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

// ── Einstellungen ───────────────────────────────────────────────────

@Composable
private fun SettingsScreen(initial: AppSettings, onSave: (AppSettings) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Section("Esendex") {
            Field("API-Schlüssel", s.esendexApiKey, password = true) { s = s.copy(esendexApiKey = it) }
            Field("Kontonummer (z. B. EXDE0000000)", s.esendexAccount) { s = s.copy(esendexAccount = it) }
            Field("Absender (optional, muss im Portal freigeschaltet sein)", s.smsSender) { s = s.copy(smsSender = it.take(11)) }
            Field("API-Adresse", s.esendexApiUrl) { s = s.copy(esendexApiUrl = it) }
        }
        Section("Spieldaten") {
            Field("Ligaportal-Spielplan (Hauptquelle)", s.ligaportalUrl) { s = s.copy(ligaportalUrl = it) }
            Field("LMO-Ligaseite (Ergänzung/Ersatz)", s.lmoUrl) { s = s.copy(lmoUrl = it) }
            Field("ÖFB-Widget-Schlüssel", s.oefbWidgetKey) { s = s.copy(oefbWidgetKey = it) }
            Field("Vereins-Homepage (wo das Widget eingebunden ist)", s.oefbEmbedOrigin) { s = s.copy(oefbEmbedOrigin = it) }
            Field("Vereinsname erkennen an", s.clubKeyword) { s = s.copy(clubKeyword = it) }
            Field("Eigener Kurzname in der SMS", s.smsShortName) { s = s.copy(smsShortName = it) }
        }
        Section("Flyer") {
            Field("Vereinsname am Flyer", s.clubDisplayName) { s = s.copy(clubDisplayName = it) }
            Field("Hashtag", s.hashtag) { s = s.copy(hashtag = it) }
        }
        Button(onClick = { onSave(s) }, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Speichern") }
        Text(
            "Zugangsdaten werden nur auf diesem Handy gespeichert.",
            fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun Field(label: String, value: String, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}
