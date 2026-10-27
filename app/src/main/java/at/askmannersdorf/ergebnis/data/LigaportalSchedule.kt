package at.askmannersdorf.ergebnis.data

/**
 * Liest den Mannschafts-Spielplan von ticker.ligaportal.at
 * (z. B. https://ticker.ligaportal.at/mannschaft/1323/ask-mannersdorf/spielplan).
 *
 * Aufbau (Stand Okt. 2026): Tabellenzeilen, je Spiel
 *  - Kopfzeile: <strong>Fr., 23.10.2026</strong> … <a …>Runde 11</a>
 *  - Spielzeile <tr class="game-row gameWon|gameLost|gameRemis|gameNotStarted">
 *      <td class="team …"><a href="…/mannschaft/1325/…" title="SC Himberg">…</a></td>
 *      <td class="score …"><strong>1:1</strong><small>(0:0)</small></td>   (bzw. "17:00" vor dem Spiel)
 *      <td class="team …">…ASK Mannersdorf…</td>
 *      … <a href="https://ticker.ligaportal.at/live-ticker/1271297/…">Details</a>
 */
object LigaportalSchedule {

    data class Game(
        val round: Int?,
        val date: String,
        val home: String,
        val away: String,
        val homeId: String?,
        val awayId: String?,
        val homeGoals: Int?,
        val awayGoals: Int?,
        val halfHome: Int?,
        val halfAway: Int?,
        /** Anstoßzeit, wenn noch nicht gespielt */
        val kickoff: String?,
        val tickerUrl: String?,
    ) {
        val isPlayed: Boolean get() = homeGoals != null && awayGoals != null

        /** Anzeige im Auswahlfeld, z. B. "R11 · 23.10. · SC Himberg – ASK Mannersdorf 1:1" */
        fun label(): String {
            val d = Regex("""^(\d{1,2}\.\d{1,2}\.)""").find(date)?.value ?: date
            val res = if (isPlayed) " $homeGoals:$awayGoals" else kickoff?.let { " ($it)" } ?: ""
            return listOfNotNull(round?.let { "R$it" }, d.ifBlank { null }, "$home – $away$res").joinToString(" · ")
        }
    }

    private val row = Regex("""<tr\b([^>]*)>(.*?)</tr>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tag = Regex("""<[^>]+>""")
    private val roundRx = Regex("""Runde\s*(\d{1,2})""")
    private val dateRx = Regex("""\b(\d{1,2}\.\d{1,2}\.\d{4})\b""")
    private val teamTd = Regex("""<td\b[^>]*class="[^"]*\bteam\b[^"]*"[^>]*>(.*?)</td>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val scoreTd = Regex("""<td\b[^>]*class="[^"]*\bscore\b[^"]*"[^>]*>(.*?)</td>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val teamId = Regex("""/mannschaft/(\d+)/""")
    private val titleAttr = Regex("""<a\b[^>]*\btitle="([^"]*)"""")
    private val ticker = Regex("""href="([^"]*/live-ticker/\d+[^"]*)"""")
    private val result = Regex("""^(\d{1,2})\s*:\s*(\d{1,2})(?:\s*\(\s*(\d{1,2})\s*:\s*(\d{1,2})\s*\))?$""")
    private val time = Regex("""^\d{1,2}:\d{2}$""")

    fun parse(html: String): List<Game> {
        val games = mutableListOf<Game>()
        var date = ""
        var round: Int? = null
        for (m in row.findAll(html)) {
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            if (!attrs.contains("game-row")) {
                val text = text(body)
                roundRx.find(text)?.let { round = it.groupValues[1].toInt() }
                dateRx.find(text)?.let { date = it.groupValues[1] }
                continue
            }
            val teams = teamTd.findAll(body).map { it.groupValues[1] }.toList()
            if (teams.size < 2) continue
            fun name(t: String) = titleAttr.find(t)?.groupValues?.get(1)?.let(LigaportalHtml::decode)?.trim()
                ?: text(t)
            val score = scoreTd.find(body)?.groupValues?.get(1)?.let(::text).orEmpty()
            val r = result.find(score)
            // Uhrzeit "17:00" ist kein Ergebnis (Stunden > 12 oder "00"-Minuten werden so nie als Tore gelesen)
            val isTime = r != null && r.groupValues[3].isEmpty() && (time.matches(score) && attrs.contains("gameNotStarted"))
            games += Game(
                round = round, date = date,
                home = name(teams[0]), away = name(teams[1]),
                homeId = teamId.find(teams[0])?.groupValues?.get(1), awayId = teamId.find(teams[1])?.groupValues?.get(1),
                homeGoals = r?.takeUnless { isTime }?.groupValues?.get(1)?.toInt(),
                awayGoals = r?.takeUnless { isTime }?.groupValues?.get(2)?.toInt(),
                halfHome = r?.takeUnless { isTime }?.groupValues?.get(3)?.toIntOrNull(),
                halfAway = r?.takeUnless { isTime }?.groupValues?.get(4)?.toIntOrNull(),
                kickoff = if (isTime || (r == null && time.matches(score))) score else null,
                tickerUrl = ticker.find(body)?.groupValues?.get(1)?.let(LigaportalHtml::decode)?.let(::absolute),
            )
        }
        return games
    }

    /** Relative Links ("/live-ticker/…", "//ticker…") zu vollständigen Adressen machen. */
    fun absolute(u: String, base: String = "https://ticker.ligaportal.at"): String = when {
        u.startsWith("http://") || u.startsWith("https://") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> base.trimEnd('/') + u
        else -> base.trimEnd('/') + "/" + u
    }

    /** Zuletzt gespieltes Spiel (für die Vorauswahl). */
    fun latestPlayed(games: List<Game>): Game? = games.lastOrNull { it.isPlayed }

    private fun text(s: String) = LigaportalHtml.decode(s.replace(tag, " ")).replace(Regex("""\s+"""), " ").trim()
}
