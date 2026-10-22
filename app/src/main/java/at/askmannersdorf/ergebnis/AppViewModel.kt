package at.askmannersdorf.ergebnis

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.askmannersdorf.ergebnis.data.AppSettings
import at.askmannersdorf.ergebnis.data.EsendexClient
import at.askmannersdorf.ergebnis.data.MatchRepository
import at.askmannersdorf.ergebnis.data.SettingsStore
import at.askmannersdorf.ergebnis.data.SmsFormatter
import at.askmannersdorf.ergebnis.data.SmsGroup
import at.askmannersdorf.ergebnis.flyer.FlyerFormat
import at.askmannersdorf.ergebnis.flyer.FlyerInput
import at.askmannersdorf.ergebnis.flyer.FlyerRenderer
import at.askmannersdorf.ergebnis.flyer.FlyerShare
import at.askmannersdorf.ergebnis.flyer.ShareTarget
import at.askmannersdorf.ergebnis.model.Goal
import at.askmannersdorf.ergebnis.model.Match
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val repo = MatchRepository(app)
    private val renderer = FlyerRenderer(app)

    var settings by mutableStateOf(store.load()); private set

    // Spiel
    var match by mutableStateOf(Match()); private set
    var fetching by mutableStateOf(false); private set
    var fetchLog by mutableStateOf<List<String>>(emptyList()); private set
    var rawText by mutableStateOf(""); private set

    // SMS
    var smsText by mutableStateOf(""); private set
    /** true, sobald der Text von Hand geändert wurde – dann nicht mehr automatisch überschreiben. */
    var smsEdited by mutableStateOf(false); private set
    var u23Result by mutableStateOf(""); private set
    var opponentShort by mutableStateOf(""); private set
    var groups by mutableStateOf<List<SmsGroup>>(emptyList()); private set
    var selectedGroupId by mutableStateOf(settings.lastGroupId)
    var smsBusy by mutableStateOf(false); private set
    var smsStatus by mutableStateOf<String?>(null); private set
    var testNumber by mutableStateOf("")

    // Flyer
    var flyerEnabled by mutableStateOf(true)
    var photo by mutableStateOf<Bitmap?>(null); private set
    var opponentLogo by mutableStateOf<Bitmap?>(null); private set
    var showMinutes by mutableStateOf(true)
    var format by mutableStateOf(FlyerFormat.FEED)
    var flyer by mutableStateOf<Bitmap?>(null); private set
    var flyerStatus by mutableStateOf<String?>(null); private set
    /** Text für den Beitrag (Facebook/Instagram), wird beim Teilen mitgegeben und in die Zwischenablage kopiert. */
    var postText by mutableStateOf(""); private set
    var postEdited by mutableStateOf(false); private set

    fun onPostTextChanged(t: String) { postText = t; postEdited = true }
    fun regeneratePost() { postEdited = false; refreshPost() }

    private fun refreshPost() {
        if (postEdited || !match.isComplete) return
        val m = match
        val kw = settings.clubKeyword
        val head = when (m.outcome(kw)) {
            at.askmannersdorf.ergebnis.model.Outcome.WIN -> if (m.isAskHome(kw)) "HEIMSIEG! 💚" else "AUSWÄRTSSIEG! 💚"
            at.askmannersdorf.ergebnis.model.Outcome.DRAW -> "Punkteteilung."
            at.askmannersdorf.ergebnis.model.Outcome.LOSS -> "Leider nichts zu holen – Kopf hoch!"
            null -> "Endstand"
        }
        val scorers = m.sortedGoals.filter { it.forAsk && it.scorer.isNotBlank() }
            .groupBy { SmsFormatter.shortScorer(it.scorer) }
            .map { (n, l) -> if (l.size > 1) "$n (${l.size})" else n }
        postText = buildString {
            append(head).append("\n")
            append("${m.homeTeam} – ${m.awayTeam} ${m.homeGoals}:${m.awayGoals}")
            m.halftime(kw)?.let { (h, a) -> append(" ($h:$a)") }
            if (scorers.isNotEmpty()) append("\n⚽ ").append(scorers.joinToString(", "))
            if (u23Result.isNotBlank()) append("\nU23: ").append(SmsFormatter.normalizeResult(u23Result))
            append("\n\n").append(settings.hashtag)
        }
    }

    fun saveSettings(s: AppSettings) {
        store.save(s); settings = store.load()
        groups = emptyList()
        refreshSms()
        regenerateFlyer()
    }

    // ── Spiel ──────────────────────────────────────────────────────

    fun fetchMatch() {
        if (fetching) return
        fetching = true
        fetchLog = listOf("Frage Ligaportal / LMO ab …")
        viewModelScope.launch {
            try {
                val r = repo.fetchLatest(settings)
                fetchLog = r.log
                rawText = r.rawText
                r.match?.let { m ->
                    val oppChanged = m.opponent(settings.clubKeyword) != match.opponent(settings.clubKeyword)
                    match = m
                    opponentShort = SmsFormatter.shortTeamName(m.opponent(settings.clubKeyword))
                    smsEdited = false
                    postEdited = false
                    refreshSms()
                    // Gegner-Logo kommt vom Upload; bei neuem Gegner altes Logo verwerfen
                    if (oppChanged) opponentLogo = null
                }
            } catch (e: Throwable) {
                // Nie die App abstürzen lassen – Fehler im Protokoll anzeigen
                fetchLog = fetchLog + "Fehler bei der Abfrage: ${e.javaClass.simpleName}: ${e.message ?: e.cause?.message ?: ""}"
            } finally {
                fetching = false
                regenerateFlyer()
            }
        }
    }

    fun updateMatch(m: Match) {
        val oldOpp = match.opponent(settings.clubKeyword)
        match = m
        val newOpp = m.opponent(settings.clubKeyword)
        if (newOpp != oldOpp && (opponentShort.isBlank() || opponentShort == SmsFormatter.shortTeamName(oldOpp))) {
            opponentShort = SmsFormatter.shortTeamName(newOpp)
        }
        refreshSms()
        regenerateFlyer()
    }

    fun updateGoal(index: Int, g: Goal) =
        updateMatch(match.copy(goals = match.goals.toMutableList().also { it[index] = g }))

    fun addGoal() = updateMatch(match.copy(goals = match.goals + Goal("", "", true)))

    fun removeGoal(index: Int) =
        updateMatch(match.copy(goals = match.goals.toMutableList().also { it.removeAt(index) }))

    // ── SMS ────────────────────────────────────────────────────────

    fun onSmsTextChanged(t: String) { smsText = t; smsEdited = true }

    fun onU23Changed(t: String) { u23Result = t; refreshSms() }

    fun onOpponentShortChanged(t: String) { opponentShort = t; refreshSms(); regenerateFlyer() }

    /** Text nach Vereinsmuster neu erzeugen (überschreibt Handänderungen). */
    fun regenerateSms() { smsEdited = false; refreshSms() }

    private fun refreshSms() {
        refreshPost()
        if (smsEdited) return
        smsText = SmsFormatter.build(
            match, settings.clubKeyword, settings.smsShortName,
            opponentShort.ifBlank { SmsFormatter.shortTeamName(match.opponent(settings.clubKeyword)) },
            u23Result,
        )
    }

    fun loadGroups() {
        smsBusy = true; smsStatus = "Lade Gruppen …"
        viewModelScope.launch {
            try {
                val client = EsendexClient(settings)
                groups = client.groups()
                val credits = runCatching { client.credits() }.getOrNull()
                if (groups.none { it.id == selectedGroupId }) selectedGroupId = groups.firstOrNull()?.id ?: ""
                smsStatus = "${groups.size} Gruppen geladen" + (credits?.let { ". Guthaben: $it" } ?: "")
            } catch (e: Exception) {
                smsStatus = e.message ?: "Fehler beim Laden der Gruppen"
            } finally {
                smsBusy = false
            }
        }
    }

    fun sendSms() {
        val group = selectedGroupId
        if (smsText.isBlank() || group.isBlank()) return
        smsBusy = true; smsStatus = "Sende …"
        viewModelScope.launch {
            try {
                val r = EsendexClient(settings).sendToGroups(smsText, listOf(group))
                smsStatus = r.message
                if (r.ok) store.save(settings.copy(lastGroupId = group))
            } catch (e: Exception) {
                smsStatus = e.message ?: "Fehler beim Versand"
            } finally {
                smsBusy = false
            }
        }
    }

    fun sendTestSms() {
        if (smsText.isBlank() || testNumber.isBlank()) return
        smsBusy = true; smsStatus = "Sende Test-SMS …"
        viewModelScope.launch {
            try {
                smsStatus = EsendexClient(settings).sendToNumbers(smsText, listOf(testNumber)).message
            } catch (e: Exception) {
                smsStatus = e.message ?: "Fehler beim Versand"
            } finally {
                smsBusy = false
            }
        }
    }

    // ── Flyer ──────────────────────────────────────────────────────

    fun setPhoto(uri: Uri) = viewModelScope.launch {
        photo = FlyerShare.loadImage(getApplication(), uri)
        if (photo == null) flyerStatus = "Foto konnte nicht geladen werden."
        regenerateFlyer()
    }

    fun setOpponentLogo(uri: Uri) = viewModelScope.launch {
        opponentLogo = FlyerShare.loadImage(getApplication(), uri, maxSide = 800)?.let { FlyerShare.removeWhiteBackground(it) }
        regenerateFlyer()
    }

    private fun ownShort() = SmsFormatter.shortTeamName(settings.clubDisplayName)
    private fun oppShortLabel() = opponentShort.ifBlank { SmsFormatter.shortTeamName(match.opponent(settings.clubKeyword)) }

    fun regenerateFlyer() {
        if (!flyerEnabled || !match.isComplete) { flyer = null; return }
        val input = FlyerInput(
            match = match,
            clubKeyword = settings.clubKeyword,
            clubDisplayName = settings.clubDisplayName,
            hashtag = settings.hashtag,
            homeLabel = if (match.isAskHome(settings.clubKeyword)) ownShort() else oppShortLabel(),
            awayLabel = if (match.isAskHome(settings.clubKeyword)) oppShortLabel() else ownShort(),
            ownShort = ownShort(),
            photo = photo,
            opponentLogo = opponentLogo,
            showMinutes = showMinutes,
        )
        val fmt = format
        viewModelScope.launch {
            flyer = withContext(Dispatchers.Default) { renderer.render(input, fmt) }
        }
    }

    private fun fileName(): String {
        val opp = match.opponent(settings.clubKeyword).replace(Regex("""[^A-Za-z0-9]+"""), "_")
        return "ASK_${opp}_${format.name.lowercase()}_${System.currentTimeMillis() / 1000}.png"
    }

    fun shareFlyer(activity: android.app.Activity, target: ShareTarget) {
        val bmp = flyer ?: return
        viewModelScope.launch {
            val caption = postText.takeIf { it.isNotBlank() && target != ShareTarget.WHATSAPP }
            if (caption != null) {
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Beitragstext", caption))
            }
            if (target == ShareTarget.BUSINESS_SUITE) {
                // Business Suite nimmt geteilte Bilder nicht an ("Content-Art wird nicht unterstützt"):
                // Flyer in die Galerie legen und die App öffnen – dort "Beitrag/Story erstellen" + neuestes Foto.
                val saved = FlyerShare.saveToGallery(activity, bmp, fileName())
                val opened = FlyerShare.launchApp(activity, target.pkg!!)
                flyerStatus = when {
                    !saved -> "Flyer konnte nicht gespeichert werden."
                    !opened -> "Flyer ist in der Galerie (Bilder/ASK Ergebnis). Business Suite ist nicht installiert."
                    else -> "Flyer ist in der Galerie, Beitragstext kopiert. In der Business Suite „Beitrag“ oder „Story“ erstellen und das neueste Foto wählen."
                }
                return@launch
            }
            val uri = FlyerShare.toShareUri(activity, bmp, fileName())
            val ok = FlyerShare.share(activity, uri, target, caption)
            flyerStatus = when {
                !ok -> "${target.label} wurde nicht gefunden – bitte im Teilen-Menü auswählen."
                caption != null -> "Beitragstext ist kopiert – falls er fehlt, einfach einfügen."
                else -> null
            }
        }
    }

    fun saveFlyer() {
        val bmp = flyer ?: return
        viewModelScope.launch {
            flyerStatus = if (FlyerShare.saveToGallery(getApplication(), bmp, fileName()))
                "In der Galerie gespeichert (Bilder/ASK Ergebnis)."
            else "Speichern fehlgeschlagen."
        }
    }
}
