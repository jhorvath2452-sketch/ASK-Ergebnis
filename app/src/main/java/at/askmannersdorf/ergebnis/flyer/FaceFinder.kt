package at.askmannersdorf.ergebnis.flyer

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector

/**
 * Findet Gesichter im Mannschaftsfoto mit dem in Android eingebauten Detektor
 * (offline, keine Zusatzbibliothek). Liefert Rechtecke in Koordinaten des Originalfotos.
 */
object FaceFinder {

    fun find(photo: Bitmap, maxFaces: Int = 40): List<PhotoPlacement.Box> = try {
        // Detektor braucht RGB_565 und gerade Breite; verkleinern spart Zeit
        val scale = minOf(1f, 1000f / photo.width)
        var dw = (photo.width * scale).toInt().coerceAtLeast(2)
        if (dw % 2 == 1) dw -= 1
        val dh = (photo.height * scale).toInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(photo, dw, dh, true)
        val rgb = small.copy(Bitmap.Config.RGB_565, false)
        if (small != photo) small.recycle()
        val faces = arrayOfNulls<FaceDetector.Face>(maxFaces)
        val n = FaceDetector(dw, dh, maxFaces).findFaces(rgb, faces)
        rgb.recycle()
        val sx = photo.width.toFloat() / dw
        val sy = photo.height.toFloat() / dh
        val mid = PointF()
        (0 until n).mapNotNull { i ->
            val f = faces[i] ?: return@mapNotNull null
            if (f.confidence() < 0.3f) return@mapNotNull null
            f.getMidPoint(mid)
            PhotoPlacement.faceBox(mid.x * sx, mid.y * sy, f.eyesDistance() * sx)
        }
    } catch (t: Throwable) {
        emptyList()
    }
}
