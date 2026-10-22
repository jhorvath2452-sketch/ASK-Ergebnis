package at.askmannersdorf.ergebnis.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SendResult(val ok: Boolean, val message: String, val accepted: Int? = null)

class EsendexException(msg: String) : Exception(msg)

/**
 * Esendex Messaging API v2 (Deutschland):
 * POST https://api.esendex.de/v2/messages, Header X-Api-Key, Konto "EXDE…".
 * Empfänger als Nummernliste ("recipients"), max. 50 000 pro Anfrage.
 */
class EsendexClient(private val s: AppSettings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun send(text: String, numbers: List<String>, name: String = "Ergebnis-SMS"): SendResult =
        withContext(Dispatchers.IO) {
            if (s.esendexApiKey.isBlank() || s.esendexAccount.isBlank()) {
                throw EsendexException("Bitte API-Schlüssel und Kontonummer (EXDE…) in den Einstellungen eintragen.")
            }
            val recipients = numbers.map(PhoneNumbers::normalize).filter { it.isNotBlank() }.distinct()
            if (recipients.isEmpty()) throw EsendexException("Keine gültigen Empfänger-Nummern.")
            if (recipients.size > 50_000) throw EsendexException("Zu viele Empfänger (max. 50 000).")

            val payload = JSONObject()
                .put("accountReference", s.esendexAccount.trim())
                .put("name", name)
                .put("channel", "SMS")
                .put("characterSet", "Auto")
                .put("messageType", "NotificationsAndReminders")
                .put("body", JSONObject().put("text", text))
                .put("recipients", JSONArray(recipients.map { JSONObject().put("msisdn", it) }))
            if (s.smsSender.isNotBlank()) payload.put("from", s.smsSender.trim())

            val req = Request.Builder()
                .url(s.esendexApiUrl.trim())
                .header("X-Api-Key", s.esendexApiKey.trim())
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(json))
                .build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.code == 202 || r.isSuccessful -> {
                        val ids = runCatching { JSONObject(body).getJSONObject("data").optJSONArray("messageIds")?.length() }.getOrNull()
                        SendResult(true, "SMS an ${ids ?: recipients.size} Empfänger übergeben.", ids ?: recipients.size)
                    }
                    body.trimStart().startsWith("<") -> SendResult(false,
                        "Esendex: Unter der API-Adresse antwortet eine Webseite statt der Schnittstelle (HTTP ${r.code}). Bitte API-Adresse prüfen.")
                    r.code == 401 -> SendResult(false, "Esendex: API-Schlüssel ungültig oder nicht berechtigt (401).")
                    else -> SendResult(false, "Esendex (HTTP ${r.code}): ${errorText(body)}")
                }
            }
        }

    private fun errorText(body: String): String = runCatching {
        val arr = JSONObject(body).getJSONArray("errors")
        (0 until arr.length()).joinToString("; ") {
            val e = arr.getJSONObject(it)
            e.optString("errorMessage").ifBlank { e.optString("errorCode") }
        }
    }.getOrNull()?.ifBlank { null } ?: body.take(300)
}

/** Zählt SMS-Teile (GSM-7: 160/153 Zeichen, sonst Unicode: 70/67). */
object SmsCounter {
    private const val GSM =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val GSM_EXT = "^{}\\[~]|€"

    fun count(text: String): Pair<Int, Int> {
        val isGsm = text.all { it in GSM || it in GSM_EXT }
        return if (isGsm) {
            val len = text.sumOf { if (it in GSM_EXT) 2L else 1L }.toInt()
            len to if (len <= 160) 1 else (len + 152) / 153
        } else {
            val len = text.length
            len to if (len <= 70) 1 else (len + 66) / 67
        }
    }
}
