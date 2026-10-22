package at.askmannersdorf.ergebnis.flyer

import at.askmannersdorf.ergebnis.data.SmsFormatter

/** Torschützen-Zeile am Flyer: "SROK 8., 85. • SEJDINI 34. • ÖZEL 88." */
object ScorerFormat {

    /** Ein Torschütze: Name + Zusatz (Minuten oder "2×"). */
    data class ScorerSegment(val name: String, val extra: String)

    /** "8'" -> "8.", "45+2'" -> "45+2." (österreichische Schreibweise) */
    fun dotMinute(m: String): String = m.trim().trimEnd('\'', '’', '´', '.', ' ') + "."

    /** Fasst Tore pro Spieler zusammen (Reihenfolge = erstes Tor), Minuten mit Beistrich getrennt. */
    fun segments(goals: List<Pair<String, String>>, showMinutes: Boolean): List<ScorerSegment> {
        val order = LinkedHashMap<String, MutableList<String>>()
        goals.forEach { (scorer, minute) ->
            order.getOrPut(SmsFormatter.shortScorer(scorer)) { mutableListOf() }.add(minute)
        }
        return order.map { (name, minutes) ->
            val extra = when {
                showMinutes -> minutes.filter { it.isNotBlank() }.joinToString(", ") { dotMinute(it) }
                minutes.size > 1 -> "${minutes.size}×"
                else -> ""
            }
            ScorerSegment(name.uppercase(), extra)
        }
    }
}
