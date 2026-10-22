package at.askmannersdorf.ergebnis.flyer

import kotlin.math.max
import kotlin.math.min

/**
 * Berechnet, wie das Mannschaftsfoto skaliert und verschoben wird, damit alle Gesichter
 * im freien Bereich [safeTop, safeBot] liegen (zwischen Logos und Titel) und nicht vom Text
 * verdeckt werden. Reine Rechnung ohne Android-Klassen, damit sie testbar ist.
 */
object PhotoPlacement {

    /** Rechteck in Fotokoordinaten (Gesicht inkl. Haare/Kinn). */
    data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    data class Placement(val scale: Float, val tx: Float, val ty: Float)

    /**
     * Gesicht aus Augenmittelpunkt + Augenabstand (android.media.FaceDetector) großzügig umschließen.
     */
    fun faceBox(midX: Float, midY: Float, eyesDistance: Float) = Box(
        midX - 1.2f * eyesDistance, midY - 1.6f * eyesDistance,
        midX + 1.2f * eyesDistance, midY + 2.0f * eyesDistance,
    )

    fun place(
        pw: Float, ph: Float, faces: List<Box>, w: Float,
        bandTop: Float, bandBot: Float, safeTop: Float, safeBot: Float, margin: Float = 24f,
    ): Placement {
        val bandH = bandBot - bandTop
        val sCover = max(w / pw, bandH / ph)
        if (faces.isEmpty() || safeBot - safeTop < 40f) {
            return Placement(sCover, (w - pw * sCover) / 2f, bandTop - (ph * sCover - bandH) * 0.4f)
        }
        val ux0 = faces.minOf { it.x0 }; val uy0 = faces.minOf { it.y0 }
        val ux1 = faces.maxOf { it.x1 }; val uy1 = faces.maxOf { it.y1 }
        val sFaces = min((safeBot - safeTop) / max(1f, uy1 - uy0), (w - 2 * margin) / max(1f, ux1 - ux0))
        var s = min(sCover, sFaces)

        // Kann das Foto das Band füllen, ohne Gesichter in den Text zu schieben?
        fun covers(sc: Float): Boolean {
            val lo = max(safeBot - uy1 * sc, bandBot - ph * sc)
            val hi = min(safeTop - uy0 * sc, bandTop)
            return lo <= hi
        }
        if (s >= sCover && !covers(s)) {
            var t = s
            while (t <= min(sFaces, sCover * 2.5f)) {
                if (covers(t)) { s = t; break }
                t *= 1.02f
            }
        }

        // Gesichter haben Vorrang; Abdeckung des Bandes nur, wenn sie damit vereinbar ist
        fun fit(target: Float, loF: Float, hiF: Float, loC: Float, hiC: Float): Float {
            val lo = max(loF, loC); val hi = min(hiF, hiC)
            return if (lo <= hi) target.coerceIn(lo, hi) else target.coerceIn(min(loF, hiF), max(loF, hiF))
        }
        val ty = fit(
            (safeTop + safeBot) / 2f - (uy0 + uy1) / 2f * s,
            safeBot - uy1 * s, safeTop - uy0 * s, bandBot - ph * s, bandTop,
        )
        val tx = fit(
            (w - pw * s) / 2f,
            w - margin - ux1 * s, margin - ux0 * s, w - pw * s, 0f,
        )
        return Placement(s, tx, ty)
    }
}
