package at.askmannersdorf.ergebnis.data

import android.content.Context

/** Einstellungen, lokal am Gerät gespeichert. */
data class AppSettings(
    val esendexBaseUrl: String = "https://app.esendex.de/API/v1.0/REST/",
    val esendexUser: String = "",
    val esendexPassword: String = "",
    val smsSender: String = "ASK",
    /** N = hohe Qualität (mit Absender), L = mittel, LL = niedrig */
    val smsQuality: String = "N",
    val lastGroupId: String = "",
    val oefbWidgetKey: String = "e19268845f",
    /** Domain der Vereinsseite, auf der das ÖFB-Widget eingebunden ist. */
    val oefbEmbedOrigin: String = "https://www.askmannersdorf.at",
    /** Teamseite auf ligaportal.at (Rückfallebene). */
    val ligaportalUrl: String = "",
    /** Woran der eigene Verein im Teamnamen erkannt wird. */
    val clubKeyword: String = "Mannersdorf",
    val clubDisplayName: String = "ASK Mannersdorf",
    val hashtag: String = "#ASKMANNERSDORF",
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            esendexBaseUrl = prefs.getString("esendexBaseUrl", d.esendexBaseUrl)!!,
            esendexUser = prefs.getString("esendexUser", d.esendexUser)!!,
            esendexPassword = prefs.getString("esendexPassword", d.esendexPassword)!!,
            smsSender = prefs.getString("smsSender", d.smsSender)!!,
            smsQuality = prefs.getString("smsQuality", d.smsQuality)!!,
            lastGroupId = prefs.getString("lastGroupId", d.lastGroupId)!!,
            oefbWidgetKey = prefs.getString("oefbWidgetKey", d.oefbWidgetKey)!!,
            oefbEmbedOrigin = prefs.getString("oefbEmbedOrigin", d.oefbEmbedOrigin)!!,
            ligaportalUrl = prefs.getString("ligaportalUrl", d.ligaportalUrl)!!,
            clubKeyword = prefs.getString("clubKeyword", d.clubKeyword)!!,
            clubDisplayName = prefs.getString("clubDisplayName", d.clubDisplayName)!!,
            hashtag = prefs.getString("hashtag", d.hashtag)!!,
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("esendexBaseUrl", s.esendexBaseUrl.trim())
            .putString("esendexUser", s.esendexUser.trim())
            .putString("esendexPassword", s.esendexPassword)
            .putString("smsSender", s.smsSender.trim())
            .putString("smsQuality", s.smsQuality.trim())
            .putString("lastGroupId", s.lastGroupId)
            .putString("oefbWidgetKey", s.oefbWidgetKey.trim())
            .putString("oefbEmbedOrigin", s.oefbEmbedOrigin.trim().trimEnd('/'))
            .putString("ligaportalUrl", s.ligaportalUrl.trim())
            .putString("clubKeyword", s.clubKeyword.trim())
            .putString("clubDisplayName", s.clubDisplayName.trim())
            .putString("hashtag", s.hashtag.trim())
            .apply()
    }
}
