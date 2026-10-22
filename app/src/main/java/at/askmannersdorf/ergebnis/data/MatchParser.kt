package at.askmannersdorf.ergebnis.data

import at.askmannersdorf.ergebnis.model.Goal
import at.askmannersdorf.ergebnis.model.Match

/**
 * Liest Ergebnis, Teams, Datum und Torschützen aus dem sichtbaren Text einer Seite.
 * Arbeitet bewusst tolerant, weil sich das Layout von ÖFB und Ligaportal ändern kann.
 * Alles, was hier erkannt wird, kann in der App noch von Hand korrigiert werden.
 */
object MatchParser {

    private val scoreLine = Regex("""^(\d{1,2})\s*[:\-–]\s*(\d{1,2})(\s*\(.*\))?$""")
    private val inlineScore = Regex("""^(.{3,60}?)\s+(\d{1,2})\s*:\s*(\d{1,2})\s+(.{3,60})$""")
    private val dateRx = Regex("""\b(\d{1,2})\.(\d{1,2})\.(\d{2,4})?\b""")
    private val timeRx = Regex("""\b\d{1,2}:\d{2}\s*(Uhr)?\b""")

    // "12' Srok", "12. Srok", "45+2' Srok", "12' (1:0) Srok"
    private val minuteFirst = Regex(
        """^(\d{1,3}(?:\+\d{1,2})?)\s*['’´.]\s*(?:\(?\s*(\d{1,2})\s*:\s*(\d{1,2})\s*\)?\s*)?([A-Za-zÀ-ÖØ-öø-ÿĀ-žŞşĞğİı][A-Za-zÀ-ÖØ-öø-ÿĀ-žŞşĞğİı .\-]{1,40})$"""
    )
    // "Srok 12'", "Srok (12.)", "Srok 12', 67'"
    private val nameFirst = Regex(
        """^([A-Za-zÀ-ÖØ-öø-ÿĀ-žŞşĞğİı][A-Za-zÀ-ÖØ-öø-ÿĀ-žŞşĞğİı .\-]{1,40}?)\s*\(?\s*((?:\d{1,3}(?:\+\d{1,2})?\s*['’´.]\s*,?\s*)+)\)?$"""
    )
    private val minuteToken = Regex("""(\d{1,3}(?:\+\d{1,2})?)\s*['’´.]""")

    private val noiseWords = listOf(
        "tabelle", "spielbericht", "aufstellung", "liveticker", "nutzungsbedingungen",
        "lädt", "gelbe", "rote", "karte", "wechsel", "ausw.", "eingewechselt", "schiedsrichter",
        "zuschauer", "runde", "spieltag", "anpfiff", "ende", "halbzeit",
    )

    private fun isPlausibleScore(a: String, b: String): Boolean {
        val x = a.toInt(); val y = b.toInt()
        if (x > 12 || y > 12) return false
        if ((a.length == 2 && a.startsWith("0")) || (b.length == 2 && b.startsWith("0"))) return false
        return true
    }

    private fun looksLikeTeam(line: String): Boolean {
        if (line.length !in 3..60) return false
        if (!line.any { it.isLetter() }) return false
        if (dateRx.containsMatchIn(line) || timeRx.containsMatchIn(line)) return false
        val l = line.lowercase()
        if (noiseWords.any { l == it || l.startsWith("$it ") }) return false
        return true
    }

    fun parse(text: String, clubKeyword: String): Match? {
        val lines = text.lines().map { it.replace(' ', ' ').trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        var home = ""; var away = ""; var hg: Int? = null; var ag: Int? = null
        var scoreIndex = -1

        // 1) Ergebnis in eigener Zeile, Teams davor/danach
        for ((i, line) in lines.withIndex()) {
            val m = scoreLine.find(line) ?: continue
            val (a, b) = m.destructured
            if (!isPlausibleScore(a, b)) continue
            val before = (i - 1 downTo maxOf(0, i - 4)).map { lines[it] }.firstOrNull(::looksLikeTeam)
            val after = (i + 1..minOf(lines.lastIndex, i + 4)).map { lines[it] }.firstOrNull(::looksLikeTeam)
            if (before != null && after != null &&
                (before.contains(clubKeyword, true) || after.contains(clubKeyword, true))
            ) {
                home = before; away = after; hg = a.toInt(); ag = b.toInt(); scoreIndex = i
                break
            }
        }
        // 2) Ergebnis in einer Zeile mit beiden Teams
        if (scoreIndex < 0) {
            for ((i, line) in lines.withIndex()) {
                val m = inlineScore.find(line) ?: continue
                val (t1, a, b, t2) = m.destructured
                if (!isPlausibleScore(a, b)) continue
                if (!t1.contains(clubKeyword, true) && !t2.contains(clubKeyword, true)) continue
                home = t1.trim(); away = t2.trim(); hg = a.toInt(); ag = b.toInt(); scoreIndex = i
                break
            }
        }
        // 3) Teams und Ergebnis über mehrere Zeilen verteilt: "Team A" / "1" / ":" / "4" / "Team B"
        if (scoreIndex < 0) {
            for (i in 0 until lines.size - 2) {
                val a = lines[i]; val sep = lines[i + 1]; val b = lines[i + 2]
                if (a.all(Char::isDigit) && sep in listOf(":", "-", "–") && b.all(Char::isDigit) && isPlausibleScore(a, b)) {
                    val before = (i - 1 downTo maxOf(0, i - 4)).map { lines[it] }.firstOrNull(::looksLikeTeam)
                    val after = (i + 3..minOf(lines.lastIndex, i + 6)).map { lines[it] }.firstOrNull(::looksLikeTeam)
                    if (before != null && after != null) {
                        home = before; away = after; hg = a.toInt(); ag = b.toInt(); scoreIndex = i
                        break
                    }
                }
            }
        }
        if (scoreIndex < 0) return null

        val date = lines.firstNotNullOfOrNull { dateRx.find(it)?.value } ?: ""

        val askIsHome = home.contains(clubKeyword, true)
        val goals = parseGoals(lines, scoreIndex, home, away, askIsHome)

        return Match(
            homeTeam = cleanTeam(home), awayTeam = cleanTeam(away),
            homeGoals = hg, awayGoals = ag, date = date, goals = goals,
        )
    }

    private fun cleanTeam(t: String) = t.replace(Regex("""\s{2,}"""), " ").trim()

    private fun parseGoals(
        lines: List<String>, scoreIndex: Int, home: String, away: String, askIsHome: Boolean,
    ): List<Goal> {
        val result = mutableListOf<Goal>()
        var prevHome = 0; var prevAway = 0
        // Abschnitt erkennen: Zeile mit Teamnamen schaltet die "aktuelle Mannschaft" um
        var currentSideHome: Boolean? = null

        // Den Gast-Teamnamen direkt nach dem Ergebnis überspringen (gehört zum Spielkopf)
        val awayHeader = ((scoreIndex + 1)..minOf(lines.lastIndex, scoreIndex + 6))
            .firstOrNull { lines[it].equals(away, true) }
        val start = (awayHeader ?: scoreIndex) + 1

        for (i in start..lines.lastIndex) {
            val line = lines[i]
            if (line.equals(home, true)) { currentSideHome = true; continue }
            if (line.equals(away, true)) { currentSideHome = false; continue }
            if (dateRx.containsMatchIn(line) || timeRx.containsMatchIn(line)) continue
            val low = line.lowercase()
            if (noiseWords.any { low.contains(it) }) continue

            val mf = minuteFirst.find(line)
            if (mf != null) {
                val (minute, sh, sa, name) = mf.destructured
                var forHome: Boolean? = currentSideHome
                if (sh.isNotEmpty() && sa.isNotEmpty()) {
                    val h = sh.toInt(); val a = sa.toInt()
                    forHome = when {
                        h > prevHome -> true
                        a > prevAway -> false
                        else -> forHome
                    }
                    prevHome = h; prevAway = a
                }
                result += Goal(normMinute(minute), name.trim(), forAsk = forHome?.let { it == askIsHome } ?: true)
                continue
            }
            val nf = nameFirst.find(line)
            if (nf != null) {
                val (name, minutes) = nf.destructured
                minuteToken.findAll(minutes).forEach {
                    result += Goal(
                        normMinute(it.groupValues[1]), name.trim(),
                        forAsk = currentSideHome?.let { s -> s == askIsHome } ?: true,
                    )
                }
            }
        }
        return result.distinct()
    }

    private fun normMinute(m: String) = m.trim().trimEnd('.', '\'', '’', '´') + "'"

    // ── LMO (askmannersdorf.at/LMO_2026) ──────────────────────────────

    private val lmoScore = Regex("""^(\d{1,2})\s*:\s*(\d{1,2})$""")
    private val lmoOpen = Regex("""^[-_]\s*:\s*[-_]$""")
    private val lmoHeading = Regex("""Spieltag\s+(\d{1,2})""")

    /** Spieltag-Nummer aus der Überschrift "Ergebnisse Spieltag 10". */
    fun lmoMatchday(text: String): Int? =
        text.lines().firstOrNull { it.contains("Ergebnisse", true) && lmoHeading.containsMatchIn(it) }
            ?.let { lmoHeading.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: lmoHeading.find(text)?.groupValues?.get(1)?.toIntOrNull()

    sealed class LmoRowResult {
        data class Played(val match: Match) : LmoRowResult()
        data class Open(val opponent: String) : LmoRowResult()
        object NotFound : LmoRowResult()
    }

    /**
     * Sucht in den Tabellenzeilen eines LMO-Spieltags das Spiel des eigenen Vereins.
     * Zeile z. B.: ["18.10.2026 15:00", "SG Weissenbach/Klausen", "ASK Mannersdorf", "1 : 4", "📋"]
     */
    fun parseLmoRows(rows: List<PageRow>, clubKeyword: String): LmoRowResult {
        for (row in rows) {
            val cells = row.cells.map { it.replace(' ', ' ').replace(Regex("""\s+"""), " ").trim() }
            if (cells.none { it.contains(clubKeyword, true) }) continue
            val scoreIdx = cells.indexOfFirst { lmoScore.matches(it) || lmoOpen.matches(it) }
            if (scoreIdx < 0) continue
            fun isTeam(c: String) = c.length >= 3 && c.any(Char::isLetter) &&
                !dateRx.containsMatchIn(c) && !c.contains("Spielbericht", true)
            // Teams in Zeilenreihenfolge: erste = Heim, zweite = Gast (Ergebnis davor, dazwischen oder danach)
            val teams = cells.filterIndexed { idx, c -> idx != scoreIdx && isTeam(c) }
            if (teams.size < 2) continue
            val home = teams[0]
            val away = teams[1]
            if (!home.contains(clubKeyword, true) && !away.contains(clubKeyword, true)) continue

            val sm = lmoScore.find(cells[scoreIdx])
            if (sm == null) {
                return LmoRowResult.Open(if (home.contains(clubKeyword, true)) away else home)
            }
            val date = cells.firstNotNullOfOrNull { dateRx.find(it)?.value } ?: ""
            val report = row.links.firstOrNull { it.contains("Spielbericht", true) }
            val logos = row.imgs.filter { it.startsWith("http") }
            return LmoRowResult.Played(
                Match(
                    homeTeam = home, awayTeam = away,
                    homeGoals = sm.groupValues[1].toInt(), awayGoals = sm.groupValues[2].toInt(),
                    date = date, reportUrl = report,
                    homeLogoUrl = logos.getOrNull(0), awayLogoUrl = logos.getOrNull(1),
                    source = "LMO",
                )
            )
        }
        return LmoRowResult.NotFound
    }

    // ── ÖFB-Spielbericht: Halbzeit + Torfolge ─────────────────────────

    private val parenScore = Regex("""\(\s*(\d{1,2})\s*:\s*(\d{1,2})\s*\)""")
    private val htLabel = Regex("""\b(?:Halbzeit|HZ|Pause)\b\D{0,12}(\d{1,2})\s*:\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
    private val minuteAny = Regex("""(?<![\d:])(\d{1,3}(?:\s*\+\s*\d{1,2})?)\s*['’´]|(?<![\d:])(\d{1,3}(?:\s*\+\s*\d{1,2})?)\.(?!\d)""")
    private val runScore = Regex("""(?<!\d)(\d{1,2})\s*:\s*(\d{1,2})(?!\d)""")
    private val nameRx = Regex("""[A-Za-zÀ-ÖØ-öø-ÿĀ-žŞşĞğİıČčŠšŽžĆćĐđ][A-Za-zÀ-ÖØ-öø-ÿĀ-žŞşĞğİıČčŠšŽžĆćĐđ'’.\- ]{1,40}""")
    private val reportNoise = listOf(
        "gelb", "rot", "karte", "wechsel", " für ", "eingewechselt", "ausgewechselt",
        "schiedsrichter", "zuschauer", "assistent", "trainer", "anpfiff", "abpfiff",
    )

    /**
     * Ergänzt ein bereits bekanntes Spiel (Teams + Endstand aus LMO) um Halbzeit und Tore
     * aus dem Text des ÖFB-Spielberichts. Gibt das Spiel unverändert zurück, wenn nichts passt.
     */
    fun enrichFromReport(text: String, match: Match, clubKeyword: String): Match {
        val hg = match.homeGoals ?: return match
        val ag = match.awayGoals ?: return match
        val askHome = match.isAskHome(clubKeyword)
        val lines = text.lines().map { it.replace(' ', ' ').trim() }.filter { it.isNotEmpty() }

        // Halbzeit: "Halbzeit 1:2" oder "(1:2)" – muss <= Endstand sein
        fun plausibleHt(h: Int, a: Int) = h <= hg && a <= ag
        val ht = htLabel.find(text)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            ?.takeIf { plausibleHt(it.first, it.second) }
            ?: parenScore.findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                .firstOrNull { plausibleHt(it.first, it.second) }

        // Torzeilen: Minute + Zwischenstand (+ Name), auch über 2–3 Zeilen verteilt
        val goals = mutableListOf<Goal>()
        var prevH = 0; var prevA = 0
        var i = 0
        while (i < lines.size && goals.size < hg + ag) {
            val window = (i until minOf(lines.size, i + 3)).map { lines[it] }
            val first = window[0]
            val low = first.lowercase()
            if (reportNoise.any { low.contains(it) }) { i++; continue }
            val mm = minuteAny.find(first)
            if (mm == null) { i++; continue }
            // Zwischenstand in derselben Zeile oder in den nächsten zwei Zeilen
            var used = 1
            var rs: MatchResult? = runScore.find(first.removeRange(mm.range))
            if (rs == null) {
                for (k in 1 until window.size) {
                    if (reportNoise.any { window[k].lowercase().contains(it) } || minuteAny.containsMatchIn(window[k])) break
                    rs = runScore.find(window[k]); if (rs != null) { used = k + 1; break }
                }
            }
            if (rs == null) { i++; continue }
            val h = rs.groupValues[1].toInt(); val a = rs.groupValues[2].toInt()
            val homeScored = when {
                h == prevH + 1 && a == prevA -> true
                a == prevA + 1 && h == prevH -> false
                else -> { i++; continue } // passt nicht zur Torfolge -> keine Torzeile
            }
            prevH = h; prevA = a
            val minute = (mm.groupValues[1].ifEmpty { mm.groupValues[2] }).replace(" ", "") + "'"
            fun candidates(t: String): List<String> =
                t.replace(minuteAny, "  ").replace(runScore, "  ").replace(Regex("""[()\[\]:]"""), "  ")
                    .split(Regex("""\s{2,}|,|;""")).map { it.trim() }
                    .mapNotNull { part -> nameRx.find(part)?.value?.trim() }
                    .filter { n ->
                        n.length >= 2 && !n.equals(match.homeTeam, true) && !n.equals(match.awayTeam, true) &&
                            reportNoise.none { n.lowercase().contains(it.trim()) } && !n.equals("Tor", true)
                    }
            // Name: zuerst in der Minutenzeile, dann zwischen Minute und Zwischenstand,
            // dann in der Zeile direkt nach dem Zwischenstand
            val name = candidates(first.removeRange(mm.range)).firstOrNull()
                ?: (1 until used).flatMap { candidates(window[it]) }.firstOrNull()
                ?: lines.getOrNull(i + used)?.takeIf { !minuteAny.containsMatchIn(it) && !runScore.containsMatchIn(it) }
                    ?.let { candidates(it).firstOrNull() }
                ?: ""
            val forAsk = homeScored == askHome
            goals += Goal(minute, if (forAsk) name else "", forAsk)
            i += used
        }

        val goalsOk = goals.size == hg + ag && prevH == hg && prevA == ag
        return match.copy(
            halfHome = ht?.first ?: match.halfHome,
            halfAway = ht?.second ?: match.halfAway,
            goals = if (goalsOk) goals else match.goals,
        )
    }

    /** Sucht passende Logos: zuerst über alt-Text, sonst die ersten Wappen-Bilder. */
    fun findLogos(snapshot: PageSnapshot, home: String, away: String): Pair<String?, String?> {
        fun tokens(t: String) = t.lowercase().split(Regex("""[^a-zäöüß0-9]+""")).filter { it.length >= 4 }
        fun matches(img: PageImage, team: String) =
            tokens(team).any { tok -> img.alt.lowercase().contains(tok) || img.src.lowercase().contains(tok) }

        val candidates = snapshot.images.filter { it.src.startsWith("http") && !it.src.endsWith(".svg") }
        val h = candidates.firstOrNull { matches(it, home) }?.src
        val a = candidates.firstOrNull { matches(it, away) && it.src != h }?.src
        if (h != null || a != null) return h to a
        val crests = candidates.filter {
            val s = it.src.lowercase()
            s.contains("logo") || s.contains("wappen") || s.contains("emblem") || s.contains("crest")
        }
        return crests.getOrNull(0)?.src to crests.getOrNull(1)?.src
    }
}
