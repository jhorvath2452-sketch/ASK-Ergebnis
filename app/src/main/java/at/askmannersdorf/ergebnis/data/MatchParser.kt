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

    // ── Spielbericht / Liveticker: Halbzeit + Torfolge ────────────────

    private val parenScore = Regex("""\(\s*(\d{1,2})\s*:\s*(\d{1,2})\s*\)""")
    private val htLabel = Regex("""\b(?:Halbzeit|HZ|Pause)\b\D{0,12}(\d{1,2})\s*:\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
    private val minuteAny = Regex("""(?<![\d:])(\d{1,3}(?:\s*\+\s*\d{1,2})?)\s*['’´]|(?<![\d:.])(\d{1,3}(?:\s*\+\s*\d{1,2})?)\.(?!\d)""")
    private val runScore = Regex("""(?<!\d)(\d{1,2})\s*:\s*(\d{1,2})(?!\d)""")
    private val nameRx = Regex("""[A-Za-zÀ-ÖØ-öø-ÿĀ-žŞşĞğİıČčŠšŽžĆćĐđ][A-Za-zÀ-ÖØ-öø-ÿĀ-žŞşĞğİıČčŠšŽžĆćĐđ'’.\- ]{1,40}""")
    private val goalWord = Regex("""(?U)\b(Tor|Tore|Treffer|Elfmeter|Strafstoß|Eigentor|Goal)\b|⚽""", RegexOption.IGNORE_CASE)
    private val hardNoise = Regex(
        """(?U)\b(gelbe?|gelb-rot|gelbrot|rote?|karte|verwarnung|ausschluss|wechsel|eingewechselt|ausgewechselt|""" +
            """schiedsrichter|zuschauer|assistent|trainer|anpfiff|abpfiff|halbzeit|pause|spielende|spielbeginn)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val substitution = Regex("""(?U)\bfür\b""", RegexOption.IGNORE_CASE)
    private val filler = Regex(
        """(?U)\b(durch|tor|tore|treffer|für|von|per|nach|elfmeter|strafstoß|kopfball|schuss|vorlage|assist|eigentor|""" +
            """zum|zur|zu|ein|einem|einen|erzielt|trifft|goal|min|minute)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Zeile mit Karte/Wechsel usw. – aber nicht, wenn es um ein Tor geht ("Tor für ASK"). */
    private fun isNoiseLine(l: String): Boolean =
        !goalWord.containsMatchIn(l) && (hardNoise.containsMatchIn(l) || substitution.containsMatchIn(l))

    /** Aussagekräftige Wörter eines Teamnamens: "SG Weissenbach/Klausen" -> [weissenbach, klausen]. */
    fun teamTokens(team: String): List<String> =
        team.lowercase().split(Regex("""[^a-zäöüßà-ÿ0-9]+"""))
            .filter { it.length >= 4 && it.uppercase() !in setOf("UNION", "SPORTUNION") }

    private fun nameCandidates(t: String, match: Match): List<String> {
        var x = t
        listOf(match.homeTeam, match.awayTeam).filter { it.isNotBlank() }.forEach { team ->
            x = x.replace(team, "  ", ignoreCase = true)
            teamTokens(team).forEach { tok -> x = x.replace(Regex("""(?U)\b${Regex.escape(tok)}\b""", RegexOption.IGNORE_CASE), "  ") }
        }
        x = x.replace(minuteAny, "  ").replace(runScore, "  ").replace(filler, "  ")
            .replace(Regex("""[()\[\]:⚽•|]"""), "  ").replace(Regex("""\s[-–]\s"""), "  ")
        return x.split(Regex("""\s{2,}|,|;|/""")).map { it.trim() }
            .mapNotNull { part -> nameRx.find(part)?.value?.trim()?.trim('.', '-', ' ') }
            .filter { n -> n.length >= 3 && n.first().isUpperCase() && n.uppercase() !in clubAbbrev }
    }

    private val clubAbbrev = setOf("SC", "SV", "SG", "ASK", "USC", "ASV", "BSV", "SVG", "FC", "SK", "USV")

    /**
     * Ergänzt ein bekanntes Spiel (Teams + Endstand) um Halbzeit und Torfolge aus dem Text
     * eines Spielberichts oder Livetickers (ÖFB, Ligaportal).
     * 1. Versuch: Torzeilen mit Zwischenstand ("12' Srok 0:1") – eindeutig.
     * 2. Versuch: Torzeilen mit Minute + Teamname ("8' Tor für ASK Mannersdorf durch Marcel Srok").
     * Wird nichts Stimmiges gefunden, bleiben die Tore unverändert.
     */
    fun enrichFromReport(text: String, match: Match, clubKeyword: String): Match {
        val hg = match.homeGoals ?: return match
        val ag = match.awayGoals ?: return match
        val askHome = match.isAskHome(clubKeyword)
        val lines = text.lines().map { it.replace(' ', ' ').trim() }.filter { it.isNotEmpty() }

        fun plausibleHt(h: Int, a: Int) = h <= hg && a <= ag
        val ht = htLabel.find(text)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            ?.takeIf { plausibleHt(it.first, it.second) }
            ?: parenScore.findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                .firstOrNull { plausibleHt(it.first, it.second) }

        val goals = goalsByRunningScore(lines, match, askHome, hg, ag)
            ?: goalsByTeamName(lines, match, askHome, hg, ag)
        return match.copy(
            halfHome = ht?.first ?: match.halfHome,
            halfAway = ht?.second ?: match.halfAway,
            goals = goals ?: match.goals,
        )
    }

    private fun minuteOf(mm: MatchResult) = (mm.groupValues[1].ifEmpty { mm.groupValues[2] }).replace(" ", "") + "'"

    private fun goalsByRunningScore(lines: List<String>, match: Match, askHome: Boolean, hg: Int, ag: Int): List<Goal>? {
        val goals = mutableListOf<Goal>()
        var prevH = 0; var prevA = 0
        var i = 0
        while (i < lines.size && goals.size < hg + ag) {
            val window = (i until minOf(lines.size, i + 3)).map { lines[it] }
            val first = window[0]
            if (isNoiseLine(first)) { i++; continue }
            val mm = minuteAny.find(first)
            if (mm == null) { i++; continue }
            var used = 1
            var rs: MatchResult? = runScore.find(first.removeRange(mm.range))
            if (rs == null) {
                for (k in 1 until window.size) {
                    if (isNoiseLine(window[k]) || minuteAny.containsMatchIn(window[k])) break
                    rs = runScore.find(window[k]); if (rs != null) { used = k + 1; break }
                }
            }
            if (rs == null) { i++; continue }
            val h = rs.groupValues[1].toInt(); val a = rs.groupValues[2].toInt()
            val homeScored = when {
                h == prevH + 1 && a == prevA -> true
                a == prevA + 1 && h == prevH -> false
                else -> { i++; continue }
            }
            prevH = h; prevA = a
            val name = nameCandidates(first.removeRange(mm.range), match).firstOrNull()
                ?: (1 until used).flatMap { nameCandidates(window[it], match) }.firstOrNull()
                ?: lines.getOrNull(i + used)?.takeIf { !minuteAny.containsMatchIn(it) && !runScore.containsMatchIn(it) && !isNoiseLine(it) }
                    ?.let { nameCandidates(it, match).firstOrNull() }
                ?: ""
            val forAsk = homeScored == askHome
            goals += Goal(minuteOf(mm), if (forAsk) name else "", forAsk)
            i += used
        }
        return goals.takeIf { it.size == hg + ag && prevH == hg && prevA == ag }
    }

    private fun goalsByTeamName(lines: List<String>, match: Match, askHome: Boolean, hg: Int, ag: Int): List<Goal>? {
        if (hg + ag == 0) return emptyList()
        val homeTok = teamTokens(match.homeTeam)
        val awayTok = teamTokens(match.awayTeam)
        val goals = mutableListOf<Goal>()
        val seen = mutableSetOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val mm = minuteAny.find(line)
            if (mm == null || isNoiseLine(line)) { i++; continue }
            // Minute und Torhinweis in derselben oder der nächsten Zeile
            val window = (i until minOf(lines.size, i + 3)).map { lines[it] }
                .takeWhile { it == line || (!minuteAny.containsMatchIn(it) && !isNoiseLine(it)) }
            val joined = window.joinToString("  ")
            if (!goalWord.containsMatchIn(joined)) { i++; continue }
            val low = joined.lowercase()
            val homeHit = homeTok.any { low.contains(it) }
            val awayHit = awayTok.any { low.contains(it) }
            if (homeHit == awayHit) { i++; continue } // Team nicht eindeutig
            val minute = minuteOf(mm)
            val forAsk = homeHit == askHome
            val name = nameCandidates(joined, match).firstOrNull().orEmpty()
            val key = "$minute|$homeHit"
            if (seen.add(key)) goals += Goal(minute, if (forAsk) name else "", forAsk)
            i += window.size
        }
        val homeCount = goals.count { (it.forAsk == askHome) }
        return goals.takeIf { it.size == hg + ag && homeCount == hg }
            ?.withIndex()?.sortedWith(compareBy({ it.value.sortKey }, { it.index }))?.map { it.value }
    }

    // ── Ligaportal-Spielplan: letztes gespieltes Spiel ──────────────

    private val dateLoose = Regex("""\b\d{1,2}\.\d{1,2}\.(?:\d{2,4})?""")
    private val rowScore = Regex("""^(\d{1,2})\s*:\s*(\d{1,2})(?:\s*\(\s*(\d{1,2})\s*:\s*(\d{1,2})\s*\))?$""")

    /** Datum als vergleichbarer Wert; ohne Jahr wird die Saison (Juli–Juni) angenommen. */
    fun dateKey(text: String): Int? {
        val m = Regex("""\b(\d{1,2})\.(\d{1,2})\.(\d{2,4})?""").find(text) ?: return null
        val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
        if (d !in 1..31 || mo !in 1..12) return null
        val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            ?: (if (mo >= 7) 1000 else 1001)
        return y * 10000 + mo * 100 + d
    }

    private fun isMatchLink(href: String): Boolean {
        if (!href.startsWith("http")) return false
        val path = runCatching { java.net.URI(href).path.orEmpty() }.getOrDefault("").lowercase()
        if (path.contains("/mannschaft/") || path.contains("/tabelle") || path.contains("/verein")) return false
        return path.contains("spiel") || path.contains("match") || path.contains("ticker") || path.contains("bericht") ||
            Regex("""/\d{4,}""").containsMatchIn(path)
    }

    /**
     * Sucht in einer Spielplan-Seite alle gespielten Spiele des Vereins und liefert das neueste.
     * Funktioniert mit Tabellenzeilen und mit verlinkten Spiel-Kacheln.
     */
    fun latestPlayed(snapshot: PageSnapshot, clubKeyword: String): Match? {
        data class Cand(val m: Match, val order: Int, val date: Int?)
        val cands = mutableListOf<Cand>()

        snapshot.rows.forEachIndexed { idx, row ->
            val cells = row.cells.map { it.replace(' ', ' ').replace(Regex("""\s+"""), " ").trim() }
            if (cells.none { it.contains(clubKeyword, true) }) return@forEachIndexed
            val scoreIdx = cells.indexOfFirst { c ->
                rowScore.find(c)?.let { isPlausibleScore(it.groupValues[1], it.groupValues[2]) } == true
            }
            if (scoreIdx < 0) return@forEachIndexed
            val teams = cells.filterIndexed { i, c ->
                i != scoreIdx && c.length >= 3 && c.any(Char::isLetter) && !dateLoose.containsMatchIn(c) && !timeRx.containsMatchIn(c)
            }
            if (teams.size < 2) return@forEachIndexed
            val sm = rowScore.find(cells[scoreIdx])!!
            val joined = cells.joinToString(" ")
            cands += Cand(
                Match(
                    homeTeam = teams[0], awayTeam = teams[1],
                    homeGoals = sm.groupValues[1].toInt(), awayGoals = sm.groupValues[2].toInt(),
                    halfHome = sm.groupValues[3].toIntOrNull(), halfAway = sm.groupValues[4].toIntOrNull(),
                    date = dateLoose.find(joined)?.value ?: "",
                    reportUrl = row.links.lastOrNull(::isMatchLink),
                    source = "Ligaportal",
                ),
                idx, dateKey(joined),
            )
        }

        if (cands.isEmpty()) {
            snapshot.links.forEachIndexed { idx, link ->
                if (!link.text.contains(clubKeyword, true)) return@forEachIndexed
                val m = parse(link.text, clubKeyword) ?: return@forEachIndexed
                if (!m.isComplete) return@forEachIndexed
                cands += Cand(
                    m.copy(goals = emptyList(), reportUrl = link.href.takeIf(::isMatchLink), source = "Ligaportal"),
                    idx, dateKey(link.text),
                )
            }
        }
        if (cands.isEmpty()) return null
        // Neuestes Datum gewinnt; ohne Datum das letzte in der Liste
        return if (cands.any { it.date != null }) cands.filter { it.date != null }.maxByOrNull { it.date!! }!!.m
        else cands.maxByOrNull { it.order }!!.m
    }

    /** Gleiches Spiel? (gleicher Endstand, Gegner-Name passt) */
    fun sameMatch(a: Match, b: Match, clubKeyword: String): Boolean {
        if (a.homeGoals != b.homeGoals || a.awayGoals != b.awayGoals) return false
        val ta = teamTokens(a.opponent(clubKeyword)); val tb = teamTokens(b.opponent(clubKeyword))
        return ta.isEmpty() || tb.isEmpty() || ta.any { it in tb }
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
