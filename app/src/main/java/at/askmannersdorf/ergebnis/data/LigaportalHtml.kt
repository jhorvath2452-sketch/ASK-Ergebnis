package at.askmannersdorf.ergebnis.data

import at.askmannersdorf.ergebnis.model.Match

/**
 * Liest einen Ligaportal-Mannschaftsspielplan direkt aus dem HTML (ohne Browser, ohne DOM),
 * nach demselben Prinzip wie der LMO-Import des Vereins:
 *
 * - Der Spielplan ist in Abschnitte "Runde N" gegliedert (Link-Text "Runde N").
 * - Ein Abschnitt reicht vom Rundenlink bis zum nächsten Rundenlink.
 * - Darin: genau zwei Mannschaftslinks /mannschaft/{ID}/{slug}; Reihenfolge = Heim, Gast.
 *   Vollständiger Name steht oft im Logo-alt ("Team - Vollständiger Vereinsname").
 * - Nur ein vollständiges Ergebnis mit Halbzeit "5:1 (2:0)" gilt als beendetes Spiel.
 *   Uhrzeit, Datum oder "-:-" werden damit nie als Ergebnis gewertet.
 */
object LigaportalHtml {

    data class TeamRef(val id: String, val name: String, val logoUrl: String?)

    data class RoundGame(
        val round: Int,
        val home: TeamRef,
        val away: TeamRef,
        val homeGoals: Int?,
        val awayGoals: Int?,
        val halfHome: Int?,
        val halfAway: Int?,
        val date: String,
        /** Link zum Spiel/Liveticker (für die Torschützen), falls vorhanden. */
        val matchUrl: String?,
    ) {
        val isFinished: Boolean get() = homeGoals != null && awayGoals != null
    }

    private const val BASE = "https://ticker.ligaportal.at"

    private val roundLink = Regex("""<a\b[^>]*>(?:\s|<[^>]+>)*Runde(?:\s|&nbsp;|<[^>]+>)*(\d{1,2})(?:\s|<[^>]+>)*</a>""", RegexOption.IGNORE_CASE)
    // Ersatz, falls der Rundentext nicht direkt in einem Link steckt
    private val roundLoose = Regex(""">\s*Runde(?:\s|&nbsp;|<[^>]+>)*(\d{1,2})\s*<""", RegexOption.IGNORE_CASE)
    private val teamLink = Regex(
        """<a\b[^>]*href\s*=\s*["']([^"']*/mannschaft/(\d+)/([^"'/?#]+)[^"']*)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val anyLink = Regex("""<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val imgTag = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val altAttr = Regex("""\balt\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val srcAttr = Regex("""\b(?:data-src|src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val result = Regex("""(?<!\d)(\d{1,2})\s*:\s*(\d{1,2})\s*\(\s*(\d{1,2})\s*:\s*(\d{1,2})\s*\)""")
    private val date = Regex("""\b\d{1,2}\.\d{1,2}\.(?:\d{2,4})?""")

    fun parseSchedule(html: String): List<RoundGame> {
        var marks = roundLink.findAll(html).map { it.range.first to it.groupValues[1].toInt() }.toList()
        if (marks.isEmpty()) marks = roundLoose.findAll(html).map { it.range.first to it.groupValues[1].toInt() }.toList()
        if (marks.isEmpty()) return emptyList()

        val games = mutableListOf<RoundGame>()
        marks.forEachIndexed { i, (start, round) ->
            val end = marks.getOrNull(i + 1)?.first ?: html.length
            parseSection(html.substring(start, end), round)?.let { games += it }
        }
        // Falls eine Runde mehrfach vorkommt (z. B. Navigation + Liste): den Abschnitt mit Ergebnis bevorzugen
        return games.groupBy { it.round }.map { (_, list) -> list.firstOrNull { it.isFinished } ?: list.first() }
            .sortedBy { it.round }
    }

    private fun parseSection(section: String, round: Int): RoundGame? {
        // Mannschaften nach ID zusammenführen, Reihenfolge des ersten Auftretens = Heim, Gast
        val teams = LinkedHashMap<String, TeamRef>()
        for (m in teamLink.findAll(section)) {
            val id = m.groupValues[2]
            val inner = m.groupValues[4]
            val img = imgTag.find(inner)?.value
            val alt = img?.let { altAttr.find(it)?.groupValues?.get(1) }?.let(::decode)?.trim()
            val fromAlt = alt?.let { if (it.contains(" - ")) it.substringAfter(" - ").trim() else it }
                ?.takeIf { it.isNotBlank() }
            val text = decode(stripTags(inner)).trim()
            val name = listOfNotNull(fromAlt, text.takeIf { it.isNotBlank() }).maxByOrNull { it.length }
                ?: m.groupValues[3].replace('-', ' ')
            val logo = img?.let { srcAttr.find(it)?.groupValues?.get(1) }?.let(::absolute)
            val prev = teams[id]
            teams[id] = TeamRef(
                id,
                if (prev == null || name.length > prev.name.length) name else prev.name,
                prev?.logoUrl ?: logo,
            )
        }
        if (teams.size != 2) return null
        val (home, away) = teams.values.toList()

        val text = decode(stripTags(section)).replace(Regex("""\s+"""), " ")
        val r = result.find(text)
        val matchUrl = anyLink.findAll(section).map { it.groupValues[1] }
            .map(::absolute)
            .firstOrNull { href ->
                val path = href.substringAfter("ligaportal.at", href).lowercase()
                !path.contains("/mannschaft/") && !path.contains("/spielplan") && !path.contains("/tabelle") &&
                    Regex("""\d{3,}""").containsMatchIn(path)
            }
        return RoundGame(
            round = round, home = home, away = away,
            homeGoals = r?.groupValues?.get(1)?.toInt(), awayGoals = r?.groupValues?.get(2)?.toInt(),
            halfHome = r?.groupValues?.get(3)?.toInt(), halfAway = r?.groupValues?.get(4)?.toInt(),
            date = date.find(text)?.value ?: "",
            matchUrl = matchUrl,
        )
    }

    /** Neueste Runde mit vollständigem Ergebnis, an der der eigene Verein beteiligt ist. */
    fun latestFinished(games: List<RoundGame>, clubKeyword: String): RoundGame? =
        games.filter { it.isFinished && (it.home.name.contains(clubKeyword, true) || it.away.name.contains(clubKeyword, true)) }
            .maxByOrNull { it.round }
            // Mannschaftsspielplan: jede Partie gehört zum Verein, auch wenn der Name gekürzt ist
            ?: games.filter { it.isFinished }.maxByOrNull { it.round }

    fun toMatch(g: RoundGame): Match = Match(
        homeTeam = g.home.name, awayTeam = g.away.name,
        homeGoals = g.homeGoals, awayGoals = g.awayGoals,
        halfHome = g.halfHome, halfAway = g.halfAway,
        date = g.date, competition = "Runde ${g.round}",
        homeLogoUrl = g.home.logoUrl, awayLogoUrl = g.away.logoUrl,
        reportUrl = g.matchUrl, source = "Ligaportal",
    )

    private fun absolute(u: String): String = when {
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> BASE + u
        else -> "$BASE/$u"
    }

    private fun stripTags(s: String) =
        s.replace(Regex("""<(script|style)\b.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<[^>]+>"""), " ")

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "eacute" to "é", "ndash" to "–", "mdash" to "—",
    )

    fun decode(s: String): String = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> named[e] ?: m.value
        }
    }
}
