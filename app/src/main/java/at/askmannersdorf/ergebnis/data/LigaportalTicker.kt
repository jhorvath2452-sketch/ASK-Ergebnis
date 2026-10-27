package at.askmannersdorf.ergebnis.data

import at.askmannersdorf.ergebnis.model.Goal
import at.askmannersdorf.ergebnis.model.Match
import org.json.JSONArray
import org.json.JSONObject

/**
 * Liest einen Ligaportal-Liveticker (z. B. https://ticker.ligaportal.at/live-ticker/1271297/…).
 *
 * Die Seite enthält die Spieldaten als JSON: `var initialData = {…}` mit
 *  - teamHome/teamAway, teamHomeId/teamAwayId, scoreHome/scoreAway, scoreHalftimeHome/-Away,
 *    roundNr, date (UTC), oefbId
 *  - summaryEvents: offizielle Spieldaten (eventType 7 = Tor) mit Spielername, Team und Minute
 *  - events: Ticker-Meldungen, u. a. "Tor, Toor, Tooor für X zum 0:1 - NAME" (eventType 7),
 *    Spielende (5) und freie Meldungen wie "Das Spiel der beiden U23 Mannschaften endet 1:1"
 */
object LigaportalTicker {

    const val EVENT_GOAL = 7
    const val EVENT_END = 5

    data class Result(
        val match: Match,
        /** U23-Ergebnis aus einer Ticker-Meldung, z. B. "1-1" */
        val u23: String?,
        /** Spiel laut Ticker beendet */
        val finished: Boolean,
        /** Woher die Torschützen stammen */
        val goalSource: String,
        /** ÖFB-Spielnummer (für den ÖFB-Spielbericht als Ersatzquelle) */
        val oefbId: String? = null,
        val round: Int? = null,
    )

    /** `var initialData = {…}` aus dem HTML herausschneiden (Klammern zählen, Strings beachten). */
    fun extractJson(html: String): JSONObject? {
        val anchors = listOf(Regex("""initialData\s*=\s*\{"""), Regex("""=\s*\{\s*"date"\s*:"""))
        for (a in anchors) {
            val m = a.find(html) ?: continue
            val start = html.indexOf('{', m.range.first)
            val end = matchBrace(html, start) ?: continue
            val obj = runCatching { JSONObject(html.substring(start, end + 1)) }.getOrNull() ?: continue
            if (obj.has("scoreHome") || obj.has("summaryEvents")) return obj
        }
        return null
    }

    private fun matchBrace(s: String, start: Int): Int? {
        var depth = 0; var inStr = false; var esc = false
        for (i in start until s.length) {
            val ch = s[i]
            if (inStr) {
                when { esc -> esc = false; ch == '\\' -> esc = true; ch == '"' -> inStr = false }
            } else when (ch) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }

    private fun JSONObject.optIntOrNull(k: String): Int? =
        if (!has(k) || isNull(k)) null else opt(k).toString().toIntOrNull()

    private fun JSONObject.str(k: String): String =
        optString(k).takeUnless { it.isBlank() || it == "null" }.orEmpty()

    private val tickerGoal = Regex("""f(?:ü|ue)r\s+(.+?)\s+zum\s+(\d{1,2})\s*:\s*(\d{1,2})(?:\s*[-–]\s*(.+))?""", RegexOption.IGNORE_CASE)
    private val u23Rx = Regex("""U\s?23\D{0,60}?(\d{1,2})\s*:\s*(\d{1,2})""", RegexOption.IGNORE_CASE)

    fun parse(obj: JSONObject, clubKeyword: String): Result {
        val home = obj.str("teamHome")
        val away = obj.str("teamAway")
        val homeId = obj.optIntOrNull("teamHomeId")
        val askHome = home.contains(clubKeyword, true)
        val hg = obj.optIntOrNull("scoreHome")
        val ag = obj.optIntOrNull("scoreAway")
        val events = obj.optJSONArray("events") ?: JSONArray()
        val summary = obj.optJSONArray("summaryEvents") ?: JSONArray()

        fun list(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        fun minuteOf(e: JSONObject): String {
            val m = e.optIntOrNull("minuteDisplay") ?: e.optIntOrNull("minute") ?: return ""
            val extra = (e.optIntOrNull("minute") ?: m) - m
            return if (extra > 0 && m in setOf(45, 90, 120)) "$m+$extra'" else "$m'"
        }
        fun sortKey(e: JSONObject) = (e.optIntOrNull("minute") ?: 0) * 1000 + (e.optIntOrNull("sortOrder") ?: 0) % 1000

        // 1) Offizielle Spieldaten
        val sGoals = list(summary).filter { it.optIntOrNull("eventType") == EVENT_GOAL }.sortedBy(::sortKey).map { e ->
            val forHome = e.optIntOrNull("teamId")?.let { it == homeId } ?: e.str("team").equals(home, true)
            Goal(minuteOf(e), e.str("player1Name"), forAsk = forHome == askHome)
        }
        // 2) Ticker-Meldungen "Tor … für X zum 0:1 - NAME"
        val tGoals = list(events).filter { it.optIntOrNull("eventType") == EVENT_GOAL }.sortedBy(::sortKey).mapNotNull { e ->
            val c = e.str("comment").replace('\n', ' ')
            val m = tickerGoal.find(c)
            val team = m?.groupValues?.get(1) ?: e.str("team")
            val forHome = when {
                team.equals(home, true) || (team.isNotBlank() && home.contains(team, true)) -> true
                team.equals(away, true) || (team.isNotBlank() && away.contains(team, true)) -> false
                else -> return@mapNotNull null
            }
            val name = m?.groupValues?.get(4)?.trim().orEmpty()
                .split(Regex("""\s+""")).joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
            Goal(minuteOf(e), name, forAsk = forHome == askHome)
        }
        val total = (hg ?: 0) + (ag ?: 0)
        fun consistent(g: List<Goal>) = g.size == total &&
            g.count { it.forAsk } == (if (askHome) hg else ag)
        val (goals, src) = when {
            consistent(sGoals) -> sGoals to "Spieldaten"
            consistent(tGoals) -> tGoals to "Ticker-Meldungen"
            sGoals.isNotEmpty() -> sGoals to "Spieldaten (unvollständig)"
            else -> tGoals to "Ticker-Meldungen (unvollständig)"
        }

        val u23 = list(events).map { it.str("comment") }.firstNotNullOfOrNull { c ->
            u23Rx.find(c)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }
        }
        val finished = list(events).any { it.optIntOrNull("eventType") == EVENT_END } ||
            list(summary).any { it.optIntOrNull("eventType") == EVENT_END }

        val match = Match(
            homeTeam = home, awayTeam = away, homeGoals = hg, awayGoals = ag,
            halfHome = obj.optIntOrNull("scoreHalftimeHome"), halfAway = obj.optIntOrNull("scoreHalftimeAway"),
            date = localDate(obj.str("date")),
            competition = obj.optIntOrNull("roundNr")?.let { "Runde $it" }.orEmpty(),
            goals = goals,
            source = "Ligaportal-Liveticker",
        )
        return Result(match, u23, finished, src, obj.str("oefbId").ifBlank { null }, obj.optIntOrNull("roundNr"))
    }

    /** "2026-10-23T17:45:00Z" -> "23.10.2026" (Datum genügt; Zeitzone spielt dafür keine Rolle außer um Mitternacht) */
    private fun localDate(iso: String): String {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(iso) ?: return ""
        return "${m.groupValues[3]}.${m.groupValues[2]}.${m.groupValues[1]}"
    }
}
