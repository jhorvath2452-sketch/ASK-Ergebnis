package at.askmannersdorf.ergebnis.data

import android.content.Context

/** Einstellungen, lokal am Gerät gespeichert. */
data class AppSettings(
    /** Esendex Messaging API v2 (Deutschland) */
    val esendexApiUrl: String = "https://api.esendex.de/v2/messages",
    val esendexApiKey: String = "",
    /** Kontonummer, z. B. EXDE0000000 */
    val esendexAccount: String = "",
    /** Absender (optional; sonst Standard-Absender aus dem Esendex-Portal) */
    val smsSender: String = "",
    /** Empfänger-Nummern, eine pro Zeile */
    val smsRecipients: String = "",
    /** Öffentliche LMO-Ligaseite (Hauptquelle für Ergebnisse). */
    val lmoUrl: String = "https://askmannersdorf.at/LMO_2026/liga.php?id=1",
    val oefbWidgetKey: String = "e19268845f",
    /** Domain der Vereinsseite, auf der das ÖFB-Widget eingebunden ist. */
    val oefbEmbedOrigin: String = "https://www.askmannersdorf.at",
    /** Spielplan auf ligaportal.at (Hauptquelle für Ergebnis + Torschützen). */
    val ligaportalUrl: String = "https://ticker.ligaportal.at/mannschaft/1323/ask-mannersdorf/spielplan",
    /** Woran der eigene Verein im Teamnamen erkannt wird. */
    val clubKeyword: String = "Mannersdorf",
    val clubDisplayName: String = "ASK Mannersdorf",
    /** Kurzname des eigenen Vereins in der SMS. */
    val smsShortName: String = "ASK",
    val hashtag: String = "#manaschdooaaf",
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            esendexApiUrl = prefs.getString("esendexApiUrl", d.esendexApiUrl)!!.ifBlank { d.esendexApiUrl },
            esendexApiKey = prefs.getString("esendexApiKey", d.esendexApiKey)!!,
            esendexAccount = prefs.getString("esendexAccount", d.esendexAccount)!!,
            smsSender = prefs.getString("smsSender2", d.smsSender)!!,
            smsRecipients = prefs.getString("smsRecipients", d.smsRecipients)!!,
            lmoUrl = prefs.getString("lmoUrl", d.lmoUrl)!!.ifBlank { d.lmoUrl },
            oefbWidgetKey = prefs.getString("oefbWidgetKey", d.oefbWidgetKey)!!,
            oefbEmbedOrigin = prefs.getString("oefbEmbedOrigin", d.oefbEmbedOrigin)!!,
            ligaportalUrl = prefs.getString("ligaportalUrl", d.ligaportalUrl)!!.ifBlank { d.ligaportalUrl },
            clubKeyword = prefs.getString("clubKeyword", d.clubKeyword)!!,
            clubDisplayName = prefs.getString("clubDisplayName", d.clubDisplayName)!!,
            smsShortName = prefs.getString("smsShortName", d.smsShortName)!!,
            hashtag = prefs.getString("hashtag", d.hashtag)!!.let { if (it == "#ASKMANNERSDORF") d.hashtag else it },
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("esendexApiUrl", s.esendexApiUrl.trim())
            .putString("esendexApiKey", s.esendexApiKey.trim())
            .putString("esendexAccount", s.esendexAccount.trim().uppercase())
            .putString("smsSender2", s.smsSender.trim())
            .putString("smsRecipients", s.smsRecipients)
            .putString("lmoUrl", s.lmoUrl.trim())
            .putString("oefbWidgetKey", s.oefbWidgetKey.trim())
            .putString("oefbEmbedOrigin", s.oefbEmbedOrigin.trim().trimEnd('/'))
            .putString("ligaportalUrl", s.ligaportalUrl.trim())
            .putString("clubKeyword", s.clubKeyword.trim())
            .putString("clubDisplayName", s.clubDisplayName.trim())
            .putString("smsShortName", s.smsShortName.trim())
            .putString("hashtag", s.hashtag.trim())
            .apply()
    }
}
