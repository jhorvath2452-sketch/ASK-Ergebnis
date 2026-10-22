package at.askmannersdorf.ergebnis.flyer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import at.askmannersdorf.ergebnis.R
import at.askmannersdorf.ergebnis.model.Match
import at.askmannersdorf.ergebnis.model.Outcome
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

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
    /** Kurznamen unter dem Ergebnis, z. B. "Weissenbach" / "Mannersdorf". */
    val homeLabel: String,
    val awayLabel: String,
    /** Eigener Kurzname für "TORE FÜR …". */
    val ownShort: String,
    /** Erkannte Gesichter im Foto (Fotokoordinaten), damit sie nicht vom Text verdeckt werden. */
    val photoFaces: List<PhotoPlacement.Box> = emptyList(),
)

/**
 * Ergebnis-Flyer im "Matchday"-Stil: Logos mit VS, Mannschaftsfoto, Pinsel-Titel,
 * großes Ergebnis mit Struktur, Torschützen-Box und Hashtag.
 * Maße beziehen sich auf 1080 px Breite; der untere Block wird von unten aufgebaut,
 * damit Feed (4:5) und Story (9:16) dasselbe Layout nutzen.
 */
class FlyerRenderer(private val context: Context) {

    companion object {
        /** gedämpftes Rasengrün statt Neon */
        val ACCENT = Color.rgb(0x5D, 0xB8, 0x5F)
        val ACCENT_DARK = Color.rgb(0x2E, 0x7D, 0x32)
        val BG = Color.rgb(6, 20, 11)
        val GREY = Color.rgb(0xB8, 0xC4, 0xBC)
        private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun font(id: Int) = ResourcesCompat.getFont(context, id) ?: Typeface.DEFAULT_BOLD
    /** Titel/VS: schmale, kursive Pinselschrift (ähnlich der Vorlage) + Trockenpinsel-Struktur */
    private val fBrush by lazy { font(R.font.bangers) }
    /** Hashtag: Albertsthal Typewriter */
    private val fTag by lazy { font(R.font.albertsthal_typewriter) }
    private val fHeavy by lazy { font(R.font.archivo_black) }
    private val fCond by lazy { font(R.font.barlow_condensed_bold) }
    private val askLogo: Bitmap by lazy { BitmapFactory.decodeResource(context.resources, R.drawable.ask_logo) }

    /** Titel in zwei Teilen: weiß + grün (bei Niederlage grau). */
    fun titleParts(input: FlyerInput): Pair<String, String> {
        val m = input.match
        return when (m.outcome(input.clubKeyword)) {
            Outcome.WIN -> (if (m.isAskHome(input.clubKeyword)) "HEIM" else "AUSWÄRTS") to "SIEG!"
            Outcome.DRAW -> "PUNKTE" to "TEILUNG"
            Outcome.LOSS -> "NIEDER" to "LAGE"
            null -> "END" to "STAND"
        }
    }

    fun render(input: FlyerInput, format: FlyerFormat): Bitmap {
        val w = format.width.toFloat()
        val h = format.height.toFloat()
        val bmp = Bitmap.createBitmap(format.width, format.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = Random(7)
        val m = input.match
        val kw = input.clubKeyword
        val askHome = m.isAskHome(kw)
        val outcome = m.outcome(kw)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // ── Layout des unteren Blocks vorab berechnen (von unten nach oben) ──
        val tagBase = h - 40f
        val boxBottom = h - 102f
        val labels = ScorerFormat.labels(
            m.sortedGoals.filter { it.forAsk && it.scorer.isNotBlank() }.map { it.scorer to it.minute }, input.showMinutes,
        )
        val linePaint = textPaint(fCond, 46f, Color.WHITE)
        val sep = "  •  "
        fun lw(line: List<String>) = line.sumOf { linePaint.measureText(it).toDouble() }.toFloat() +
            linePaint.measureText(sep) * (line.size - 1)
        var lines = listOf(labels)
        while (labels.isNotEmpty() && lw(labels) > w - 160f && linePaint.textSize > 34f) linePaint.textSize -= 2f
        if (labels.isNotEmpty() && lw(labels) > w - 160f) {
            val mid = (labels.size + 1) / 2
            lines = listOf(labels.take(mid), labels.drop(mid))
            while (lines.maxOf { lw(it) } > w - 160f && linePaint.textSize > 24f) linePaint.textSize -= 2f
        }
        val hasBox = labels.isNotEmpty()
        val boxH = if (hasBox) 58f + 54f * lines.size + 12f else 0f
        val boxTop = boxBottom - boxH
        // Ergebnis direkt über der Box (Teamnamen stehen schon bei den Logos)
        val scoreSize = 245f
        val digitsBase = (if (hasBox) boxTop else boxBottom) - 40f
        val scorePaint = textPaint(fHeavy, scoreSize, Color.WHITE)
        val bounds = Rect(); scorePaint.getTextBounds("14", 0, 2, bounds)
        val digitsTop = digitsBase + bounds.top
        val titleBase = digitsTop - 30f

        // Titel (schräg, Pinselschrift) + Unterstrich
        val (p1, p2) = titleParts(input)
        val titlePaint = textPaint(fBrush, 150f, Color.WHITE)
        while (titlePaint.measureText(p1 + p2) > w - 70f && titlePaint.textSize > 60f) titlePaint.textSize -= 4f
        // ── Hintergrund ──────────────────────────────────────────────
        c.drawColor(BG)
        p.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.rgb(8, 26, 14), Color.rgb(6, 20, 11), Color.rgb(12, 40, 20)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        glow(c, -120f + 450f, h - 620f + 450f, 450f, ACCENT, 70)
        glow(c, w - 780f + 450f, h - 620f + 450f, 450f, ACCENT, 70)

        // ── Foto: Gesichter immer im freien Bereich zwischen Logos und Titel ──
        val logoCy = 205f
        val box = 300f
        val aBounds = Rect(); textPaint(fBrush, titlePaint.textSize, Color.WHITE).getTextBounds("A", 0, 1, aBounds)
        // Titel ist um 4° gedreht: an der höheren Seite ~38 px höher; plus Abstand
        val safeTop = logoCy + box / 2f + 10f
        val safeBot = titleBase + aBounds.top - 38f - 8f
        val bandTop = 200f
        val bandBot = digitsTop + 20f
        input.photo?.let { photo -> drawPhoto(c, photo, input.photoFaces, w, bandTop, bandBot, safeTop, safeBot) }
        // Vignette links/rechts
        val vig = withAlpha(Color.rgb(3, 10, 6), 120)
        p.shader = LinearGradient(0f, 0f, 160f, 0f, vig, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 160f, h, p)
        p.shader = LinearGradient(w, 0f, w - 160f, 0f, vig, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(w - 160f, 0f, w, h, p)
        // oben abdunkeln für die Logos
        p.shader = LinearGradient(0f, 0f, 0f, 260f, withAlpha(Color.rgb(4, 14, 8), 160), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, 260f, p); p.shader = null

        // ── Flutlicht oben links/rechts ──────────────────────────────
        for (cx in floatArrayOf(60f, w - 60f)) {
            glow(c, cx, 20f, 380f, Color.rgb(225, 255, 230), 185)
            val sgn = if (cx < w / 2) 1f else -1f
            for ((blur, a) in listOf(22f to 255, 10f to 255, 4f to 255)) {
                val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(Color.WHITE, a); maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                }
                for (r in 0 until 3) for (col in 0 until 4) {
                    c.drawCircle(cx + sgn * (col * 30f + r * 6f), 26f + r * 26f - col * 3f, 7f, dp)
                }
            }
        }

        // ── Pinselstriche an den Rändern ─────────────────────────────
        for (side in 0..1) for (k in 0 until 9) {
            val y = rnd.nextFloat(h * 0.02f, h * 0.9f)
            val x = if (side == 0) 0f else w
            val dx = rnd.nextFloat(60f, 170f) * if (side == 0) 1 else -1
            brushStroke(c, rnd, x - dx * 0.4f, y + rnd.nextFloat(50f, 130f), x + dx, y - rnd.nextFloat(30f, 110f),
                rnd.nextFloat(4f, 26f), if (k % 3 != 0) ACCENT else ACCENT_DARK, rnd.nextInt(70, 161))
        }

        // ── Logos + VS ───────────────────────────────────────────────
        val homeLogo = if (askHome) askLogo else input.opponentLogo
        val awayLogo = if (askHome) input.opponentLogo else askLogo
        drawLogo(c, homeLogo, 290f, logoCy, box, m.homeTeam)
        drawLogo(c, awayLogo, w - 290f, logoCy, box, m.awayTeam)
        brushStroke(c, rnd, w / 2 + 40f, logoCy - 120f, w / 2 - 40f, logoCy + 110f, 9f, ACCENT, 200)
        textBlock(c, rnd, "VS", w / 2, logoCy + 4f + 46f, fBrush, 140f, Color.WHITE, Paint.Align.CENTER, specks = 0, shadow = true, dry = 60)

        val tx = (w - titlePaint.measureText(p1 + p2)) / 2f
        c.save()
        c.rotate(-4f, w / 2f, titleBase)
        textBlock(c, rnd, p1, tx, titleBase, fBrush, titlePaint.textSize, Color.WHITE, Paint.Align.LEFT,
            specks = 0, shadow = true, dry = 170, bold = 4f)
        textBlock(c, rnd, p2, tx + titlePaint.measureText(p1), titleBase, fBrush, titlePaint.textSize,
            if (outcome == Outcome.LOSS) GREY else ACCENT, Paint.Align.LEFT, specks = 0, shadow = true, dry = 170, bold = 4f)
        brushStroke(c, rnd, 80f, titleBase + 36f, w - 80f, titleBase + 16f, 14f, ACCENT, 230)
        c.restore()

        // Ergebnis
        val cxHome = w / 2f - 190f
        val cxAway = w / 2f + 190f
        textBlock(c, rnd, m.homeGoals?.toString() ?: "-", cxHome, digitsBase, fHeavy, scoreSize,
            if (askHome) ACCENT else Color.WHITE, Paint.Align.CENTER, specks = 2600, shadow = true)
        textBlock(c, rnd, m.awayGoals?.toString() ?: "-", cxAway, digitsBase, fHeavy, scoreSize,
            if (askHome) Color.WHITE else ACCENT, Paint.Align.CENTER, specks = 2600, shadow = true)
        textBlock(c, rnd, ":", w / 2f, digitsBase - 34f, fHeavy, 145f, Color.WHITE, Paint.Align.CENTER, specks = 200, shadow = true)

        // Torschützen-Box
        if (hasBox) {
            val r = RectF(52f, boxTop, w - 52f, boxBottom)
            p.style = Paint.Style.FILL; p.color = withAlpha(Color.rgb(4, 16, 9), 200)
            c.drawRoundRect(r, 20f, 20f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = ACCENT
            c.drawRoundRect(r, 20f, 20f, p); p.style = Paint.Style.FILL
            val tp = textPaint(fCond, 34f, ACCENT).apply { textAlign = Paint.Align.CENTER }
            c.drawText("TORE FÜR ${input.ownShort.uppercase()}", w / 2f, boxTop + 46f, tp)
            var y = boxTop + 46f + 54f
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
            for (line in lines) {
                var x = w / 2f - lw(line) / 2f
                line.forEachIndexed { i, s ->
                    c.drawText(s, x, y, linePaint); x += linePaint.measureText(s)
                    if (i < line.lastIndex) {
                        val g = linePaint.measureText(sep)
                        c.drawCircle(x + g / 2f, y - linePaint.textSize * 0.37f, linePaint.textSize * 0.13f, dot); x += g
                    }
                }
                y += 54f
            }
        }

        // Hashtag
        if (input.hashtag.isNotBlank()) {
            val hp = textPaint(fTag, 64f, Color.WHITE).apply {
                textAlign = Paint.Align.CENTER; setShadowLayer(6f, 0f, 4f, withAlpha(Color.BLACK, 170))
            }
            while (hp.measureText(input.hashtag) > w - 120f && hp.textSize > 30f) hp.textSize -= 2f
            val tw = hp.measureText(input.hashtag)
            brushStroke(c, rnd, w / 2 - tw / 2 + 20f, tagBase + 14f, w / 2 + tw / 2 + 10f, tagBase + 6f, 7f, ACCENT, 220)
            c.drawText(input.hashtag, w / 2f, tagBase, hp)
        }
        return bmp
    }

    // ── Bausteine ───────────────────────────────────────────────────

    private fun textPaint(tf: Typeface, size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf; textSize = size; this.color = color
    }

    private fun Random.nextFloat(from: Float, until: Float) = from + nextFloat() * (until - from)

    private fun glow(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Int) {
        val g = Paint(Paint.ANTI_ALIAS_FLAG)
        g.shader = RadialGradient(cx, cy, radius,
            intArrayOf(withAlpha(color, alpha), withAlpha(color, alpha * 56 / 100), withAlpha(color, alpha / 4), withAlpha(color, 0)),
            floatArrayOf(0f, 0.25f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, radius, g)
    }

    /** Unregelmäßiger, an den Enden spitz zulaufender Pinselstrich. */
    private fun brushStroke(c: Canvas, rnd: Random, x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, alpha: Int) {
        val n = 14
        val ang = atan2(y1 - y0, x1 - x0)
        val nx = -sin(ang); val ny = cos(ang)
        val top = ArrayList<Pair<Float, Float>>(); val bot = ArrayList<Pair<Float, Float>>()
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = x0 + (x1 - x0) * t; val y = y0 + (y1 - y0) * t
            val taper = sin(PI * min(1.0, t * 1.15)).pow(0.6).toFloat()
            val wv = width * taper * (0.7f + 0.3f * rnd.nextFloat())
            top += (x + nx * wv / 2) to (y + ny * wv / 2)
            bot += (x - nx * wv / 2 + rnd.nextFloat(-2f, 2f)) to (y - ny * wv / 2)
        }
        val path = Path()
        (top + bot.reversed()).forEachIndexed { i, (px, py) -> if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
        path.close()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(color, alpha) })
    }

    /**
     * Text mit Schatten und "Grunge"-Struktur: in eigener Ebene zeichnen,
     * dann feine Sprenkel/Kratzer herauslöschen (DST_OUT).
     */
    private fun textBlock(
        c: Canvas, rnd: Random, text: String, x: Float, baseline: Float, tf: Typeface, size: Float, color: Int,
        align: Paint.Align, specks: Int, shadow: Boolean, dry: Int = 0, bold: Float = 0f,
    ) {
        val tp = textPaint(tf, size, color).apply {
            textAlign = align
            if (bold > 0f) { style = Paint.Style.FILL_AND_STROKE; strokeWidth = bold; strokeJoin = Paint.Join.ROUND }
        }
        val b = Rect(); tp.getTextBounds(text, 0, text.length, b)
        val width = tp.measureText(text)
        val left = when (align) { Paint.Align.CENTER -> x - width / 2; Paint.Align.RIGHT -> x - width; else -> x }
        val area = RectF(left - 30f, baseline + b.top - 30f, left + width + 30f, baseline + b.bottom + 40f)

        if (shadow) {
            val sp = textPaint(tf, size, withAlpha(Color.BLACK, 190)).apply {
                textAlign = align; maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
                if (bold > 0f) { style = Paint.Style.FILL_AND_STROKE; strokeWidth = bold }
            }
            c.drawText(text, x, baseline + 8f, sp)
        }
        val layer = c.saveLayer(area, null)
        c.drawText(text, x, baseline, tp)
        if (specks > 0) {
            val er = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
            val radii = floatArrayOf(0.6f, 0.8f, 1f, 1f, 1.3f, 1.8f)
            // specks = Anzahl bezogen auf eine ganze 1080×1350-Fläche → auf die Textfläche umrechnen
            val n = (specks * (area.width() * area.height()) / (1080f * 1350f)).toInt().coerceAtLeast(1)
            repeat(n) {
                er.alpha = rnd.nextInt(150, 256)
                c.drawCircle(rnd.nextFloat(area.left, area.right), rnd.nextFloat(area.top, area.bottom), radii[rnd.nextInt(radii.size)], er)
            }
            er.strokeWidth = 1f; er.style = Paint.Style.STROKE
            repeat(n / 12) {
                val sx = rnd.nextFloat(area.left, area.right); val sy = rnd.nextFloat(area.top, area.bottom)
                val l = rnd.nextFloat(6f, 28f); val a = rnd.nextFloat(-0.6f, 0.6f)
                er.alpha = 200
                c.drawLine(sx, sy, sx + l * cos(a), sy + l * sin(a), er)
            }
        }
        if (dry > 0) dryBrush(c, rnd, tp, text, x, baseline, area, dry)
        c.restoreToCount(layer)
    }

    /**
     * Trockenpinsel-Look: feine Streifen in Strichrichtung (kursiv, steil) und
     * kurze Querstriche an zufälligen Punkten der Buchstabenkontur (ausgefranste Kanten).
     * [dry] = Anzahl Streifen bezogen auf eine 1080×230-Titelzeile.
     */
    private fun dryBrush(c: Canvas, rnd: Random, tp: Paint, text: String, x: Float, baseline: Float, area: RectF, dry: Int) {
        val er = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT); style = Paint.Style.STROKE; strokeWidth = 1f
        }
        val n = (dry * (area.width() * area.height()) / (1080f * 230f)).toInt().coerceAtLeast(1)
        val angles = floatArrayOf(-76f, -76f, -72f, -80f, -6f)
        repeat(n) {
            val sx = rnd.nextFloat(area.left, area.right); val sy = rnd.nextFloat(area.top, area.bottom)
            val a = Math.toRadians(angles[rnd.nextInt(angles.size)].toDouble()).toFloat()
            val l = rnd.nextFloat(14f, 60f)
            c.drawLine(sx, sy, sx + l * cos(a), sy + l * sin(a), er)
        }
        er.style = Paint.Style.FILL
        val radii = floatArrayOf(0.7f, 1f, 1.3f)
        repeat((n * 0.6f).toInt()) {
            c.drawCircle(rnd.nextFloat(area.left, area.right), rnd.nextFloat(area.top, area.bottom), radii[rnd.nextInt(3)], er)
        }
        // Kontur abtasten und kurze waagrechte "Fransen" herauslöschen
        val outline = Path()
        val plain = Paint(tp).apply { style = Paint.Style.FILL }
        val startX = when (tp.textAlign) {
            Paint.Align.CENTER -> x - plain.measureText(text) / 2f
            Paint.Align.RIGHT -> x - plain.measureText(text)
            else -> x
        }
        plain.textAlign = Paint.Align.LEFT
        plain.getTextPath(text, 0, text.length, startX, baseline, outline)
        er.style = Paint.Style.STROKE
        val pm = android.graphics.PathMeasure(outline, false)
        val pos = FloatArray(2)
        do {
            val len = pm.length
            var d = 0f
            while (d < len) {
                if (rnd.nextFloat() < 0.24f) {
                    pm.getPosTan(d, pos, null)
                    val a = Math.toRadians((if (rnd.nextBoolean()) -7f else 175f).toDouble()).toFloat()
                    val l = rnd.nextFloat(4f, 14f)
                    er.strokeWidth = if (rnd.nextBoolean()) 1f else 2f
                    c.drawLine(pos[0], pos[1], pos[0] + l * cos(a), pos[1] + l * sin(a), er)
                }
                d += 4f
            }
        } while (pm.nextContour())
    }

    /**
     * Zeichnet das Foto so, dass alle Gesichter zwischen [safeTop] und [safeBot] liegen.
     * Darunter liegt eine weichgezeichnete, abgedunkelte Kopie, die das ganze Band füllt
     * (wichtig, wenn das scharfe Foto das Band nicht ganz abdecken kann, z. B. im Story-Format).
     */
    private fun drawPhoto(
        c: Canvas, photo: Bitmap, faces: List<PhotoPlacement.Box>, w: Float,
        bandTop: Float, bandBot: Float, safeTop: Float, safeBot: Float,
    ) {
        val bandH = bandBot - bandTop
        // 1) Hintergrund: stark verkleinert und wieder vergrößert = weich
        run {
            val small = Bitmap.createScaledBitmap(photo, max(1, photo.width / 24), max(1, photo.height / 24), true)
            val layer = c.saveLayer(0f, bandTop, w, bandBot, null)
            drawCenterCrop(c, small, RectF(0f, bandTop, w, bandBot), 0.4f)
            c.drawRect(0f, bandTop, w, bandBot, Paint().apply { color = withAlpha(Color.rgb(5, 18, 10), 175) })
            val mask = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                shader = LinearGradient(0f, bandTop, 0f, bandBot,
                    intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                    floatArrayOf(0f, min(0.45f, 150f / bandH), max(0.55f, 1f - 300f / bandH), 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, bandTop, w, bandBot, mask)
            c.restoreToCount(layer)
            small.recycle()
        }
        // 2) scharfes Foto, gesichtsbewusst platziert
        val pl = PhotoPlacement.place(photo.width.toFloat(), photo.height.toFloat(), faces, w, bandTop, bandBot, safeTop, safeBot)
        val pw = photo.width * pl.scale; val ph = photo.height * pl.scale
        val visTop = max(bandTop, pl.ty); val visBot = min(bandBot, pl.ty + ph)
        if (visBot - visTop < 10f) return
        val fTop = faces.minOfOrNull { it.y0 * pl.scale + pl.ty } ?: (visTop + 150f)
        val fBot = faces.maxOfOrNull { it.y1 * pl.scale + pl.ty } ?: (visBot - 260f)
        val fadeT = (fTop - visTop).coerceIn(30f, 150f)
        val fadeBStart = min(max(fBot, visBot - 260f), visBot - 120f).coerceAtLeast(visTop + fadeT)
        val layer = c.saveLayer(0f, visTop, w, visBot, null)
        c.drawBitmap(photo, null, RectF(pl.tx, pl.ty, pl.tx + pw, pl.ty + ph), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val vh = visBot - visTop
        val vMask = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(0f, visTop, 0f, visBot,
                intArrayOf(Color.TRANSPARENT, withAlpha(Color.BLACK, 120), Color.BLACK, Color.BLACK, withAlpha(Color.BLACK, 110), Color.TRANSPARENT),
                floatArrayOf(0f, fadeT * 0.55f / vh, fadeT / vh, (fadeBStart - visTop) / vh,
                    ((fadeBStart - visTop) / vh + 1f) / 2f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, visTop, w, visBot, vMask)
        if (pw < w) { // Foto schmäler als das Bild: Seiten weich ausblenden
            val edge = min(80f, pw / 4f)
            val hMask = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                shader = LinearGradient(pl.tx, 0f, pl.tx + pw, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                    floatArrayOf(0f, edge / pw, 1f - edge / pw, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, visTop, w, visBot, hMask)
        }
        c.restoreToCount(layer)
    }

    private fun drawCenterCrop(c: Canvas, src: Bitmap, dst: RectF, focusY: Float) {
        val scale = max(dst.width() / src.width, dst.height() / src.height)
        val cropW = dst.width() / scale; val cropH = dst.height() / scale
        val left = (src.width - cropW) / 2f
        val top = ((src.height - cropH) * focusY).coerceIn(0f, max(0f, src.height - cropH))
        c.drawBitmap(src, Rect(left.toInt(), top.toInt(), (left + cropW).toInt(), (top + cropH).toInt()), dst,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    /** Logo mit weichem Schatten; ohne Logo ein weißer Kreis mit Initialen. */
    private fun drawLogo(c: Canvas, logo: Bitmap?, cx: Float, cy: Float, box: Float, teamName: String) {
        if (logo != null) {
            val s = min(box / logo.width, box / logo.height)
            val lw = logo.width * s; val lh = logo.height * s
            val dst = RectF(cx - lw / 2, cy - lh / 2, cx + lw / 2, cy + lh / 2)
            val blur = Paint().apply { maskFilter = BlurMaskFilter(14f / s, BlurMaskFilter.Blur.NORMAL) }
            val offset = IntArray(2)
            val alpha = logo.extractAlpha(blur, offset)
            val sh = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = withAlpha(Color.BLACK, 190) }
            c.drawBitmap(alpha, null,
                RectF(dst.left + offset[0] * s, dst.top + offset[1] * s + 10f,
                    dst.left + (offset[0] + alpha.width) * s, dst.top + (offset[1] + alpha.height) * s + 10f), sh)
            alpha.recycle()
            c.drawBitmap(logo, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            val r = box * 0.46f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(14f, 0f, 10f, withAlpha(Color.BLACK, 190)) }
            c.drawCircle(cx, cy, r, p)
            p.clearShadowLayer(); p.style = Paint.Style.STROKE; p.strokeWidth = 8f; p.color = ACCENT
            c.drawCircle(cx, cy, r - 4f, p)
            val initials = teamName.split(Regex("""\s+""")).filter { it.isNotEmpty() }.take(2)
                .joinToString("") { it.first().uppercase() }.ifBlank { "?" }
            val tp = textPaint(fHeavy, box * 0.3f, BG).apply { textAlign = Paint.Align.CENTER }
            val b = Rect(); tp.getTextBounds(initials, 0, initials.length, b)
            c.drawText(initials, cx, cy - b.exactCenterY(), tp)
        }
    }
}
