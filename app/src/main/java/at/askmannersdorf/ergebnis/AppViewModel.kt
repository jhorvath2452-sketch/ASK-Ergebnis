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
import at.askmannersdorf.ergebnis.data.PhoneNumbers
import at.askmannersdorf.ergebnis.flyer.FlyerFormat
import at.askmannersdorf.ergebnis.flyer.FlyerInput
import at.askmannersdorf.ergebnis.flyer.FlyerRenderer
import at.askmannersdorf.ergebnis.flyer.FlyerShare
import at.askmannersdorf.ergebnis.flyer.ShareTarget
import at.askmannersdorf.ergebnis.model.Goal
import at.askmannersdorf.ergebnis.model.Match
import at.askmannersdorf.ergebnis.model.goalTimeline
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
    /** Empfänger-Nummern (aus den Einstellungen, eine pro Zeile) */
    val recipients: List<String> get() = PhoneNumbers.parse(settings.smsRecipients)
    var smsBusy by mutableStateOf(false); private set
    var smsStatus by mutableStateOf<String?>(null); private set
    var testNumber by mutableStateOf("")

    // Flyer
    var flyerEnabled by mutableStateOf(true)
    var photo by mutableStateOf<Bitmap?>(null); private set
    /** Erkannte Gesichter im Foto – damit sie am Flyer nicht vom Text verdeckt werden. */
    private var photoFaces: List<at.askmannersdorf.ergebnis.flyer.PhotoPlacement.Box> = emptyList()
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
        refreshSms()
        regenerateFlyer()
    }

    // ── Spielplan / Auswahl ────────────────────────────────────────

    var games by mutableStateOf<List<at.askmannersdorf.ergebnis.data.LigaportalSchedule.Game>>(emptyList()); private set
    var selectedGame by mutableStateOf<at.askmannersdorf.ergebnis.data.LigaportalSchedule.Game?>(null)
    var scheduleLoading by mutableStateOf(false); private set
    var scheduleStatus by mutableStateOf<String?>(null); private set

    init { loadSchedule() }

    fun loadSchedule() {
        if (scheduleLoading) return
        scheduleLoading = true
        scheduleStatus = "Lade Spielplan …"
        viewModelScope.launch {
            try {
                val r = repo.loadSchedule(settings)
                val keep = selectedGame?.tickerUrl
                games = r.games
                selectedGame = r.games.firstOrNull { it.tickerUrl != null && it.tickerUrl == keep }
                    ?: at.askmannersdorf.ergebnis.data.LigaportalSchedule.latestPlayed(r.games)
                    ?: r.games.firstOrNull()
                scheduleStatus = r.log
            } catch (e: Throwable) {
                scheduleStatus = "Spielplan: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                scheduleLoading = false
            }
        }
    }

    // ── Spiel ──────────────────────────────────────────────────────

    fun fetchMatch() {
        if (fetching) return
        fetching = true
        val game = selectedGame
        fetchLog = listOf(if (game != null) "Frage Liveticker ab: ${game.home} – ${game.away} …" else "Frage Ligaportal / LMO ab …")
        viewModelScope.launch {
            try {
                val r = if (game?.tickerUrl != null) repo.fetchFromTicker(settings, game) else repo.fetchLatest(settings)
                fetchLog = r.log
                rawText = r.rawText
                // U23 aus dem Ticker übernehmen, wenn noch nichts eingetragen ist
                r.u23?.let { if (u23Result.isBlank()) u23Result = it }
                r.match?.let { m ->
                    val oppChanged = m.opponent(settings.clubKeyword) != match.opponent(settings.clubKeyword)
                    match = m
                    goalIndex = -1 // Tor-Flyer: standardmäßig das zuletzt gefallene ASK-Tor
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

    /** Empfängerliste speichern (Text oder CSV-Import); gibt die Anzahl erkannter Nummern zurück. */
    fun saveRecipients(text: String): Int {
        val nums = PhoneNumbers.parse(text)
        store.save(settings.copy(smsRecipients = nums.joinToString("\n")))
        settings = store.load()
        smsStatus = "${nums.size} Empfänger gespeichert."
        return nums.size
    }

    fun importRecipients(uri: Uri) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        }
        if (text == null) { smsStatus = "Datei konnte nicht gelesen werden."; return@launch }
        val merged = (recipients + PhoneNumbers.parse(text)).distinct()
        saveRecipients(merged.joinToString("\n"))
    }

    fun sendSms() {
        val nums = recipients
        if (smsText.isBlank() || nums.isEmpty()) return
        smsBusy = true; smsStatus = "Sende an ${nums.size} Empfänger …"
        viewModelScope.launch {
            try {
                smsStatus = EsendexClient(settings).send(smsText, nums).message
            } catch (e: Throwable) {
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
                smsStatus = EsendexClient(settings).send(smsText, listOf(testNumber), "Ergebnis-SMS Test").message
            } catch (e: Throwable) {
                smsStatus = e.message ?: "Fehler beim Versand"
            } finally {
                smsBusy = false
            }
        }
    }

    // ── Flyer ──────────────────────────────────────────────────────

    fun setPhoto(uri: Uri) = viewModelScope.launch {
        val bmp = FlyerShare.loadImage(getApplication(), uri)
        photo = bmp
        photoFaces = emptyList()
        if (bmp == null) {
            flyerStatus = "Foto konnte nicht geladen werden."
        } else {
            flyerStatus = "Suche Gesichter …"
            photoFaces = withContext(Dispatchers.Default) { at.askmannersdorf.ergebnis.flyer.FaceFinder.find(bmp) }
            flyerStatus = if (photoFaces.isEmpty())
                "Keine Gesichter erkannt – Foto wird mittig platziert."
            else "${photoFaces.size} Gesichter erkannt – sie bleiben frei vom Text."
        }
        regenerateFlyer()
    }

    fun setOpponentLogo(uri: Uri) = viewModelScope.launch {
        opponentLogo = FlyerShare.loadImage(getApplication(), uri, maxSide = 800)?.let { FlyerShare.removeWhiteBackground(it) }
        regenerateFlyer()
    }

    private fun ownShort() = SmsFormatter.shortTeamName(settings.clubDisplayName)
    private fun oppShortLabel() = opponentShort.ifBlank { SmsFormatter.shortTeamName(match.opponent(settings.clubKeyword)) }

    fun regenerateFlyer() {
        regenerateGoalFlyer()
        if (!flyerEnabled || !match.isComplete) { flyer = null; return }
        val input = FlyerInput(
            match = match,
            clubKeyword = settings.clubKeyword,
            clubDisplayName = settings.clubDisplayName,
            hashtag = settings.hashtag,
            homeLabel = if (match.isAskHome(settings.clubKeyword)) ownShort() else oppShortLabel(),
            awayLabel = if (match.isAskHome(settings.clubKeyword)) oppShortLabel() else ownShort(),
            ownShort = ownShort(),
            photoFaces = photoFaces,
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
        shareBitmap(activity, bmp, target, postText, fileName()) { flyerStatus = it }
    }

    /** Ein Bild teilen (WhatsApp/Instagram/Facebook/Business Suite/…), Text vorher in die Zwischenablage. */
    private fun shareBitmap(
        activity: android.app.Activity, bmp: Bitmap, target: ShareTarget, text: String, name: String, status: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val caption = text.takeIf { it.isNotBlank() && target != ShareTarget.WHATSAPP }
            if (caption != null) {
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Beitragstext", caption))
            }
            if (target == ShareTarget.BUSINESS_SUITE) {
                // Business Suite nimmt geteilte Bilder nicht an ("Content-Art wird nicht unterstützt"):
                // Flyer in die Galerie legen und die App öffnen – dort "Beitrag/Story erstellen" + neuestes Foto.
                val saved = FlyerShare.saveToGallery(activity, bmp, name)
                val opened = FlyerShare.launchApp(activity, target.pkg!!)
                status(when {
                    !saved -> "Flyer konnte nicht gespeichert werden."
                    !opened -> "Flyer ist in der Galerie (Bilder/ASK Ergebnis). Business Suite ist nicht installiert."
                    else -> "Flyer ist in der Galerie, Beitragstext kopiert. In der Business Suite „Beitrag“ oder „Story“ erstellen und das neueste Foto wählen."
                })
                return@launch
            }
            val uri = FlyerShare.toShareUri(activity, bmp, name)
            val ok = FlyerShare.share(activity, uri, target, caption)
            status(when {
                !ok -> "${target.label} wurde nicht gefunden – bitte im Teilen-Menü auswählen."
                caption != null -> "Beitragstext ist kopiert – falls er fehlt, einfach einfügen."
                else -> null
            })
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

    // ── Tor-Flyer ──────────────────────────────────────────────────

    /** ASK-Tore mit neuem Spielstand (aus dem Liveticker bzw. der Torliste). */
    val askGoals: List<at.askmannersdorf.ergebnis.model.GoalEvent>
        get() = match.goalTimeline(settings.clubKeyword).filter { it.goal.forAsk }

    var goalIndex by mutableStateOf(-1)
        private set
    var goalPhoto by mutableStateOf<Bitmap?>(null); private set
    private var goalPhotoFaces: List<at.askmannersdorf.ergebnis.flyer.PhotoPlacement.Box> = emptyList()
    var goalFormat by mutableStateOf(FlyerFormat.FEED)
    var goalFlyer by mutableStateOf<Bitmap?>(null); private set
    var goalStatus by mutableStateOf<String?>(null); private set

    /** Gewähltes Tor; ohne Auswahl das zuletzt gefallene ASK-Tor. */
    val selectedGoal: at.askmannersdorf.ergebnis.model.GoalEvent?
        get() = askGoals.let { g -> g.getOrNull(goalIndex) ?: g.lastOrNull() }

    fun selectGoal(i: Int) { goalIndex = i; regenerateGoalFlyer() }

    fun setGoalFormat(f: FlyerFormat) { goalFormat = f; regenerateGoalFlyer() }

    fun setGoalPhoto(uri: Uri) = viewModelScope.launch {
        val bmp = FlyerShare.loadImage(getApplication(), uri)
        goalPhoto = bmp
        goalPhotoFaces = emptyList()
        if (bmp == null) {
            goalStatus = "Foto konnte nicht geladen werden."
        } else {
            goalStatus = "Suche Gesichter …"
            goalPhotoFaces = withContext(Dispatchers.Default) { at.askmannersdorf.ergebnis.flyer.FaceFinder.find(bmp) }
            goalStatus = if (goalPhotoFaces.isEmpty()) "Kein Gesicht erkannt – Foto wird mittig platziert."
            else "${goalPhotoFaces.size} Gesicht(er) erkannt – bleiben frei vom Text."
        }
        regenerateGoalFlyer()
    }

    fun regenerateGoalFlyer() {
        val ev = selectedGoal
        if (ev == null || !match.isComplete) { goalFlyer = null; return }
        val input = at.askmannersdorf.ergebnis.flyer.GoalFlyerInput(
            match = match, clubKeyword = settings.clubKeyword, hashtag = settings.hashtag,
            photo = goalPhoto, photoFaces = goalPhotoFaces, opponentLogo = opponentLogo,
            scorer = ev.goal.scorer, minute = ev.goal.minute, home = ev.home, away = ev.away,
            ownShort = settings.smsShortName.ifBlank { "ASK" },
        )
        val fmt = goalFormat
        viewModelScope.launch {
            goalFlyer = withContext(Dispatchers.Default) { renderer.renderGoal(input, fmt) }
        }
    }

    /** Beitragstext für den Tor-Flyer. */
    val goalPostText: String
        get() {
            val ev = selectedGoal ?: return ""
            val name = SmsFormatter.shortScorer(ev.goal.scorer)
            return "TOOOR! ⚽ ${ev.goal.minute} $name trifft für den ${settings.smsShortName.ifBlank { "ASK" }}!\n" +
                "Neuer Spielstand: ${match.homeTeam} – ${match.awayTeam} ${ev.score}\n\n${settings.hashtag}"
        }

    fun shareGoalFlyer(activity: android.app.Activity, target: ShareTarget) {
        val bmp = goalFlyer ?: return
        val name = "ASK_Tor_${SmsFormatter.shortScorer(selectedGoal?.goal?.scorer.orEmpty()).replace(Regex("[^A-Za-z0-9]+"), "_")}_" +
            "${goalFormat.name.lowercase()}_${System.currentTimeMillis() / 1000}.png"
        shareBitmap(activity, bmp, target, goalPostText, name) { goalStatus = it }
    }

    fun saveGoalFlyer() {
        val bmp = goalFlyer ?: return
        viewModelScope.launch {
            val name = "ASK_Tor_${System.currentTimeMillis() / 1000}.png"
            goalStatus = if (FlyerShare.saveToGallery(getApplication(), bmp, name))
                "In der Galerie gespeichert (Bilder/ASK Ergebnis)." else "Speichern fehlgeschlagen."
        }
    }
}
