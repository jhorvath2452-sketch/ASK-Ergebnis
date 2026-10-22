package at.askmannersdorf.ergebnis.flyer

import at.askmannersdorf.ergebnis.data.SmsFormatter

/** Torschützen-Zeile am Flyer: "Srok (8., 88.) • Sejdini (22.) • Özel (95.)" */
object ScorerFormat {

    /** "8'" -> "8.", "45+2'" -> "45+2." (österreichische Schreibweise) */
    fun dotMinute(m: String): String = m.trim().trimEnd('\'', '’', '´', '.', ' ') + "."

    /**
     * Fasst Tore pro Spieler zusammen (Reihenfolge = erstes Tor).
     * Mit Minuten: "Srok (8., 88.)"; ohne Minuten: "Srok 2×" bzw. "Srok".
     */
    fun labels(goals: List<Pair<String, String>>, showMinutes: Boolean): List<String> {
        val order = LinkedHashMap<String, MutableList<String>>()
        goals.forEach { (scorer, minute) ->
            order.getOrPut(SmsFormatter.shortScorer(scorer)) { mutableListOf() }.add(minute)
        }
        return order.map { (name, minutes) ->
            val mins = minutes.filter { it.isNotBlank() }
            when {
                showMinutes && mins.isNotEmpty() -> "$name (${mins.joinToString(", ") { dotMinute(it) }})"
                minutes.size > 1 -> "$name ${minutes.size}×"
                else -> name
            }
        }
    }
}
