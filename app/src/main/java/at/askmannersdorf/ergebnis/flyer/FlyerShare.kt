package at.askmannersdorf.ergebnis.flyer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

enum class ShareTarget(val label: String, val pkg: String?) {
    /** Meta Business Suite: ein Beitrag/Story für Facebook-Seite und Instagram gleichzeitig. */
    BUSINESS_SUITE("Business Suite", "com.facebook.pages.app"),
    WHATSAPP("WhatsApp", "com.whatsapp"),
    INSTAGRAM("Instagram", "com.instagram.android"),
    FACEBOOK("Facebook", "com.facebook.katana"),
    OTHER("Andere …", null),
}

object FlyerShare {

    private fun cacheFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, "flyer").apply { mkdirs() }
        return File(dir, name)
    }

    suspend fun toShareUri(context: Context, bmp: Bitmap, name: String): Uri = withContext(Dispatchers.IO) {
        val f = cacheFile(context, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }

    /**
     * Öffnet direkt die gewählte App bzw. den Teilen-Dialog.
     * Gibt false zurück, wenn die gewählte App fehlt – dann wird der Teilen-Dialog geöffnet.
     */
    fun share(context: Context, uri: Uri, target: ShareTarget, caption: String? = null): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!caption.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (target.pkg != null) {
            send.setPackage(target.pkg)
            return try {
                context.startActivity(send); true
            } catch (e: Exception) {
                send.setPackage(null)
                context.startActivity(Intent.createChooser(send, "Flyer teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                false
            }
        }
        context.startActivity(Intent.createChooser(send, "Flyer teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Startet eine installierte App (z. B. Meta Business Suite). false, wenn sie fehlt. */
    fun launchApp(context: Context, pkg: String): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) {
            false
        }
    }

    /** Speichert den Flyer in der Galerie (Ordner Bilder/ASK Ergebnis). */
    suspend fun saveToGallery(context: Context, bmp: Bitmap, name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ASK Ergebnis")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ASK Ergebnis").apply { mkdirs() }
                FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Lädt ein Foto aus dem Bildauswahl-Dialog, verkleinert auf max. [maxSide] px, richtig gedreht. */
    suspend fun loadImage(context: Context, uri: Uri, maxSide: Int = 2400): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    val w = info.size.width; val h = info.size.height
                    val scale = maxSide.toFloat() / maxOf(w, h)
                    if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Lädt ein Logo aus dem Internet (z. B. Wappen aus dem ÖFB-Widget). */
    suspend fun downloadImage(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        } catch (e: Exception) {
            null
        }
    }
}
