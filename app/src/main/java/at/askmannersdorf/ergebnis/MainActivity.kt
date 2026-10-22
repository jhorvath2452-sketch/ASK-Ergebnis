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
    Section("1 · Letztes Spiel") {
        Button(onClick = vm::fetchMatch, enabled = !vm.fetching, modifier = Modifier.fillMaxWidth()) {
            if (vm.fetching) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Wird abgefragt …")
            } else Text("Ergebnis abrufen (ÖFB / Ligaportal)")
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
        if (m.date.isNotBlank() || m.source.isNotBlank()) {
            Text(listOf(m.date, m.source.takeIf { it.isNotBlank() }?.let { "Quelle: $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = Color.Gray)
        }

        HorizontalDivider()
        Text("Torschützen (Haken = Tor für ASK)", fontWeight = FontWeight.SemiBold)
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
    var menuOpen by remember { mutableStateOf(false) }
    val (chars, parts) = SmsCounter.count(vm.smsText)
    val groupName = vm.groups.firstOrNull { it.id == vm.selectedGroupId }?.name

    Section("2 · Ergebnis-SMS") {
        OutlinedTextField(
            vm.smsText, { vm.smsText = it }, label = { Text("SMS-Text") },
            modifier = Modifier.fillMaxWidth(), minLines = 4,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$chars Zeichen · $parts SMS", fontSize = 13.sp, color = if (parts > 1) Color(0xFFB45309) else Color.Gray, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::suggestSmsText, enabled = vm.match.isComplete) { Text("Vorschlag einfügen") }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                OutlinedButton(onClick = { if (vm.groups.isEmpty()) vm.loadGroups() else menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(groupName?.let { "Gruppe: $it" } ?: if (vm.groups.isEmpty()) "Esendex-Gruppen laden" else "Gruppe wählen")
                }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    vm.groups.forEach { g ->
                        DropdownMenuItem(
                            text = { Text(g.name + (g.count?.let { " ($it)" } ?: "")) },
                            onClick = { vm.selectedGroupId = g.id; menuOpen = false },
                        )
                    }
                }
            }
            if (vm.groups.isNotEmpty()) TextButton(onClick = vm::loadGroups) { Text("Neu laden") }
        }

        Button(
            onClick = { confirm = true },
            enabled = !vm.smsBusy && vm.smsText.isNotBlank() && vm.selectedGroupId.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("SMS an Gruppe senden") }

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
            text = { Text("An Gruppe: ${groupName ?: vm.selectedGroupId}\n\n${vm.smsText}") },
            confirmButton = { Button(onClick = { confirm = false; vm.sendSms() }) { Text("Jetzt senden") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
        )
    }
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
            Text("Minuten statt „2×“ anzeigen")
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
            Field("Benutzername / E-Mail", s.esendexUser) { s = s.copy(esendexUser = it) }
            Field("Passwort", s.esendexPassword, password = true) { s = s.copy(esendexPassword = it) }
            Field("Absender (max. 11 Zeichen)", s.smsSender) { s = s.copy(smsSender = it.take(11)) }
            Field("Qualität (N = hoch, L = mittel, LL = niedrig)", s.smsQuality) { s = s.copy(smsQuality = it.uppercase().take(2)) }
            Field("API-Adresse", s.esendexBaseUrl) { s = s.copy(esendexBaseUrl = it) }
        }
        Section("Spieldaten") {
            Field("ÖFB-Widget-Schlüssel", s.oefbWidgetKey) { s = s.copy(oefbWidgetKey = it) }
            Field("Vereins-Homepage (wo das Widget eingebunden ist)", s.oefbEmbedOrigin) { s = s.copy(oefbEmbedOrigin = it) }
            Field("Ligaportal-Teamseite (Ersatzquelle)", s.ligaportalUrl) { s = s.copy(ligaportalUrl = it) }
            Field("Vereinsname erkennen an", s.clubKeyword) { s = s.copy(clubKeyword = it) }
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
