package at.askmannersdorf.ergebnis.flyer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import at.askmannersdorf.ergebnis.R
import at.askmannersdorf.ergebnis.model.Match
import at.askmannersdorf.ergebnis.model.Outcome

enum class FlyerFormat(val width: Int, val height: Int, val label: String) {
    FEED(1080, 1350, "Feed 4:5"),
    STORY(1080, 1920, "Story 9:16"),
}

data class FlyerInput(
    val match: Match,
    val clubKeyword: String,
    val clubDisplayName: String,
    val hashtag: String,
    val photo: Bitmap?,
    val opponentLogo: Bitmap?,
    val showMinutes: Boolean,
)

/**
 * Zeichnet den Ergebnis-Flyer im Stil der Vorlage (Endstand-Tag, Foto, Titelband,
 * Logos + Ergebnis, Torschützen, Fußzeile). Alle Maße beziehen sich auf 1080 px Breite.
 */
class FlyerRenderer(private val context: Context) {

    companion object {
        val DARK_GREEN = Color.rgb(0x0A, 0x3F, 0x1C)
        val DARKER_GREEN = Color.rgb(0x06, 0x28, 0x12)
        val LIME = Color.rgb(0xB9, 0xF2, 0x3C)
        val GREY_GREEN = Color.rgb(0x91, 0xA8, 0x99)
        val LIGHT_GREY = Color.rgb(0xCD, 0xD4, 0xD0)
        val DIVIDER = Color.rgb(0x36, 0x62, 0x45)
        const val PANEL_HEIGHT = 652f
    }

    private val bebas: Typeface by lazy { ResourcesCompat.getFont(context, R.font.bebas_neue) ?: Typeface.DEFAULT_BOLD }
    private val barlowBold: Typeface by lazy { ResourcesCompat.getFont(context, R.font.barlow_condensed_bold) ?: Typeface.DEFAULT_BOLD }
    private val barlowSemi: Typeface by lazy { ResourcesCompat.getFont(context, R.font.barlow_condensed_semibold) ?: Typeface.DEFAULT }
    private val askLogo: Bitmap by lazy { BitmapFactory.decodeResource(context.resources, R.drawable.ask_logo) }

    fun title(input: FlyerInput): String {
        val m = input.match
        return when (m.outcome(input.clubKeyword)) {
            Outcome.WIN -> if (m.isAskHome(input.clubKeyword)) "HEIMSIEG!" else "AUSWÄRTSSIEG!"
            Outcome.DRAW -> "UNENTSCHIEDEN!"
            Outcome.LOSS -> "NIEDERLAGE"
            null -> "ENDSTAND"
        }
    }

    fun render(input: FlyerInput, format: FlyerFormat): Bitmap {
        val w = format.width.toFloat()
        val h = format.height.toFloat()
        val bmp = Bitmap.createBitmap(format.width, format.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val m = input.match
        val kw = input.clubKeyword
        val outcome = m.outcome(kw)

        val photoBottom = h - PANEL_HEIGHT
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // ── Hintergrund + Foto ───────────────────────────────────────
        c.drawColor(DARK_GREEN)
        if (input.photo != null) {
            drawCenterCrop(c, input.photo, RectF(0f, 0f, w, photoBottom), focusY = 0.4f)
        } else {
            p.color = DARKER_GREEN
            c.drawRect(0f, 0f, w, photoBottom, p)
        }

        // ── Tag "● ENDSTAND" ────────────────────────────────────────
        p.color = DARK_GREEN
        c.drawRect(40f, 40f, 255f, 92f, p)
        p.color = LIME
        c.drawCircle(64f, 66f, 6f, p)
        drawSpaced(c, "ENDSTAND", 82f, 79f, barlowBold, 34f, Color.WHITE, spacing = 0.12f)

        // ── Titelband ────────────────────────────────────────────────
        val title = title(input)
        val titlePaint = textPaint(bebas, 124f, DARK_GREEN)
        var titleSize = 124f
        while (titlePaint.measureText(title) > w - 140f && titleSize > 60f) {
            titleSize -= 4f; titlePaint.textSize = titleSize
        }
        val bandTop = photoBottom - 118f
        val bandRight = maxOf(626f, 43f + titlePaint.measureText(title) + 44f)
        p.color = if (outcome == Outcome.LOSS) GREY_GREEN else LIME
        c.drawRect(0f, bandTop, bandRight, photoBottom, p)
        c.drawText(title, 36f, photoBottom - 18f, titlePaint)

        // ── Panel ────────────────────────────────────────────────────
        p.color = DARK_GREEN
        c.drawRect(0f, photoBottom, w, h, p)
        val y0 = photoBottom // alle Panel-Maße relativ dazu (Vorlage: 698)

        val askHome = m.isAskHome(kw)
        val leftCenterX = 176f
        val rightCenterX = 904f
        val logoY = y0 + 160f
        val homeLogo = if (askHome) askLogo else input.opponentLogo
        val awayLogo = if (askHome) input.opponentLogo else askLogo
        drawLogoBadge(c, leftCenterX, logoY, homeLogo, m.homeTeam)
        drawLogoBadge(c, rightCenterX, logoY, awayLogo, m.awayTeam)

        // Ergebnis
        val scorePaint = textPaint(bebas, 268f, Color.WHITE)
        val hg = m.homeGoals?.toString() ?: "-"
        val ag = m.awayGoals?.toString() ?: "-"
        val colon = ":"
        val gap = 18f
        val wh = scorePaint.measureText(hg)
        val wc = scorePaint.measureText(colon)
        val wa = scorePaint.measureText(ag)
        val total = wh + gap + wc + gap + wa
        var x = w / 2f - total / 2f
        val baseline = y0 + 252f
        scorePaint.color = if (askHome) LIME else GREY_GREEN
        c.drawText(hg, x, baseline, scorePaint); x += wh + gap
        scorePaint.color = GREY_GREEN
        c.drawText(colon, x, baseline, scorePaint); x += wc + gap
        scorePaint.color = if (askHome) GREY_GREEN else LIME
        c.drawText(ag, x, baseline, scorePaint)

        // Teamnamen
        drawSpacedCentered(c, m.homeTeam.uppercase(), leftCenterX, y0 + 302f, barlowBold, 34f, Color.WHITE, 0.08f, maxWidth = 330f)
        drawSpacedCentered(c, m.awayTeam.uppercase(), rightCenterX, y0 + 302f, barlowBold, 34f, Color.WHITE, 0.08f, maxWidth = 330f)

        // Trennlinie
        p.color = DIVIDER
        c.drawRect(56f, y0 + 347f, w - 56f, y0 + 349f, p)

        // Torschützen
        val askGoals = m.sortedGoals.filter { it.forAsk && it.scorer.isNotBlank() }
        if (askGoals.isNotEmpty()) {
            drawSpacedCentered(c, "TORE ${input.clubDisplayName.uppercase()}", w / 2f, y0 + 408f, barlowBold, 27f, LIME, 0.35f)
            drawScorers(c, buildScorerSegments(askGoals.map { it.scorer to it.minute }, input.showMinutes), w / 2f, y0 + 480f, maxWidth = w - 100f)
        } else if (m.askGoals(kw) == 0) {
            drawSpacedCentered(c, "KOPF HOCH – WEITER GEHT'S!", w / 2f, y0 + 470f, bebas, 64f, Color.WHITE, 0.02f)
        }

        // ── Fußzeile ─────────────────────────────────────────────────
        p.color = DARKER_GREEN
        c.drawRect(0f, h - 84f, w, h, p)
        val footerLeft = "${m.homeTeam} – ${m.awayTeam}".uppercase()
        drawSpaced(c, footerLeft, 45f, h - 31f, barlowSemi, 26f, LIGHT_GREY, 0.2f, maxWidth = 620f)
        drawSpacedRight(c, input.hashtag.uppercase(), w - 45f, h - 31f, barlowBold, 27f, Color.WHITE, 0.2f)

        return bmp
    }

    // ── Hilfsfunktionen ──────────────────────────────────────────────

    private fun textPaint(tf: Typeface, size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf; textSize = size; this.color = color
    }

    private fun drawCenterCrop(c: Canvas, src: Bitmap, dst: RectF, focusY: Float) {
        val scale = maxOf(dst.width() / src.width, dst.height() / src.height)
        val cropW = dst.width() / scale
        val cropH = dst.height() / scale
        val left = (src.width - cropW) / 2f
        val top = ((src.height - cropH) * focusY).coerceIn(0f, src.height - cropH)
        val srcRect = Rect(left.toInt(), top.toInt(), (left + cropW).toInt(), (top + cropH).toInt())
        c.drawBitmap(src, srcRect, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    /** Weißes Abzeichen wie in der Vorlage: Kreis + Quadrat, Logo mittig. */
    private fun drawLogoBadge(c: Canvas, cx: Float, cy: Float, logo: Bitmap?, teamName: String) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.color = Color.WHITE
        c.drawCircle(cx, cy, 100f, p)
        c.drawRect(cx - 84f, cy - 84f, cx + 84f, cy + 84f, p)
        if (logo != null) {
            val box = 150f
            val s = minOf(box / logo.width, box / logo.height)
            val lw = logo.width * s; val lh = logo.height * s
            c.drawBitmap(logo, null, RectF(cx - lw / 2, cy - lh / 2, cx + lw / 2, cy + lh / 2), p)
        } else {
            val initials = teamName.split(Regex("""\s+""")).filter { it.isNotEmpty() }
                .let { parts -> if (parts.size >= 2) parts.take(2) else parts }
                .joinToString("") { it.first().uppercase() }.ifBlank { "?" }
            val tp = textPaint(bebas, 96f, DARK_GREEN).apply { textAlign = Paint.Align.CENTER }
            c.drawText(initials, cx, cy + 34f, tp)
        }
    }

    private fun spacedWidth(text: String, paint: Paint, spacingPx: Float): Float =
        paint.measureText(text) + spacingPx * (text.length - 1).coerceAtLeast(0)

    private fun drawSpaced(
        c: Canvas, text: String, x: Float, y: Float, tf: Typeface, size: Float, color: Int,
        spacing: Float, maxWidth: Float = Float.MAX_VALUE,
    ) {
        val p = textPaint(tf, size, color)
        fitSpaced(text, p, spacing, maxWidth)
        p.letterSpacing = spacing
        c.drawText(text, x, y, p)
    }

    private fun drawSpacedCentered(
        c: Canvas, text: String, cx: Float, y: Float, tf: Typeface, size: Float, color: Int,
        spacing: Float, maxWidth: Float = Float.MAX_VALUE,
    ) {
        val p = textPaint(tf, size, color)
        fitSpaced(text, p, spacing, maxWidth)
        p.letterSpacing = spacing
        // letterSpacing fügt auch nach dem letzten Zeichen Abstand hinzu → korrigieren
        val width = p.measureText(text) - spacing * p.textSize
        c.drawText(text, cx - width / 2f, y, p)
    }

    private fun drawSpacedRight(
        c: Canvas, text: String, right: Float, y: Float, tf: Typeface, size: Float, color: Int, spacing: Float,
    ) {
        val p = textPaint(tf, size, color)
        p.letterSpacing = spacing
        val width = p.measureText(text) - spacing * p.textSize
        c.drawText(text, right - width, y, p)
    }

    private fun fitSpaced(text: String, p: Paint, spacing: Float, maxWidth: Float) {
        while (spacedWidth(text, p, spacing * p.textSize) > maxWidth && p.textSize > 14f) {
            p.textSize -= 1f
        }
    }

    /** Ein Torschütze: Name + Zusatz (z. B. "2×" oder Minuten). */
    data class ScorerSegment(val name: String, val extra: String)

    fun buildScorerSegments(goals: List<Pair<String, String>>, showMinutes: Boolean): List<ScorerSegment> {
        val order = LinkedHashMap<String, MutableList<String>>()
        goals.forEach { (scorer, minute) ->
            val key = lastName(scorer)
            order.getOrPut(key) { mutableListOf() }.add(minute)
        }
        return order.map { (name, minutes) ->
            val extra = when {
                showMinutes -> minutes.filter { it.isNotBlank() }.joinToString(" ")
                minutes.size > 1 -> "${minutes.size}×"
                else -> ""
            }
            ScorerSegment(name.uppercase(), extra)
        }
    }

    /** "Srok Marcel" / "Marcel Srok" → wir nehmen den Namen so, wie er geliefert wird, aber kürzen sehr lange. */
    private fun lastName(full: String): String =
        at.askmannersdorf.ergebnis.data.SmsFormatter.shortScorer(full)

    private fun drawScorers(c: Canvas, segs: List<ScorerSegment>, cx: Float, baseline: Float, maxWidth: Float) {
        if (segs.isEmpty()) return
        var size = 74f
        val namePaint = textPaint(bebas, size, Color.WHITE)
        val extraPaint = textPaint(bebas, size, LIME)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY_GREEN }

        fun lineWidth(line: List<ScorerSegment>): Float {
            val gapDot = size * 0.9f
            return line.sumOf { s ->
                (namePaint.measureText(s.name) + if (s.extra.isNotEmpty()) size * 0.12f + extraPaint.measureText(s.extra) else 0f).toDouble()
            }.toFloat() + gapDot * (line.size - 1)
        }
        fun setSize(sz: Float) { size = sz; namePaint.textSize = sz; extraPaint.textSize = sz }

        // Eine Zeile, wenn möglich; sonst auf zwei Zeilen verteilen
        var lines = listOf(segs)
        while (lineWidth(segs) > maxWidth && size > 60f) setSize(size - 2f)
        if (lineWidth(segs) > maxWidth) {
            val mid = (segs.size + 1) / 2
            lines = listOf(segs.take(mid), segs.drop(mid))
            setSize(70f)
            while (lines.maxOf { lineWidth(it) } > maxWidth && size > 36f) setSize(size - 2f)
        }

        val lineHeight = size * 0.95f
        var y = baseline - (lines.size - 1) * lineHeight / 2f
        for (line in lines) {
            var x = cx - lineWidth(line) / 2f
            line.forEachIndexed { i, s ->
                c.drawText(s.name, x, y, namePaint)
                x += namePaint.measureText(s.name)
                if (s.extra.isNotEmpty()) {
                    x += size * 0.12f
                    c.drawText(s.extra, x, y, extraPaint)
                    x += extraPaint.measureText(s.extra)
                }
                if (i < line.lastIndex) {
                    val gapDot = size * 0.9f
                    c.drawCircle(x + gapDot / 2f, y - size * 0.33f, size * 0.07f, dotPaint)
                    x += gapDot
                }
            }
            y += lineHeight
        }
    }
}
