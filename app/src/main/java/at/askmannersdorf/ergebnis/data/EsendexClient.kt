package at.askmannersdorf.ergebnis.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SmsGroup(val id: String, val name: String, val count: Int?)

data class SendResult(val ok: Boolean, val message: String, val totalSent: Int? = null)

class EsendexException(msg: String) : Exception(msg)

/**
 * REST-Client für das Esendex-Portal (app.esendex.de, API v1.0).
 * Ablauf: login -> user_key + Session_key -> Aufrufe mit diesen Headern.
 */
class EsendexClient(private val s: AppSettings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val base = s.esendexBaseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }

    private var userKey: String? = null
    private var sessionKey: String? = null

    private suspend fun login() = withContext(Dispatchers.IO) {
        if (s.esendexUser.isBlank() || s.esendexPassword.isBlank()) {
            throw EsendexException("Bitte Esendex-Benutzer und Passwort in den Einstellungen eintragen.")
        }
        val url = (base + "login").toHttpUrl().newBuilder()
            .addQueryParameter("username", s.esendexUser)
            .addQueryParameter("password", s.esendexPassword)
            .build()
        val req = Request.Builder().url(url)
            .header("Authorization", Credentials.basic(s.esendexUser, s.esendexPassword))
            .get().build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty().trim()
            if (!r.isSuccessful) {
                throw EsendexException(
                    if (r.code == 401 || r.code == 404) "Esendex-Login fehlgeschlagen: Benutzer oder Passwort falsch."
                    else "Esendex-Login fehlgeschlagen (HTTP ${r.code}): ${body.take(200)}"
                )
            }
            val parts = body.split(";")
            if (parts.size < 2) throw EsendexException("Unerwartete Antwort beim Login: ${body.take(200)}")
            userKey = parts[0].trim(); sessionKey = parts[1].trim()
        }
    }

    private fun authed(b: Request.Builder): Request.Builder =
        b.header("user_key", userKey!!).header("Session_key", sessionKey!!)

    private suspend fun ensureLogin() { if (userKey == null) login() }

    /** Verfügbare SMS-Guthaben als lesbarer Text, z. B. "N: 1200". */
    suspend fun credits(): String = withContext(Dispatchers.IO) {
        ensureLogin()
        val req = authed(Request.Builder().url(base + "status")).get().build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw EsendexException("Guthaben-Abfrage fehlgeschlagen (HTTP ${r.code})")
            val arr = JSONObject(body).optJSONArray("sms") ?: return@use "unbekannt"
            (0 until arr.length()).joinToString(", ") {
                val o = arr.getJSONObject(it)
                "${o.optString("type")}: ${o.optInt("quantity")}"
            }
        }
    }

    /** Kontaktgruppen aus dem Esendex-Adressbuch. */
    suspend fun groups(): List<SmsGroup> = withContext(Dispatchers.IO) {
        ensureLogin()
        val all = mutableListOf<SmsGroup>()
        var page = 1
        while (page <= 20) {
            val url = (base + "groups").toHttpUrl().newBuilder()
                .addQueryParameter("pageNumber", page.toString())
                .addQueryParameter("pageSize", "100")
                .build()
            val req = authed(Request.Builder().url(url)).get().build()
            val batch = http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw EsendexException("Gruppen konnten nicht geladen werden (HTTP ${r.code}): ${body.take(200)}")
                parseGroups(body)
            }
            all += batch
            if (batch.size < 100) break
            page++
        }
        all
    }

    private fun parseGroups(body: String): List<SmsGroup> {
        val trimmed = body.trim()
        val arr: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).let { it.optJSONArray("groups") ?: it.optJSONArray("data") ?: JSONArray() }
        }
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val id = listOf("id_group", "idGroup", "id", "group_id").firstNotNullOfOrNull { k ->
                o.optString(k).takeIf { v -> v.isNotBlank() && v != "null" }
            } ?: return@mapNotNull null
            val name = o.optString("name").ifBlank { o.optString("description") }.ifBlank { id }
            val count = listOf("total", "contacts", "count", "total_contacts").firstNotNullOfOrNull { k ->
                if (o.has(k)) o.optInt(k) else null
            }
            SmsGroup(id, name, count)
        }
    }

    /** SMS an eine oder mehrere Kontaktgruppen. */
    suspend fun sendToGroups(text: String, groupIds: List<String>): SendResult =
        send("smstogroups", text, groupIds)

    /** SMS an einzelne Nummern (z. B. Test an die eigene Nummer). */
    suspend fun sendToNumbers(text: String, numbers: List<String>): SendResult =
        send("sms", text, numbers.map(::normalizeNumber))

    private suspend fun send(endpoint: String, text: String, recipients: List<String>): SendResult =
        withContext(Dispatchers.IO) {
            ensureLogin()
            val payload = JSONObject()
                .put("message_type", s.smsQuality.ifBlank { "N" })
                .put("message", text)
                .put("recipient", JSONArray(recipients))
                .put("returnCredits", true)
            if (s.smsSender.isNotBlank() && s.smsQuality == "N") payload.put("sender", s.smsSender)

            val req = authed(Request.Builder().url(base + endpoint))
                .post(payload.toString().toRequestBody(json)).build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    return@use SendResult(false, "Versand fehlgeschlagen (HTTP ${r.code}): ${body.take(300)}")
                }
                val o = runCatching { JSONObject(body) }.getOrNull()
                val ok = o == null || o.optString("result", "OK").equals("OK", true)
                val sent = o?.optInt("total_sent")
                val credits = o?.opt("remaining_credits")
                SendResult(
                    ok,
                    if (ok) "SMS versendet" + (sent?.let { " an $it Empfänger" } ?: "") +
                        (credits?.let { ". Restguthaben: $it" } ?: "") + "."
                    else "Esendex meldet: ${body.take(300)}",
                    sent,
                )
            }
        }

    private fun normalizeNumber(n: String): String {
        val digits = n.filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+") -> digits
            digits.startsWith("00") -> "+" + digits.drop(2)
            digits.startsWith("0") -> "+43" + digits.drop(1)
            else -> digits
        }
    }
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
