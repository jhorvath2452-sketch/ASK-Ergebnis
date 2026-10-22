package at.askmannersdorf.ergebnis.data

import android.content.Context
import at.askmannersdorf.ergebnis.model.Match

data class FetchOutcome(
    val match: Match?,
    /** Kurzes Protokoll, was versucht wurde (wird in der App angezeigt). */
    val log: List<String>,
    /** Rohtext der Quellen – hilft beim Nachjustieren, falls etwas nicht erkannt wird. */
    val rawText: String,
)

/**
 * Reihenfolge der Quellen:
 * 1. LMO auf askmannersdorf.at (Endstand, Teams, Logos, Link zum ÖFB-Spielbericht)
 *    + ÖFB-Spielbericht für Halbzeit und Torfolge
 * 2. ÖFB-Widget "Letztes Spiel"
 * 3. Ligaportal
 */
class MatchRepository(context: Context) {
    private val extractor = WebPageExtractor(context)

    suspend fun fetchLatest(s: AppSettings): FetchOutcome {
        val log = mutableListOf<String>()
        val raw = StringBuilder()

        if (s.lmoUrl.isNotBlank()) {
            val lmo = fetchFromLmo(s, log, raw)
            if (lmo != null) {
                var m = lmo
                if (m.reportUrl != null) m = enrichFromOefbReport(m, s, log, raw)
                if (m.goals.isEmpty() && (m.homeGoals ?: 0) + (m.awayGoals ?: 0) > 0) {
                    // Torschützen notfalls aus dem ÖFB-Widget holen, wenn es dasselbe Spiel ist
                    val w = fetchFromOefb(s, log, raw)
                    if (w != null && w.homeGoals == m.homeGoals && w.awayGoals == m.awayGoals && w.goals.isNotEmpty()) {
                        m = m.copy(goals = w.goals)
                        log += "Torschützen aus dem ÖFB-Widget übernommen."
                    } else {
                        log += "Torschützen nicht gefunden – bitte unten eintragen."
                    }
                }
                return FetchOutcome(m, log, raw.toString())
            }
        }

        val oefb = fetchFromOefb(s, log, raw)
        if (oefb != null) return FetchOutcome(oefb, log, raw.toString())

        if (s.ligaportalUrl.isNotBlank()) {
            val lp = fetchFromLigaportal(s, log, raw)
            if (lp != null) return FetchOutcome(lp, log, raw.toString())
        } else {
            log += "Ligaportal: keine Teamseite in den Einstellungen hinterlegt."
        }
        log += "Kein Ergebnis gefunden – bitte Daten unten von Hand eintragen."
        return FetchOutcome(null, log, raw.toString())
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

    private suspend fun enrichFromOefbReport(
        m: Match, s: AppSettings, log: MutableList<String>, raw: StringBuilder,
    ): Match {
        val total = (m.homeGoals ?: 0) + (m.awayGoals ?: 0)
        var best: Match = m
        // Die ÖFB-Seite hat einen Browser-Check (Anubis), den die WebView selbst löst.
        val r = extractor.load(url = m.reportUrl, timeoutMs = 35_000) { snap ->
            if (!snap.url.contains("oefb.at") || snap.text.length < 300) return@load false
            if (snap.text.contains("Anubis") || snap.text.contains("Making sure you're not a bot", true)) return@load false
            val e = MatchParser.enrichFromReport(snap.text, m, s.clubKeyword)
            if (e.goals.size == total && (e.halfHome != null || total == 0)) { best = e; true }
            else { if (e.goals.isNotEmpty() || e.halfHome != null) best = e; false }
        }
        val snap = r.accepted ?: r.all.filter { it.url.contains("oefb.at") }.maxByOrNull { it.text.length }
        snap?.let { raw.append("── ÖFB-Spielbericht (").append(it.url).append(") ──\n").append(it.text.take(5000)).append("\n\n") }

        if (best.goals.isEmpty() && snap != null && total > 0) {
            // Zweiter Versuch mit dem allgemeinen Parser (Torschützen ohne Zwischenstand)
            MatchParser.parse(snap.text, s.clubKeyword)?.takeIf { it.goals.isNotEmpty() }?.let {
                best = best.copy(goals = it.goals)
            }
        }
        log += when {
            snap == null -> "ÖFB-Spielbericht: nicht geladen."
            best.goals.size == total && best.halfHome != null -> "ÖFB-Spielbericht: Halbzeit und Torfolge gefunden."
            best.goals.isNotEmpty() -> "ÖFB-Spielbericht: Tore teilweise erkannt – bitte prüfen."
            else -> "ÖFB-Spielbericht: geladen, aber Tore nicht erkannt."
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

    // ── 3. Ligaportal ──────────────────────────────────────────────

    private suspend fun fetchFromLigaportal(s: AppSettings, log: MutableList<String>, raw: StringBuilder): Match? {
        var parsed: Match? = null
        var teamSnap: PageSnapshot? = null
        val r1 = extractor.load(url = s.ligaportalUrl) { snap ->
            if (!snap.url.contains("ligaportal")) return@load false
            teamSnap = snap
            val m = MatchParser.parse(snap.text, s.clubKeyword)
            if (m != null && m.isComplete) { parsed = m; true } else false
        }
        r1.error?.let { log += "Ligaportal: $it"; return null }
        val snap = teamSnap ?: r1.all.maxByOrNull { it.text.length }
        snap?.let { raw.append("── Ligaportal Teamseite ──\n").append(it.text.take(4000)).append("\n\n") }
        val base = parsed ?: run {
            log += "Ligaportal: Teamseite geladen, aber kein Ergebnis erkannt."
            return null
        }

        val opponentToken = base.opponent(s.clubKeyword).lowercase().split(" ").filter { it.length >= 4 }
        val reportLink = snap?.links?.firstOrNull { l ->
            val h = l.href.lowercase()
            (h.contains("spielbericht") || h.contains("/spiel/") || h.contains("match")) &&
                (opponentToken.any { h.contains(it) || l.text.lowercase().contains(it) })
        } ?: snap?.links?.firstOrNull { it.href.lowercase().contains("spielbericht") }

        var detailed: Match? = null
        if (reportLink != null) {
            val r2 = extractor.load(url = reportLink.href, timeoutMs = 20_000) { rs ->
                val m = MatchParser.parse(rs.text, s.clubKeyword)
                if (m != null && m.isComplete && m.goals.isNotEmpty()) {
                    val (hl, al) = MatchParser.findLogos(rs, m.homeTeam, m.awayTeam)
                    detailed = m.copy(homeLogoUrl = hl, awayLogoUrl = al); true
                } else false
            }
            r2.all.maxByOrNull { it.text.length }?.let {
                raw.append("── Ligaportal Spielbericht ──\n").append(it.text.take(4000)).append("\n\n")
            }
        }
        log += if (detailed != null) "Ligaportal: Ergebnis und Torschützen gefunden."
        else "Ligaportal: Ergebnis gefunden, Torschützen bitte prüfen."
        return (detailed ?: base).copy(source = "Ligaportal")
    }
}
