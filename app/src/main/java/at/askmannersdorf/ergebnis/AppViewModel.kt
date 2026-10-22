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
import at.askmannersdorf.ergebnis.data.SmsGroup
import at.askmannersdorf.ergebnis.flyer.FlyerFormat
import at.askmannersdorf.ergebnis.flyer.FlyerInput
import at.askmannersdorf.ergebnis.flyer.FlyerRenderer
import at.askmannersdorf.ergebnis.flyer.FlyerShare
import at.askmannersdorf.ergebnis.flyer.ShareTarget
import at.askmannersdorf.ergebnis.model.Goal
import at.askmannersdorf.ergebnis.model.Match
import at.askmannersdorf.ergebnis.model.Outcome
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
    var smsText by mutableStateOf("")
    var groups by mutableStateOf<List<SmsGroup>>(emptyList()); private set
    var selectedGroupId by mutableStateOf(settings.lastGroupId)
    var smsBusy by mutableStateOf(false); private set
    var smsStatus by mutableStateOf<String?>(null); private set
    var testNumber by mutableStateOf("")

    // Flyer
    var flyerEnabled by mutableStateOf(true)
    var photo by mutableStateOf<Bitmap?>(null); private set
    var opponentLogo by mutableStateOf<Bitmap?>(null); private set
    var showMinutes by mutableStateOf(false)
    var format by mutableStateOf(FlyerFormat.FEED)
    var flyer by mutableStateOf<Bitmap?>(null); private set
    var flyerStatus by mutableStateOf<String?>(null); private set

    fun saveSettings(s: AppSettings) {
        store.save(s); settings = store.load()
        groups = emptyList()
        regenerateFlyer()
    }

    // ── Spiel ──────────────────────────────────────────────────────

    fun fetchMatch() {
        if (fetching) return
        fetching = true
        fetchLog = listOf("Frage ÖFB-Widget ab …")
        viewModelScope.launch {
            try {
                val r = repo.fetchLatest(settings)
                fetchLog = r.log
                rawText = r.rawText
                r.match?.let { m ->
                    match = m
                    opponentLogo = null
                    val oppUrl = if (m.isAskHome(settings.clubKeyword)) m.awayLogoUrl else m.homeLogoUrl
                    oppUrl?.let { url -> opponentLogo = FlyerShare.downloadImage(url) }
                }
            } catch (e: Exception) {
                fetchLog = fetchLog + "Fehler: ${e.message}"
            } finally {
                fetching = false
                regenerateFlyer()
            }
        }
    }

    fun updateMatch(m: Match) { match = m; regenerateFlyer() }

    fun updateGoal(index: Int, g: Goal) =
        updateMatch(match.copy(goals = match.goals.toMutableList().also { it[index] = g }))

    fun addGoal() = updateMatch(match.copy(goals = match.goals + Goal("", "", true)))

    fun removeGoal(index: Int) =
        updateMatch(match.copy(goals = match.goals.toMutableList().also { it.removeAt(index) }))

    // ── SMS ────────────────────────────────────────────────────────

    fun suggestSmsText() {
        val m = match
        val kw = settings.clubKeyword
        if (!m.isComplete) return
        val head = when (m.outcome(kw)) {
            Outcome.WIN -> if (m.isAskHome(kw)) "Heimsieg!" else "Auswärtssieg!"
            Outcome.DRAW -> "Unentschieden."
            Outcome.LOSS -> "Leider verloren."
            null -> "Endstand:"
        }
        val scorers = m.goals.filter { it.forAsk && it.scorer.isNotBlank() }
            .groupBy { it.scorer.trim() }
            .map { (name, list) -> if (list.size > 1) "$name (${list.size})" else name }
        val tore = if (scorers.isNotEmpty()) " Tore: ${scorers.joinToString(", ")}." else ""
        smsText = "$head ${m.homeTeam} - ${m.awayTeam} ${m.homeGoals}:${m.awayGoals}.$tore"
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
        opponentLogo = FlyerShare.loadImage(getApplication(), uri, maxSide = 600)
        regenerateFlyer()
    }

    fun regenerateFlyer() {
        if (!flyerEnabled || !match.isComplete) { flyer = null; return }
        val input = FlyerInput(
            match = match,
            clubKeyword = settings.clubKeyword,
            clubDisplayName = settings.clubDisplayName,
            hashtag = settings.hashtag,
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
            val uri = FlyerShare.toShareUri(activity, bmp, fileName())
            if (!FlyerShare.share(activity, uri, target)) {
                flyerStatus = "${target.label} ist auf diesem Handy nicht installiert."
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
