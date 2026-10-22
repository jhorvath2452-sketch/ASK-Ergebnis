package at.askmannersdorf.ergebnis

import at.askmannersdorf.ergebnis.data.PhoneNumbers
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumbersTest {
    @Test fun normalizeAustrianAndInternational() {
        assertEquals("+436641234567", PhoneNumbers.normalize("0664 123 45 67"))
        assertEquals("+436641234567", PhoneNumbers.normalize("+43 664 1234567"))
        assertEquals("+436641234567", PhoneNumbers.normalize("0043 664/1234567"))
        assertEquals("+436641234567", PhoneNumbers.normalize("436641234567"))
        assertEquals("+4917112345678", PhoneNumbers.normalize("0049 171 12345678"))
        assertEquals("", PhoneNumbers.normalize("12345"))
    }

    @Test fun parseCsvExport() {
        val csv = """
            Vorname;Nachname;Mobilnummer;Erstellt
            Max;Muster;+436641234567;2026-10-22
            Eva;Beispiel;0676 9876543;2026-10-21
            Hans;Doppelt;+43 664 1234567;2026-10-20
        """.trimIndent()
        assertEquals(listOf("+436641234567", "+436769876543"), PhoneNumbers.parse(csv))
    }

    @Test fun parseOnePerLineWithNames() {
        val text = "Max 0664 1111111\nEva: 0699-22 22 222\n\nungültig 123"
        assertEquals(listOf("+436641111111", "+436992222222"), PhoneNumbers.parse(text))
    }
}
