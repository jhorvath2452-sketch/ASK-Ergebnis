package at.askmannersdorf.ergebnis

import at.askmannersdorf.ergebnis.data.LigaportalSchedule
import at.askmannersdorf.ergebnis.data.LigaportalTicker
import at.askmannersdorf.ergebnis.data.SmsFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests mit echten, gespeicherten Ligaportal-Seiten (Stand 27.10.2026). */
class LigaportalRealPagesTest {
    private fun res(name: String) = javaClass.getResource("/ligaportal/$name")!!.readText()
    private val kw = "Mannersdorf"

    @Test fun scheduleHasAll26Games() {
        val games = LigaportalSchedule.parse(res("spielplan_ask_2026-10-27.html"))
        assertEquals(26, games.size)
        assertEquals((1..26).toList(), games.map { it.round })
        assertTrue(games.all { it.tickerUrl?.contains("/live-ticker/") == true })
        assertTrue(games.all { it.home.contains(kw) || it.away.contains(kw) })
    }

    @Test fun schedulePlayedAndOpenGames() {
        val games = LigaportalSchedule.parse(res("spielplan_ask_2026-10-27.html"))
        val r10 = games.first { it.round == 10 }
        assertEquals("SG Weissenbach/Klausen-L./A.", r10.home)
        assertEquals("ASK Mannersdorf", r10.away)
        assertEquals(listOf(1, 4, 1, 2), listOf(r10.homeGoals, r10.awayGoals, r10.halfHome, r10.halfAway))
        assertEquals("18.10.2026", r10.date)

        val r11 = games.first { it.round == 11 }
        assertEquals("https://ticker.ligaportal.at/live-ticker/1271297/sc-himberg-gegen-ask-mannersdorf", r11.tickerUrl)
        assertEquals("23.10.2026", r11.date)

        val r12 = games.first { it.round == 12 }
        assertFalse("Anstoßzeit ist kein Ergebnis", r12.isPlayed)
        assertEquals("17:00", r12.kickoff)
        assertEquals("ASV Vösendorf", r12.away)

        assertEquals(11, LigaportalSchedule.latestPlayed(games)?.round)
        assertEquals(11, games.count { it.isPlayed })
        assertEquals("R11 · 23.10. · SC Himberg – ASK Mannersdorf 1:1", r11.label())
    }

    @Test fun tickerJsonOfficialGoals() {
        val obj = LigaportalTicker.extractJson(res("ticker_himberg_ask_1271297.html"))
        assertNotNull(obj)
        val r = LigaportalTicker.parse(obj!!, kw)
        val m = r.match
        assertEquals("SC Himberg", m.homeTeam); assertEquals("ASK Mannersdorf", m.awayTeam)
        assertEquals(listOf(1, 1, 0, 0), listOf(m.homeGoals, m.awayGoals, m.halfHome, m.halfAway))
        assertEquals("23.10.2026", m.date)
        assertEquals("Runde 11", m.competition)
        assertEquals(listOf("60'", "77'"), m.goals.map { it.minute })
        assertEquals(listOf("Edin Harcevic", "Luis Schweighofer"), m.goals.map { it.scorer })
        assertEquals(listOf(true, false), m.goals.map { it.forAsk })
        assertEquals("Spieldaten", r.goalSource)
        assertEquals("1-1", r.u23)
        assertTrue(r.finished)
        assertEquals(
            "Himberg - ASK: 1-1 (0-0); 0:1 Harcevic, 1:1  U23: 1-1",
            SmsFormatter.build(m, kw, "ASK", SmsFormatter.shortTeamName(m.homeTeam), r.u23!!),
        )
    }

    @Test fun tickerMessagesAsFallback() {
        val obj = LigaportalTicker.extractJson(res("ticker_himberg_ask_1271297.html"))!!
        obj.remove("summaryEvents")
        val r = LigaportalTicker.parse(obj, kw)
        assertEquals("Ticker-Meldungen", r.goalSource)
        assertEquals(listOf("58'", "76'"), r.match.goals.map { it.minute })
        assertEquals(listOf("Harcevic", ""), r.match.goals.map { it.scorer })
        assertEquals(listOf(true, false), r.match.goals.map { it.forAsk })
    }
}
