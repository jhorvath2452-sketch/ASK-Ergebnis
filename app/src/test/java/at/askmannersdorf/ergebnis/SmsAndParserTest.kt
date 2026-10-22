package at.askmannersdorf.ergebnis

import at.askmannersdorf.ergebnis.data.MatchParser
import at.askmannersdorf.ergebnis.data.PageRow
import at.askmannersdorf.ergebnis.data.SmsFormatter
import at.askmannersdorf.ergebnis.model.Goal
import at.askmannersdorf.ergebnis.model.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsAndParserTest {

    private val kw = "Mannersdorf"

    private val weissenbach = Match(
        homeTeam = "SG Weissenbach/Klausen", awayTeam = "ASK Mannersdorf",
        homeGoals = 1, awayGoals = 4,
        goals = listOf(
            Goal("12'", "Marcel Srok", true),
            Goal("30'", "Sejdini", true),
            Goal("52'", "", false),
            Goal("67'", "Marcel Srok", true),
            Goal("88'", "Özel", true),
        ),
    )

    @Test fun smsMatchesClubPattern() {
        // Beispiel aus der Vorgabe, Halbzeit 1-2 ergibt sich hier aus den Toren
        val m = weissenbach.copy(goals = listOf(
            Goal("12'", "Marcel Srok", true),
            Goal("30'", "Sejdini", true),
            Goal("41'", "", false),
            Goal("67'", "Marcel Srok", true),
            Goal("88'", "Özel", true),
        ))
        val sms = SmsFormatter.build(m, kw, "ASK", SmsFormatter.shortTeamName(m.homeTeam), "4:0")
        assertEquals(
            "Weissenbach - ASK: 1-4 (1-2); 0:1 Srok, 0:2 Sejdini, 1:2, 1:3 Srok, 1:4 Özel  U23: 4-0",
            sms,
        )
    }

    @Test fun explicitHalftimeWinsOverComputed() {
        val sms = SmsFormatter.build(weissenbach.copy(halfHome = 1, halfAway = 2), kw, "ASK", "Weissenbach", "")
        assertEquals("Weissenbach - ASK: 1-4 (1-2); 0:1 Srok, 0:2 Sejdini, 1:2, 1:3 Srok, 1:4 Özel", sms)
    }

    @Test fun homeGameAndUnsortedGoals() {
        val m = Match(
            homeTeam = "ASK Mannersdorf", awayTeam = "SC Himberg", homeGoals = 2, awayGoals = 1,
            goals = listOf(Goal("80'", "Özel", true), Goal("10'", "", false), Goal("45+2'", "Srok", true)),
        )
        assertEquals(
            "ASK - Himberg: 2-1 (1-1); 0:1, 1:1 Srok, 2:1 Özel  U23: 3-3",
            SmsFormatter.build(m, kw, "ASK", "Himberg", "3 - 3"),
        )
    }

    @Test fun noGoalsNoHalftimeNoU23() {
        val m = Match(homeTeam = "ASK Mannersdorf", awayTeam = "SV Gablitz", homeGoals = 0, awayGoals = 0)
        assertEquals("ASK - Gablitz: 0-0 (0-0)", SmsFormatter.build(m, kw, "ASK", "Gablitz", ""))
    }

    @Test fun halftimeUnknownWhenGoalsIncomplete() {
        val m = weissenbach.copy(goals = weissenbach.goals.take(3))
        assertNull(m.halftime(kw))
    }

    @Test fun shortNames() {
        assertEquals("Weissenbach", SmsFormatter.shortTeamName("SG Weissenbach/Klausen"))
        assertEquals("Maria Lanzendorf", SmsFormatter.shortTeamName("SC Maria Lanzendorf"))
        assertEquals("Enzesfeld", SmsFormatter.shortTeamName("BSV Enzesfeld/H."))
        assertEquals("Bad Fischau", SmsFormatter.shortTeamName("ASK Bad Fischau"))
        assertEquals("Srok", SmsFormatter.shortScorer("Marcel Srok"))
        assertEquals("Srok", SmsFormatter.shortScorer("Srok M."))
        assertEquals("Özel", SmsFormatter.shortScorer("Özel"))
    }

    @Test fun lmoRowPlayed() {
        val rows = listOf(
            PageRow(listOf("Datum", "Heim", "Gast", "Ergebnis", ""), emptyList(), emptyList()),
            PageRow(listOf("16.10.2026 19:30", "SC Sommerein", "SC Wolfsthal", "1 : 7", "📋"),
                listOf("https://www.oefb.at/bewerbe/Spiel/Spielbericht/4092116/"), emptyList()),
            PageRow(
                listOf("18.10.2026 15:00", "SG Weissenbach/Klausen", "ASK Mannersdorf", "1 : 4", "📋"),
                listOf("https://www.oefb.at/bewerbe/Spiel/Spielbericht/4091801/"),
                listOf("https://askmannersdorf.at/LMO_2026/assets/img/teams/9.png",
                    "https://askmannersdorf.at/LMO_2026/assets/img/teams/1.png"),
            ),
        )
        val r = MatchParser.parseLmoRows(rows, kw)
        assertTrue(r is MatchParser.LmoRowResult.Played)
        val m = (r as MatchParser.LmoRowResult.Played).match
        assertEquals("SG Weissenbach/Klausen", m.homeTeam)
        assertEquals("ASK Mannersdorf", m.awayTeam)
        assertEquals(1, m.homeGoals); assertEquals(4, m.awayGoals)
        assertEquals("https://www.oefb.at/bewerbe/Spiel/Spielbericht/4091801/", m.reportUrl)
        assertEquals("18.10.2026", m.date)
    }

    @Test fun lmoRowOpenAndMissing() {
        val open = listOf(PageRow(listOf("23.10.2026 19:45", "SC Himberg", "ASK Mannersdorf", "- : -", "📋"), emptyList(), emptyList()))
        assertTrue(MatchParser.parseLmoRows(open, kw) is MatchParser.LmoRowResult.Open)
        val none = listOf(PageRow(listOf("x", "SC A", "SC B", "1 : 0"), emptyList(), emptyList()))
        assertTrue(MatchParser.parseLmoRows(none, kw) === MatchParser.LmoRowResult.NotFound)
    }

    @Test fun lmoMatchdayHeading() {
        assertEquals(10, MatchParser.lmoMatchday("Liga\nErgebnisse Spieltag 10\n← vorheriger Spieltag"))
    }

    private val base = Match(homeTeam = "SG Weissenbach/Klausen", awayTeam = "ASK Mannersdorf", homeGoals = 1, awayGoals = 4)

    @Test fun reportSingleLineGoals() {
        val text = """
            SG Weissenbach/Klausen
            1:4
            (1:2)
            ASK Mannersdorf
            Tore
            12' Marcel Srok 0:1
            30' Arian Sejdini 0:2
            41' Max Muster 1:2
            55' Gelbe Karte Marcel Srok
            67' Marcel Srok 1:3
            70' Wechsel: Huber für Maier
            88' Emre Özel 1:4
        """.trimIndent()
        val m = MatchParser.enrichFromReport(text, base, kw)
        assertEquals(1, m.halfHome); assertEquals(2, m.halfAway)
        assertEquals(5, m.goals.size)
        assertEquals(listOf(true, true, false, true, true), m.goals.map { it.forAsk })
        assertEquals("Marcel Srok", m.goals[0].scorer)
        assertEquals("", m.goals[2].scorer)
        assertEquals(
            "Weissenbach - ASK: 1-4 (1-2); 0:1 Srok, 0:2 Sejdini, 1:2, 1:3 Srok, 1:4 Özel",
            SmsFormatter.build(m, kw, "ASK", "Weissenbach", ""),
        )
    }

    @Test fun reportMultiLineGoals() {
        val text = """
            Halbzeit 1:2
            12'
            0:1
            Marcel Srok
            30'
            0:2
            Arian Sejdini
            41'
            1:2
            Max Muster
            67'
            1:3
            Marcel Srok
            88'
            1:4
            Emre Özel
        """.trimIndent()
        val m = MatchParser.enrichFromReport(text, base, kw)
        assertEquals(1, m.halfHome); assertEquals(2, m.halfAway)
        assertEquals(5, m.goals.size)
        assertEquals("Emre Özel", m.goals[4].scorer)
        assertEquals(listOf("12'", "30'", "41'", "67'", "88'"), m.goals.map { it.minute })
    }

    @Test fun reportWithoutGoalsKeepsMatch() {
        val m = MatchParser.enrichFromReport("Nichts Brauchbares hier", base, kw)
        assertTrue(m.goals.isEmpty())
        assertNull(m.halfHome)
    }
}
