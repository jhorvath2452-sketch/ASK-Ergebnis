package at.askmannersdorf.ergebnis.data

import android.content.Context
import at.askmannersdorf.ergebnis.model.Match

data class FetchOutcome(
    val match: Match?,
    /** Kurzes Protokoll, was versucht wurde (wird in der App angezeigt). */
    val log: List<String>,
    /** Rohtext der Quellen – hilft beim Nachjustieren, falls etwas nicht erkannt wird. */
    val rawText: String,
    /** U23-Ergebnis aus dem Liveticker, z. B. "1-1" */
    val u23: String? = null,
)

data class ScheduleOutcome(val games: List<LigaportalSchedule.Game>, val log: String)

/**
 * Reihenfolge der Quellen:
 * 1. LMO auf askmannersdorf.at (Endstand, Teams, Logos, Link zum ÖFB-Spielbericht)
 *    + ÖFB-Spielbericht für Halbzeit und Torfolge
 * 2. ÖFB-Widget "Letztes Spiel"
 * 3. Ligaportal
 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

class MatchRepository(context: Context) {
    private val extractor = WebPageExtractor(context)

    suspend fun fetchLatest(s: AppSettings): FetchOutcome {
        val log = mutableListOf<String>()
        val raw = StringBuilder()
        fun needsGoals(m: Match) = m.goals.isEmpty() && (m.homeGoals ?: 0) + (m.awayGoals ?: 0) > 0

        // 1. Ligaportal: Ergebnis + Torschützen
        var m: Match? = if (s.ligaportalUrl.isNotBlank()) fetchFromLigaportal(s, log, raw) else {
            log += "Ligaportal: keine Spielplan-Seite in den Einstellungen hinterlegt."
            null
        }

        // 2. LMO + ÖFB-Spielbericht: Ersatz bzw. Ergänzung (Torschützen, Halbzeit)
        if ((m == null || needsGoals(m) || m.halftime(s.clubKeyword) == null) && s.lmoUrl.isNotBlank()) {
            val lmo = fetchFromLmo(s, log, raw)
            if (lmo != null && (m == null || MatchParser.sameMatch(m, lmo, s.clubKeyword))) {
                val base = m ?: lmo
                val needMore = needsGoals(base) || base.halftime(s.clubKeyword) == null
                val enriched = if (needMore && lmo.reportUrl != null)
                    enrichFromReport(base.copy(reportUrl = lmo.reportUrl), s, log, raw, "ÖFB-Spielbericht") else base
                m = enriched.copy(
                    homeLogoUrl = base.homeLogoUrl ?: lmo.homeLogoUrl,
                    awayLogoUrl = base.awayLogoUrl ?: lmo.awayLogoUrl,
                    date = base.date.ifBlank { lmo.date },
                    source = if (m == null) "LMO" else base.source,
                )
            } else if (lmo != null && m != null) {
                log += "LMO zeigt ein anderes Spiel – Ligaportal-Daten werden verwendet."
            }
        }

        // 3. ÖFB-Widget als letzte Möglichkeit
        if (m == null || needsGoals(m)) {
            val w = fetchFromOefb(s, log, raw)
            if (m == null) m = w
            else if (w != null && MatchParser.sameMatch(m, w, s.clubKeyword) && w.goals.isNotEmpty()) {
                m = m.copy(goals = w.goals)
                log += "Torschützen aus dem ÖFB-Widget übernommen."
            }
        }

        when {
            m == null -> log += "Kein Ergebnis gefunden – bitte Daten unten von Hand eintragen."
            needsGoals(m) -> log += "Torschützen nicht gefunden – bitte unten eintragen."
        }
        return FetchOutcome(m, log, raw.toString())
    }

    // ── 1. LMO ─────────────────────────────────────────────────────

    private fun lmoUrlFor(base: String, nr: Int?): String {
        var u = base.trim()
            .replace(Regex("""([?&])nr=\d+&?"""), "$1")
            .replace(Regex("""([?&])view=[^&]*&?"""), "$1")
            .trimEnd('&', '?')
        u += (if (u.contains('?')) "&" else "?") + "view=ergebnisse"
        if (nr != null) u += "&nr=$nr"
        return u
    }

    private suspend fun loadLmo(url: String): PageSnapshot? {
        val r = extractor.load(url = url, timeoutMs = 20_000) { snap ->
            snap.rows.isNotEmpty() && snap.text.contains("Spieltag", true)
        }
        return r.accepted ?: r.all.maxByOrNull { it.rows.size }
    }

    private suspend fun fetchFromLmo(s: AppSettings, log: MutableList<String>, raw: StringBuilder): Match? {
        val first = loadLmo(lmoUrlFor(s.lmoUrl, null))
        if (first == null) {
            log += "LMO: Seite nicht erreichbar."
            return null
        }
        val current = MatchParser.lmoMatchday(first.text)
        raw.append("── LMO (").append(first.url).append(") ──\n").append(first.text.take(3000)).append("\n\n")

        // Der angezeigte Spieltag ist nicht immer der zuletzt gespielte:
        // erst den nächsten prüfen, dann rückwärts suchen (auch falls spielfrei).
        val order = if (current == null) listOf<Int?>(null)
        else listOf(current + 1, current, current - 1, current - 2, current - 3).filter { it >= 1 }
        for (nr in order) {
            val snap = if (nr == current || nr == null) first else loadLmo(lmoUrlFor(s.lmoUrl, nr)) ?: continue
            when (val r = MatchParser.parseLmoRows(snap.rows, s.clubKeyword)) {
                is MatchParser.LmoRowResult.Played -> {
                    log += "LMO: Spieltag ${nr ?: "?"} – ${r.match.homeTeam} ${r.match.homeGoals}:${r.match.awayGoals} ${r.match.awayTeam}"
                    return r.match
                }
                is MatchParser.LmoRowResult.Open -> Unit // noch nicht gespielt -> weiter zurück
                MatchParser.LmoRowResult.NotFound -> Unit // spielfrei -> weiter zurück
            }
        }
        log += "LMO: kein gespieltes Spiel gefunden."
        return null
    }

    private suspend fun enrichFromReport(
        m: Match, s: AppSettings, log: MutableList<String>, raw: StringBuilder, label: String,
    ): Match {
        val total = (m.homeGoals ?: 0) + (m.awayGoals ?: 0)
        var best: Match = m
        val host = runCatching { java.net.URI(m.reportUrl!!).host.removePrefix("www.") }.getOrNull().orEmpty()
        // ÖFB hat einen Browser-Check (Anubis), den die WebView selbst löst; Ligaportal lädt per JavaScript nach.
        val r = extractor.load(url = m.reportUrl, timeoutMs = 35_000) { snap ->
            if (!snap.url.contains(host) || snap.text.length < 300) return@load false
            if (snap.text.contains("Anubis") || snap.text.contains("Making sure you're not a bot", true)) return@load false
            val e = MatchParser.enrichFromReport(snap.text, m, s.clubKeyword)
            if (e.goals.size == total && e.halftime(s.clubKeyword) != null) { best = e; true }
            else { if (e.goals.isNotEmpty() || e.halfHome != null) best = e; false }
        }
        val snap = r.accepted ?: r.all.filter { it.url.contains(host) }.maxByOrNull { it.text.length }
        snap?.let { raw.append("── $label (").append(it.url).append(") ──\n").append(it.text.take(5000)).append("\n\n") }

        if (best.goals.isEmpty() && snap != null && total > 0) {
            // Zweiter Versuch mit dem allgemeinen Parser (Torschützen ohne Zwischenstand)
            MatchParser.parse(snap.text, s.clubKeyword)
                ?.takeIf { it.goals.isNotEmpty() && MatchParser.sameMatch(it, m, s.clubKeyword) }?.let {
                best = best.copy(goals = it.goals)
            }
        }
        log += when {
            snap == null -> "$label: nicht geladen."
            best.goals.size == total && best.halftime(s.clubKeyword) != null -> "$label: Halbzeit und Torfolge gefunden."
            best.goals.isNotEmpty() -> "$label: Tore teilweise erkannt – bitte prüfen."
            else -> "$label: geladen, aber Tore nicht erkannt."
        }
        return best
    }

    // ── 2. ÖFB-Widget ──────────────────────────────────────────────

    private suspend fun fetchFromOefb(s: AppSettings, log: MutableList<String>, raw: StringBuilder): Match? {
        val key = s.oefbWidgetKey
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body>
            <script id='fv-$key'>
            (function(o,e,f,b,_,a,t){o[e]=o[e]||function(){(o[e].q=o[e].q||[])
            .push(arguments)};a=f.createElement(b);t=f.getElementsByTagName(b)
            [0];a.async=1;a.src=_;t.parentNode.insertBefore(a,t)})(window,'fv'
            ,document,'script','https://vereine.oefb.at/widgets.js');
            fv('$key');
            </script>
            </body></html>
        """.trimIndent()

        var parsed: Match? = null
        val result = extractor.load(html = html, baseUrl = s.oefbEmbedOrigin.trimEnd('/') + "/") { snap ->
            if (!snap.url.contains("oefb.at")) return@load false
            val m = MatchParser.parse(snap.text, s.clubKeyword)
            if (m != null && m.isComplete) {
                val (hl, al) = MatchParser.findLogos(snap, m.homeTeam, m.awayTeam)
                parsed = m.copy(homeLogoUrl = hl, awayLogoUrl = al, source = "ÖFB")
                true
            } else false
        }
        result.error?.let { log += "ÖFB: $it"; return null }
        result.all.filter { it.url.contains("oefb.at") }.forEach {
            raw.append("── ÖFB-Widget (").append(it.url).append(") ──\n").append(it.text.take(4000)).append("\n\n")
        }
        return if (parsed != null) {
            log += "ÖFB-Widget: Ergebnis gefunden."
            parsed
        } else {
            val widgetText = result.all.firstOrNull { it.url.contains("oefb.at") }?.text.orEmpty()
            log += when {
                widgetText.isBlank() -> "ÖFB-Widget: keine Antwort (evtl. Domain in den Einstellungen prüfen)."
                else -> "ÖFB-Widget: geladen, aber Ergebnis nicht erkannt."
            }
            null
        }
    }

    // ── Ligaportal ─────────────────────────────────────────────────

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class HttpPage(val code: Int?, val body: String?, val finalUrl: String?, val error: String?) {
        fun describe(): String = when {
            error != null -> "Verbindungsfehler: $error"
            body == null -> "HTTP $code ohne Inhalt"
            else -> "HTTP $code · ${body.length / 1024} KB" + (finalUrl?.let { " · $it" } ?: "")
        }
    }

    /** Seite abrufen und dabei festhalten, was passiert ist (für das Protokoll). */
    private suspend fun fetchPage(url: String): HttpPage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val req = okhttp3.Request.Builder().url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "de-AT,de;q=0.9")
                .build()
            http.newCall(req).execute().use { r ->
                val redirected = r.request.url.toString().takeIf { it.trimEnd('/') != url.trimEnd('/') }
                HttpPage(r.code, r.body?.string(), redirected?.let { "weitergeleitet auf $it" }, null)
            }
        } catch (e: Throwable) {
            HttpPage(null, null, null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** HTML wie ein normaler Browser abrufen (wie der LMO-Import: User-Agent + Accept-Header). */
    private suspend fun fetchHtml(url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val req = okhttp3.Request.Builder().url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "de-AT,de;q=0.9")
                .build()
            http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        } catch (e: Exception) {
            null
        }
    }

    // ── Spielplan + Liveticker (Auswahl eines Spiels) ──────────────

    /** Alle Spiele aus dem Ligaportal-Mannschaftsspielplan. */
    suspend fun loadSchedule(s: AppSettings): ScheduleOutcome {
        if (s.ligaportalUrl.isBlank()) return ScheduleOutcome(emptyList(), "Kein Ligaportal-Spielplan in den Einstellungen.")
        fun parse(h: String) = runCatching { LigaportalSchedule.parse(h) }.getOrDefault(emptyList())
        var games = fetchHtml(s.ligaportalUrl)?.let { h ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { parse(h) }
        }.orEmpty()
        var via = "direkt"
        if (games.isEmpty()) {
            val rendered = extractor.loadRenderedHtml(s.ligaportalUrl, 30_000) { h -> h.contains("game-row") && h.contains("live-ticker") }
            games = rendered?.let { h -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { parse(h) } }.orEmpty()
            via = "über Browser-Ansicht"
        }
        return ScheduleOutcome(
            games,
            if (games.isEmpty()) "Spielplan konnte nicht gelesen werden." else "${games.size} Spiele geladen ($via).",
        )
    }

    /** Ergebnis, Halbzeit, Torschützen und U23 eines Spiels aus seinem Liveticker. */
    suspend fun fetchFromTicker(s: AppSettings, game: LigaportalSchedule.Game): FetchOutcome {
        val log = mutableListOf<String>()
        val url = game.tickerUrl ?: return FetchOutcome(null, listOf("Für dieses Spiel gibt es keinen Liveticker-Link."), "")
        val diagRaw = StringBuilder()
        suspend fun extract(h: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { LigaportalTicker.extractWithDiag(h) }
                .getOrElse { LigaportalTicker.Extract(null, "Auswertung fehlgeschlagen: ${it.javaClass.simpleName}: ${it.message}") }
        }
        fun snippet(label: String, h: String) {
            diagRaw.append("── $label: Seitenanfang ──\n").append(h.take(800)).append("\n")
            val i = h.indexOf("initialData")
            if (i >= 0) diagRaw.append("── um 'initialData' ──\n").append(h.substring(i, minOf(h.length, i + 600))).append("\n")
            diagRaw.append("\n")
        }

        // 1) direkt abrufen
        val page = fetchPage(url)
        var obj: org.json.JSONObject? = null
        if (page.body != null) {
            val ex = extract(page.body)
            obj = ex.obj
            log += "Liveticker direkt: ${page.describe()} – ${ex.diag}"
            if (obj == null) snippet("Direkter Abruf", page.body)
        } else {
            log += "Liveticker direkt: ${page.describe()}"
        }
        // 2) Browser-Ansicht
        if (obj == null) {
            val rendered = extractor.loadRenderedWithDiag(url, 35_000) { h -> h.contains("initialData") }
            if (rendered.html != null) {
                val ex = extract(rendered.html)
                obj = ex.obj
                log += "Liveticker Browser-Ansicht: ${rendered.diag} – ${ex.diag}"
                if (obj == null) snippet("Browser-Ansicht", rendered.html)
            } else {
                log += "Liveticker Browser-Ansicht: ${rendered.diag}"
            }
        }
        if (obj == null) {
            log += "Liveticker konnte nicht ausgelesen werden (Details unter „Rohdaten“)."
            // Ersatz: Spielplan-Daten (Ergebnis + Halbzeit, ohne Torschützen)
            val m = if (game.isPlayed) Match(
                homeTeam = game.home, awayTeam = game.away, homeGoals = game.homeGoals, awayGoals = game.awayGoals,
                halfHome = game.halfHome, halfAway = game.halfAway, date = game.date,
                competition = game.round?.let { "Runde $it" }.orEmpty(), source = "Ligaportal-Spielplan",
            ) else null
            if (m == null) return FetchOutcome(null, log, diagRaw.toString())
            log += "Ergebnis aus dem Spielplan übernommen."
            val raw = StringBuilder(diagRaw)
            val withGoals = scorersFallback(s, m, game.round, null, log, raw)
            return FetchOutcome(withGoals, log, raw.toString())
        }
        val r = LigaportalTicker.parse(obj, s.clubKeyword)
        val m = r.match
        val raw = StringBuilder().apply {
            append("── Liveticker ($url) ──\n")
            append("${m.homeTeam} ${m.homeGoals}:${m.awayGoals} (${m.halfHome}:${m.halfAway}) ${m.awayTeam}\n")
            m.goals.forEach { append("${it.minute} ${it.scorer.ifBlank { "(Gegner)" }} ${if (it.forAsk) "ASK" else "Gegner"}\n") }
            r.u23?.let { append("U23: $it\n") }
        }
        when {
            m.homeGoals == null -> log += "Liveticker: Spiel hat noch kein Ergebnis."
            !r.finished -> log += "Liveticker: ${m.homeTeam} ${m.homeGoals}:${m.awayGoals} ${m.awayTeam} – Spiel läuft noch bzw. nicht als beendet markiert."
            else -> log += "Liveticker: ${m.homeTeam} ${m.homeGoals}:${m.awayGoals} (${m.halfHome}:${m.halfAway}) ${m.awayTeam}"
        }
        val total = (m.homeGoals ?: 0) + (m.awayGoals ?: 0)
        val complete = m.goals.size == total && m.goals.count { it.forAsk } == m.askGoals(s.clubKeyword)
        var result = m
        if (complete) {
            if (total > 0) log += "Torschützen aus ${r.goalSource}."
        } else if (m.homeGoals != null) {
            log += if (m.goals.isEmpty()) "Keine Torschützen im Liveticker – suche im ÖFB-Spielbericht."
            else "Torschützen im Liveticker unvollständig – suche im ÖFB-Spielbericht."
            val extra = StringBuilder()
            val fb = scorersFallback(s, m.copy(goals = emptyList()), game.round ?: r.round, r.oefbId, log, extra)
            result = if (fb.goals.size > m.goals.size) fb else m
            raw.append(extra)
        }
        r.u23?.let { log += "U23-Ergebnis gefunden: $it" }
        return FetchOutcome(result, log, raw.toString(), r.u23)
    }

    /**
     * Torschützen für ein bekanntes Spiel (Teams + Endstand) aus den bisherigen Quellen ergänzen:
     * 1. ÖFB-Spielbericht (Spielnummer aus dem Ticker, sonst Link von der LMO-Seite derselben Runde)
     * 2. ÖFB-Widget "Letztes Spiel" (nur wenn es dasselbe Spiel ist)
     */
    private suspend fun scorersFallback(
        s: AppSettings, base: Match, round: Int?, oefbId: String?, log: MutableList<String>, raw: StringBuilder,
    ): Match {
        fun needs(m: Match) = m.goals.isEmpty() && (m.homeGoals ?: 0) + (m.awayGoals ?: 0) > 0
        if (!needs(base)) return base
        var report = oefbId?.takeIf { it.isNotBlank() }?.let { "https://www.oefb.at/bewerbe/Spiel/Spielbericht/$it/" }
        if (report == null && round != null && s.lmoUrl.isNotBlank()) {
            val snap = loadLmo(lmoUrlFor(s.lmoUrl, round))
            val r = snap?.let { MatchParser.parseLmoRows(it.rows, s.clubKeyword) }
            when {
                snap == null -> log += "LMO Spieltag $round: Seite nicht geladen."
                r is MatchParser.LmoRowResult.Played && MatchParser.sameMatch(r.match, base, s.clubKeyword) -> {
                    report = r.match.reportUrl
                    if (report == null) log += "LMO Spieltag $round: Spiel gefunden, aber ohne Spielbericht-Link."
                }
                r is MatchParser.LmoRowResult.Played -> log += "LMO Spieltag $round: anderes Ergebnis (${r.match.homeGoals}:${r.match.awayGoals}) als im Spielplan."
                r is MatchParser.LmoRowResult.Open -> log += "LMO Spieltag $round: Spiel dort noch ohne Ergebnis."
                else -> log += "LMO Spieltag $round: Spiel nicht gefunden (${snap?.rows?.size ?: 0} Tabellenzeilen gelesen)."
            }
        }
        var m = base
        if (report != null) m = enrichFromReport(base.copy(reportUrl = report), s, log, raw, "ÖFB-Spielbericht")
        if (needs(m)) {
            val w = fetchFromOefb(s, log, raw)
            if (w != null && MatchParser.sameMatch(w, m, s.clubKeyword) && w.goals.isNotEmpty()) {
                m = m.copy(goals = w.goals)
                log += "Torschützen aus dem ÖFB-Widget übernommen."
            }
        }
        if (needs(m)) log += "Torschützen nicht gefunden – bitte unten eintragen."
        return m
    }

    private suspend fun fetchFromLigaportal(s: AppSettings, log: MutableList<String>, raw: StringBuilder): Match? {
        // Weg 1: Spielplan-HTML direkt lesen ("Runde N"-Abschnitte, Ergebnis "1:4 (1:2)")
        val html = fetchHtml(s.ligaportalUrl)
        if (html != null) {
            val games = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { LigaportalHtml.parseSchedule(html) }.getOrDefault(emptyList())
            }
            val g = LigaportalHtml.latestFinished(games, s.clubKeyword)
            raw.append("── Ligaportal Spielplan (HTML, ").append(games.size).append(" Runden erkannt) ──\n")
            games.takeLast(6).forEach {
                raw.append("Runde ${it.round}: ${it.home.name} – ${it.away.name} ")
                    .append(if (it.isFinished) "${it.homeGoals}:${it.awayGoals} (${it.halfHome}:${it.halfAway})" else "offen")
                    .append(it.matchUrl?.let { u -> "  [$u]" } ?: "").append("\n")
            }
            raw.append("\n")
            if (g != null) {
                val base = LigaportalHtml.toMatch(g)
                log += "Ligaportal: Runde ${g.round} – ${base.homeTeam} ${base.homeGoals}:${base.awayGoals} (${base.halfHome}:${base.halfAway}) ${base.awayTeam}"
                val total = (base.homeGoals ?: 0) + (base.awayGoals ?: 0)
                if (total == 0) return base
                if (base.reportUrl == null) {
                    log += "Ligaportal: kein Link zum Spiel im Spielplan – Torschützen über LMO/ÖFB."
                    return base
                }
                return enrichFromReport(base, s, log, raw, "Ligaportal-Ticker")
            }
            log += if (games.isEmpty()) "Ligaportal: HTML geladen, aber keine Runden erkannt – versuche Browser-Ansicht."
            else "Ligaportal: noch kein beendetes Spiel im Spielplan."
            if (games.isNotEmpty()) return null
        } else {
            log += "Ligaportal: direkter Abruf fehlgeschlagen – versuche Browser-Ansicht."
        }
        return fetchFromLigaportalRendered(s, log, raw)
    }

    /** Weg 2: Seite in der unsichtbaren WebView laden (falls der direkte Abruf nicht klappt). */
    private suspend fun fetchFromLigaportalRendered(s: AppSettings, log: MutableList<String>, raw: StringBuilder): Match? {
        // Spielplan lädt per JavaScript nach -> warten, bis ein gespieltes Spiel erkennbar ist
        var found: Match? = null
        val r = extractor.load(url = s.ligaportalUrl, timeoutMs = 30_000) { snap ->
            if (!snap.url.contains("ligaportal")) return@load false
            val m = MatchParser.latestPlayed(snap, s.clubKeyword)
            if (m != null) { found = m; true } else false
        }
        r.error?.let { log += "Ligaportal: $it"; return null }
        val snap = r.accepted ?: r.all.filter { it.url.contains("ligaportal") }.maxByOrNull { it.text.length }
        snap?.let { raw.append("── Ligaportal Spielplan (").append(it.url).append(") ──\n").append(it.text.take(4000)).append("\n\n") }
        val base = found ?: snap?.let { MatchParser.latestPlayed(it, s.clubKeyword) }
        if (base == null) {
            log += if (snap == null) "Ligaportal: Seite nicht erreichbar." else "Ligaportal: Spielplan geladen, aber kein Ergebnis erkannt."
            return null
        }
        log += "Ligaportal: ${base.date.ifBlank { "letztes Spiel" }} – ${base.homeTeam} ${base.homeGoals}:${base.awayGoals} ${base.awayTeam}"
        if (base.reportUrl == null) {
            log += "Ligaportal: kein Link zum Spiel gefunden."
            return base
        }
        val total = (base.homeGoals ?: 0) + (base.awayGoals ?: 0)
        if (total == 0) return base
        return enrichFromReport(base, s, log, raw, "Ligaportal-Ticker")
    }
}
