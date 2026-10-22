package at.askmannersdorf.ergebnis.data

import at.askmannersdorf.ergebnis.model.Match

/**
 * Baut die Ergebnis-SMS nach dem Vereinsmuster:
 * "Weissenbach - ASK: 1-4 (1-2); 0:1 Srok, 0:2 Sejdini, 1:2, 1:3 Srok, 1:4 Özel  U23: 4-0"
 *
 * - Ergebnisse mit "-", Zwischenstände mit ":" (immer Heim:Gast)
 * - nur eigene Torschützen werden namentlich genannt, Gegentore nur als Zwischenstand
 * - Halbzeit in Klammern, wenn bekannt; U23 nur, wenn eingetragen
 */
object SmsFormatter {

    private val clubPrefixes = setOf(
        "SC", "SV", "SG", "SK", "FC", "UFC", "USC", "ASK", "ASV", "BSV", "SVG", "USV", "ATSV",
        "SPG", "SPORTUNION", "UNION", "FK", "SU", "ESV", "TSV", "USK", "ATUS", "FCU", "SCU", "KSV",
    )

    /** "SG Weissenbach/Klausen" -> "Weissenbach", "SC Maria Lanzendorf" -> "Maria Lanzendorf". */
    fun shortTeamName(full: String): String {
        val words = full.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val rest = words.filterNot { it.trimEnd('.').uppercase() in clubPrefixes }
        val joined = (rest.ifEmpty { words }).joinToString(" ")
        return joined.substringBefore('/').trim().ifEmpty { full.trim() }
    }

    /** "Marcel Srok" -> "Srok"; "Srok M." -> "Srok"; einzelner Name bleibt. */
    fun shortScorer(full: String): String {
        val words = full.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.size <= 1) return full.trim()
        val last = words.last()
        val lastIsInitial = last.trimEnd('.').length <= 2 && last.endsWith(".")
        return if (lastIsInitial) words.first() else last
    }

    /** "4:0", "4 - 0", "4-0" -> "4-0"; alles andere unverändert. */
    fun normalizeResult(r: String): String {
        val m = Regex("""^\s*(\d{1,2})\s*[:\-–]\s*(\d{1,2})\s*$""").find(r) ?: return r.trim()
        return "${m.groupValues[1]}-${m.groupValues[2]}"
    }

    fun build(
        match: Match,
        clubKeyword: String,
        ownShortName: String,
        opponentShortName: String,
        u23Result: String,
    ): String {
        if (!match.isComplete) return ""
        val askHome = match.isAskHome(clubKeyword)
        val home = if (askHome) ownShortName else opponentShortName
        val away = if (askHome) opponentShortName else ownShortName

        val sb = StringBuilder()
        sb.append("$home - $away: ${match.homeGoals}-${match.awayGoals}")
        match.halftime(clubKeyword)?.let { (h, a) -> sb.append(" ($h-$a)") }

        val goals = match.sortedGoals
        if (goals.isNotEmpty()) {
            var h = 0; var a = 0
            val parts = goals.map { g ->
                val homeScored = if (askHome) g.forAsk else !g.forAsk
                if (homeScored) h++ else a++
                if (g.forAsk && g.scorer.isNotBlank()) "$h:$a ${shortScorer(g.scorer)}" else "$h:$a"
            }
            sb.append("; ").append(parts.joinToString(", "))
        }

        val u23 = normalizeResult(u23Result)
        if (u23.isNotBlank()) sb.append("  U23: ").append(u23)
        return sb.toString()
    }
}
