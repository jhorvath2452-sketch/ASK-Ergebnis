package at.askmannersdorf.ergebnis.model

/** Ein Tor. [forAsk] = true, wenn es ein Tor des ASK Mannersdorf ist. */
data class Goal(
    val minute: String,
    val scorer: String,
    val forAsk: Boolean = true,
)

data class Match(
    val homeTeam: String = "",
    val awayTeam: String = "",
    val homeGoals: Int? = null,
    val awayGoals: Int? = null,
    val date: String = "",
    val competition: String = "",
    val goals: List<Goal> = emptyList(),
    /** Logo-URLs aus der Quelle (falls gefunden). */
    val homeLogoUrl: String? = null,
    val awayLogoUrl: String? = null,
    /** Woher die Daten stammen, z. B. "ÖFB" oder "Ligaportal". */
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

    val isComplete: Boolean
        get() = homeTeam.isNotBlank() && awayTeam.isNotBlank() && homeGoals != null && awayGoals != null
}

enum class Outcome { WIN, DRAW, LOSS }
