package at.askmannersdorf.ergebnis

import at.askmannersdorf.ergebnis.data.LigaportalHtml
import at.askmannersdorf.ergebnis.data.SmsFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nachgebauter Mannschaftsspielplan (Aufbau laut Beschreibung des LMO-Imports). */
class LigaportalHtmlTest {

    private fun team(id: Int, slug: String, short: String, full: String) = """
        <div class="team">
          <a href="/mannschaft/$id/$slug"><img src="/images/logos/$id.png" alt="Team - $full"></a>
          <a href="/mannschaft/$id/$slug"><span>$short</span></a>
        </div>
    """

    private fun round(n: Int, date: String, home: String, away: String, score: String, link: String? = null) = """
        <div class="round"><a href="/spielplan/93/gebietsliga-sued-suedost/runde/$n">Runde <b>$n</b></a>
          <span class="date">$date</span>
          $home
          <div class="score">${if (link != null) "<a href=\"$link\">$score</a>" else score}</div>
          $away
        </div>
    """

    private val ask = team(1323, "ask-mannersdorf", "Mannersdorf", "ASK Mannersdorf")
    private val html = """
        <html><body>
        <nav><a href="/x?r=1">Runde 1</a><a href="/x?r=2">Runde 2</a></nav>
        ${round(8, "Sa, 04.10.2026 16:00", team(2001, "sc-fortuna-wiener-neustadt-ii", "Fortuna Wr. N. II", "SC Fortuna Wiener Neustadt II"), ask, "2:0 (1:0)")}
        ${round(9, "Sa, 11.10.2026 16:00", ask, team(2002, "sc-himberg", "Himberg", "SC Himberg"), "3:3 (1:2)")}
        ${round(10, "So, 18.10.2026 15:00", team(2003, "sg-weissenbach-klausen", "Weissenbach/K.", "SG Weissenbach/Klausen-Leopoldsdorf"), ask, "1:4 (1:2)", "/livespiel/987654/weissenbach-mannersdorf")}
        ${round(11, "Fr, 23.10.2026 19:45", team(2002, "sc-himberg", "Himberg", "SC Himberg"), ask, "19:45")}
        ${round(12, "Sa, 31.10.2026 14:00", ask, team(2004, "sc-wolfsthal", "Wolfsthal", "SC Wolfsthal"), "-:-")}
        </body></html>
    """

    @Test fun roundsAreSeparatedAndOnlyFullResultsCount() {
        val games = LigaportalHtml.parseSchedule(html)
        assertEquals(listOf(8, 9, 10, 11, 12), games.map { it.round })
        assertTrue(games.first { it.round == 10 }.isFinished)
        assertFalse("Uhrzeit ist kein Ergebnis", games.first { it.round == 11 }.isFinished)
        assertFalse(games.first { it.round == 12 }.isFinished)
    }

    @Test fun round8RegressionFromLmoImport() {
        val r8 = LigaportalHtml.parseSchedule(html).first { it.round == 8 }
        assertEquals("SC Fortuna Wiener Neustadt II", r8.home.name)  // vollständiger Name aus alt
        assertEquals("ASK Mannersdorf", r8.away.name)
        assertEquals(2, r8.homeGoals); assertEquals(0, r8.awayGoals)
        assertEquals(1, r8.halfHome); assertEquals(0, r8.halfAway)
        assertEquals("2001", r8.home.id)
    }

    @Test fun latestFinishedIsRound10WithLink() {
        val g = LigaportalHtml.latestFinished(LigaportalHtml.parseSchedule(html), "Mannersdorf")
        assertNotNull(g)
        assertEquals(10, g!!.round)
        assertEquals("SG Weissenbach/Klausen-Leopoldsdorf", g.home.name)
        assertEquals("https://ticker.ligaportal.at/livespiel/987654/weissenbach-mannersdorf", g.matchUrl)
        assertEquals("https://ticker.ligaportal.at/images/logos/2003.png", g.home.logoUrl)
        val m = LigaportalHtml.toMatch(g)
        assertEquals(
            "Weissenbach - ASK: 1-4 (1-2)",
            SmsFormatter.build(m, "Mannersdorf", "ASK", SmsFormatter.shortTeamName(m.homeTeam), ""),
        )
    }

    @Test fun sectionWithoutTwoTeamsIsDropped() {
        val bad = """<a>Runde 5</a> <a href="/mannschaft/1/a">A</a> 2:1 (1:1) <a>Runde 6</a>"""
        assertTrue(LigaportalHtml.parseSchedule(bad).none { it.round == 5 })
        assertNull(LigaportalHtml.latestFinished(LigaportalHtml.parseSchedule(bad), "Mannersdorf"))
    }

    @Test fun entities() {
        assertEquals("SC Weißenbach & Co – Özel", LigaportalHtml.decode("SC Wei&szlig;enbach &amp; Co &ndash; &#214;zel"))
    }
}
