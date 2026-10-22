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

class MatchRepository(context: Context) {
    private val extractor = WebPageExtractor(context)

    suspend fun fetchLatest(s: AppSettings): FetchOutcome {
        val log = mutableListOf<String>()
        val raw = StringBuilder()

        // 1) ÖFB-Widget "Letztes Spiel"
        val oefb = fetchFromOefb(s, log, raw)
        if (oefb != null) return FetchOutcome(oefb, log, raw.toString())

        // 2) Rückfallebene Ligaportal
        if (s.ligaportalUrl.isNotBlank()) {
            val lp = fetchFromLigaportal(s, log, raw)
            if (lp != null) return FetchOutcome(lp, log, raw.toString())
        } else {
            log += "Ligaportal: keine Teamseite in den Einstellungen hinterlegt."
        }
        log += "Kein Ergebnis gefunden – bitte Daten unten von Hand eintragen."
        return FetchOutcome(null, log, raw.toString())
    }

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
            raw.append("── ÖFB (").append(it.url).append(") ──\n").append(it.text.take(4000)).append("\n\n")
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

    private suspend fun fetchFromLigaportal(s: AppSettings, log: MutableList<String>, raw: StringBuilder): Match? {
        // Schritt 1: Teamseite laden und das letzte Spiel suchen
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

        // Schritt 2: Spielbericht öffnen, um die Torschützen zu bekommen
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
