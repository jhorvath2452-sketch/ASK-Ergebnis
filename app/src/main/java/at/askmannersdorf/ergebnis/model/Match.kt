package at.askmannersdorf.ergebnis.model

/** Ein Tor. [forAsk] = true, wenn es ein Tor des ASK Mannersdorf ist. */
data class Goal(
    val minute: String,
    val scorer: String,
    val forAsk: Boolean = true,
) {
    /** Minute als Zahl zum Sortieren: "45+2'" -> 45.02, leer -> sehr groß. */
    val sortKey: Double
        get() {
            val m = Regex("""(\d{1,3})(?:\s*\+\s*(\d{1,2}))?""").find(minute) ?: return 999.0
            return m.groupValues[1].toDouble() + (m.groupValues[2].toIntOrNull() ?: 0) / 100.0
        }

    /** Tor in der ersten Halbzeit (inkl. Nachspielzeit "45+x"). */
    val isFirstHalf: Boolean
        get() = sortKey < 46.0
}

data class Match(
    val homeTeam: String = "",
    val awayTeam: String = "",
    val homeGoals: Int? = null,
    val awayGoals: Int? = null,
    val halfHome: Int? = null,
    val halfAway: Int? = null,
    val date: String = "",
    val competition: String = "",
    /** Alle Tore (eigene UND gegnerische), damit der Zwischenstand stimmt. */
    val goals: List<Goal> = emptyList(),
    /** Logo-URLs aus der Quelle (falls gefunden). */
    val homeLogoUrl: String? = null,
    val awayLogoUrl: String? = null,
    /** Link zum ÖFB-Spielbericht (aus LMO). */
    val reportUrl: String? = null,
    /** Woher die Daten stammen, z. B. "LMO", "ÖFB" oder "Ligaportal". */
    val source: String = "",
) {
    fun isAskHome(clubKeyword: String): Boolean =
        homeTeam.contains(clubKeyword, ignoreCase = true)

    fun askGoals(clubKeyword: String): Int? =
        if (isAskHome(clubKeyword)) homeGoals else awayGoals

    fun opponentGoals(clubKeyword: String): Int? =
        if (isAskHome(clubKeyword)) awayGoals else homeGoals

    fun opponent(clubKeyword: String): String =
        if (isAskHome(clubKeyword)) awayTeam else homeTeam

    fun outcome(clubKeyword: String): Outcome? {
        val a = askGoals(clubKeyword) ?: return null
        val o = opponentGoals(clubKeyword) ?: return null
        return when {
            a > o -> Outcome.WIN
            a == o -> Outcome.DRAW
            else -> Outcome.LOSS
        }
    }

    /** Tore chronologisch sortiert (stabil: gleiche/fehlende Minuten behalten ihre Reihenfolge). */
    val sortedGoals: List<Goal>
        get() = goals.withIndex().sortedWith(compareBy({ it.value.sortKey }, { it.index })).map { it.value }

    /**
     * Halbzeitstand: eingetragener Wert, sonst aus den Toren berechnet –
     * aber nur, wenn die Torliste zum Endstand passt (sonst wäre es geraten).
     */
    fun halftime(clubKeyword: String): Pair<Int, Int>? {
        if (halfHome != null && halfAway != null) return halfHome to halfAway
        val hg = homeGoals ?: return null
        val ag = awayGoals ?: return null
        if (goals.size != hg + ag || goals.any { it.minute.isBlank() }) return null
        val askHome = isAskHome(clubKeyword)
        val first = goals.filter { it.isFirstHalf }
        val askFirst = first.count { it.forAsk }
        val oppFirst = first.size - askFirst
        val askTotal = goals.count { it.forAsk }
        if (askTotal != (if (askHome) hg else ag)) return null
        return if (askHome) askFirst to oppFirst else oppFirst to askFirst
    }

    val isComplete: Boolean
        get() = homeTeam.isNotBlank() && awayTeam.isNotBlank() && homeGoals != null && awayGoals != null
}

enum class Outcome { WIN, DRAW, LOSS }

/** Ein Tor mit dem Spielstand (Heim:Gast) direkt danach. */
data class GoalEvent(val goal: Goal, val home: Int, val away: Int) {
    val score: String get() = "$home:$away"
}

/** Alle Tore chronologisch, jeweils mit neuem Spielstand. */
fun Match.goalTimeline(clubKeyword: String): List<GoalEvent> {
    val askHome = isAskHome(clubKeyword)
    var h = 0; var a = 0
    return sortedGoals.map { g ->
        if (g.forAsk == askHome) h++ else a++
        GoalEvent(g, h, a)
    }
}
