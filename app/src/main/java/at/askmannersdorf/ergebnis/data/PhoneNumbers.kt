package at.askmannersdorf.ergebnis.data

/**
 * Telefonnummern aus freiem Text oder einem CSV-Export (z. B. Esendex-Kontaktgruppe) herauslesen
 * und ins internationale Format bringen (österreichische Nummern: 0664… -> +43664…).
 */
object PhoneNumbers {

    private val candidate = Regex("""(?<![\w@.])(\+|00)?\d[\d \-/()]{5,20}\d(?![\w@])""")

    /** "0664 123 45 67" -> "+436641234567", "0049 171…" -> "+49171…", "43664…" -> "+43664…" */
    fun normalize(raw: String, defaultCountry: String = "43"): String {
        val t = raw.trim()
        val digits = t.filter(Char::isDigit)
        if (digits.length < 7) return ""
        return when {
            t.startsWith("+") -> "+$digits"
            digits.startsWith("00") -> "+" + digits.drop(2)
            digits.startsWith("0") -> "+$defaultCountry" + digits.drop(1)
            digits.length >= 11 -> "+$digits" // bereits mit Ländervorwahl, nur ohne +
            else -> "+$defaultCountry$digits"
        }.takeIf { it.length in 9..16 } ?: ""
    }

    /** Alle Nummern aus einem Text (eine pro Zeile, CSV, mit Namen …), ohne Duplikate. */
    fun parse(text: String): List<String> =
        candidate.findAll(text)
            .map { normalize(it.value) }
            .filter { it.isNotBlank() && !looksLikeDate(it) }
            .distinct()
            .toList()

    // Datumswerte wie 2026-10-22 oder 22.10.2026 aus CSV-Exporten nicht als Nummer werten
    private fun looksLikeDate(n: String) = Regex("""^\+43(19|20)\d{6}$""").matches(n)
}
