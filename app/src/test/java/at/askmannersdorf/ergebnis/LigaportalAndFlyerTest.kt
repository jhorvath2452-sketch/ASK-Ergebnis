package at.askmannersdorf.ergebnis

import at.askmannersdorf.ergebnis.data.MatchParser
import at.askmannersdorf.ergebnis.data.PageLink
import at.askmannersdorf.ergebnis.data.PageRow
import at.askmannersdorf.ergebnis.data.PageSnapshot
import at.askmannersdorf.ergebnis.data.SmsFormatter
import at.askmannersdorf.ergebnis.flyer.ScorerFormat
import at.askmannersdorf.ergebnis.model.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LigaportalAndFlyerTest {
    private val kw = "Mannersdorf"

    @Test fun flyerMinutesCommaSeparated() {
        val segs = ScorerFormat.segments(
            listOf("Marcel Srok" to "8'", "Sejdini" to "34'", "Marcel Srok" to "85'", "Özel" to "90+2'"), true,
        )
        assertEquals(
            listOf(
                ScorerFormat.ScorerSegment("SROK", "8., 85."),
                ScorerFormat.ScorerSegment("SEJDINI", "34."),
                ScorerFormat.ScorerSegment("ÖZEL", "90+2."),
            ),
            segs,
        )
        assertEquals("2×", ScorerFormat.segments(listOf("Srok" to "8'", "Srok" to "85'"), false)[0].extra)
    }

    private fun snap(rows: List<PageRow> = emptyList(), links: List<PageLink> = emptyList(), text: String = "") =
        PageSnapshot("https://ticker.ligaportal.at/mannschaft/1323/ask-mannersdorf/spielplan", text, emptyList(), links, rows)

    @Test fun spielplanRowsPicksNewestPlayed() {
        val rows = listOf(
            PageRow(listOf("Sa, 04.10.", "ASK Mannersdorf", "2:0", "SC Himberg"), listOf("https://ticker.ligaportal.at/mannschaft/1323/ask-mannersdorf", "https://ticker.ligaportal.at/spiel/111/x"), emptyList()),
            PageRow(listOf("So, 18.10.", "SG Weissenbach/Klausen", "1:4 (1:2)", "ASK Mannersdorf"), listOf("https://ticker.ligaportal.at/spiel/222/weissenbach-mannersdorf"), emptyList()),
            PageRow(listOf("Fr, 23.10.", "SC Himberg", "19:45", "ASK Mannersdorf"), listOf("https://ticker.ligaportal.at/spiel/333/x"), emptyList()),
            PageRow(listOf("Sa, 11.04.", "ASK Mannersdorf", "-:-", "SC Wolfsthal"), emptyList(), emptyList()),
        )
        val m = MatchParser.latestPlayed(snap(rows), kw)
        assertNotNull(m)
        assertEquals("SG Weissenbach/Klausen", m!!.homeTeam)
        assertEquals("ASK Mannersdorf", m.awayTeam)
        assertEquals(1, m.homeGoals); assertEquals(4, m.awayGoals)
        assertEquals(1, m.halfHome); assertEquals(2, m.halfAway)
        assertEquals("https://ticker.ligaportal.at/spiel/222/weissenbach-mannersdorf", m.reportUrl)
    }

    @Test fun spielplanLinksWhenNoTable() {
        val links = listOf(
            PageLink("https://ticker.ligaportal.at/spiel/111/a", "04.10.2026\nASK Mannersdorf\n2 : 0\nSC Himberg"),
            PageLink("https://ticker.ligaportal.at/spiel/222/b", "18.10.2026\nSG Weissenbach/Klausen\n1 : 4\nASK Mannersdorf"),
            PageLink("https://ticker.ligaportal.at/spiel/333/c", "23.10.2026\nSC Himberg\n- : -\nASK Mannersdorf"),
        )
        val m = MatchParser.latestPlayed(snap(links = links), kw)
        assertNotNull(m)
        assertEquals(4, m!!.awayGoals)
        assertEquals("https://ticker.ligaportal.at/spiel/222/b", m.reportUrl)
    }

    @Test fun nothingPlayedReturnsNull() {
        val rows = listOf(PageRow(listOf("23.10.", "SC Himberg", "- : -", "ASK Mannersdorf"), emptyList(), emptyList()))
        assertNull(MatchParser.latestPlayed(snap(rows), kw))
    }

    private val base = Match(homeTeam = "SG Weissenbach/Klausen", awayTeam = "ASK Mannersdorf", homeGoals = 1, awayGoals = 4)

    @Test fun tickerWithTeamNamesNoRunningScore() {
        val text = """
            Liveticker
            90' Spielende
            88' Tor für ASK Mannersdorf durch Emre Özel
            85'
            Tor für ASK Mannersdorf
            Marcel Srok
            70' Wechsel bei SG Weissenbach/Klausen: Huber für Maier
            52' Tor für SG Weissenbach/Klausen durch Max Muster
            45' Halbzeit
            34' Gelbe Karte für Arian Sejdini (ASK Mannersdorf)
            34' ⚽ Tor für ASK Mannersdorf – Arian Sejdini
            8' Tor für ASK Mannersdorf durch Marcel Srok
            1' Spielbeginn
        """.trimIndent()
        val m = MatchParser.enrichFromReport(text, base, kw)
        assertEquals(5, m.goals.size)
        assertEquals(listOf("8'", "34'", "52'", "85'", "88'"), m.goals.map { it.minute })
        assertEquals(listOf("Marcel Srok", "Arian Sejdini", "", "Marcel Srok", "Emre Özel"), m.goals.map { it.scorer })
        assertEquals(
            "Weissenbach - ASK: 1-4 (0-2); 0:1 Srok, 0:2 Sejdini, 1:2, 1:3 Srok, 1:4 Özel",
            SmsFormatter.build(m, kw, "ASK", "Weissenbach", ""),
        )
    }

    @Test fun tickerRunningScoreStyle() {
        val text = """
            SG Weissenbach/Klausen 1:4 ASK Mannersdorf
            8. Minute: 0:1 Marcel Srok
            34. Minute: 0:2 Arian Sejdini
            41. Minute: 1:2 Max Muster
            85. Minute: 1:3 Marcel Srok
            88. Minute: 1:4 Emre Özel
        """.trimIndent()
        val m = MatchParser.enrichFromReport(text, base, kw)
        assertEquals(listOf("Marcel Srok", "Arian Sejdini", "", "Marcel Srok", "Emre Özel"), m.goals.map { it.scorer })
        assertEquals(1 to 2, m.halftime(kw))
    }
}
