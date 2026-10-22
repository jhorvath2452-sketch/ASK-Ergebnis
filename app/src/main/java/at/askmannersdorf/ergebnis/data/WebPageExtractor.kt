package at.askmannersdorf.ergebnis.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class PageImage(val src: String, val alt: String)
data class PageLink(val href: String, val text: String)

/** Eine Tabellenzeile: Zelltexte, Links und Bilder darin. */
data class PageRow(val cells: List<String>, val links: List<String>, val imgs: List<String>)

/** Text, Bilder und Links eines geladenen Frames (auch aus iFrames). */
data class PageSnapshot(
    val url: String,
    val text: String,
    val images: List<PageImage>,
    val links: List<PageLink>,
    val rows: List<PageRow> = emptyList(),
)

/**
 * Lädt Seiten in einer unsichtbaren WebView und liest den fertig gerenderten Inhalt aus.
 * Das ist nötig, weil ÖFB-Widget und Ligaportal ihre Daten erst per JavaScript nachladen.
 * Ein Skript wird in JEDEN Frame eingefügt (auch in das ÖFB-iFrame) und schickt
 * im Sekundentakt den sichtbaren Text an die App zurück.
 */
class WebPageExtractor(private val context: Context) {

    private val snapshotScript = """
        (function(){
          if (window.__askSnap) return; window.__askSnap = true;
          var tries = 0;
          function snap(){
            tries++;
            try {
              var t = (document.body && document.body.innerText) || '';
              var imgs = [].slice.call(document.images).map(function(i){
                return {src: i.currentSrc || i.src || '', alt: i.alt || i.title || ''};
              });
              var links = [].slice.call(document.links).slice(0, 400).map(function(a){
                return {href: a.href || '', text: (a.innerText || '').trim().slice(0, 200)};
              });
              var rows = [].slice.call(document.querySelectorAll('tr')).slice(0, 300).map(function(tr){
                return {
                  cells: [].slice.call(tr.children).map(function(td){ return (td.innerText || '').trim(); }),
                  links: [].slice.call(tr.querySelectorAll('a')).map(function(a){ return a.href || ''; }),
                  imgs: [].slice.call(tr.querySelectorAll('img')).map(function(i){ return i.currentSrc || i.src || ''; })
                };
              });
              if (window.AskBridge) {
                AskBridge.postMessage(JSON.stringify({url: location.href, text: t, imgs: imgs, links: links, rows: rows}));
              }
            } catch (e) {}
            if (tries < 30) setTimeout(snap, 1000);
          }
          if (document.readyState === 'complete') setTimeout(snap, 500);
          else window.addEventListener('load', function(){ setTimeout(snap, 500); });
        })();
    """.trimIndent()

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    /**
     * Lädt eine Seite (per URL oder als HTML mit Basis-URL) und wartet, bis [accept]
     * einen Snapshot akzeptiert – oder bis [timeoutMs] abgelaufen ist.
     * Gibt den akzeptierten Snapshot zurück, sonst alle gesammelten Snapshots im Fehlerfall.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun load(
        url: String? = null,
        html: String? = null,
        baseUrl: String? = null,
        timeoutMs: Long = 25_000,
        accept: (PageSnapshot) -> Boolean,
    ): ExtractResult = withContext(Dispatchers.Main) {
        if (!isSupported) {
            return@withContext ExtractResult(null, emptyList(), "Die WebView auf diesem Gerät ist zu alt. Bitte 'Android System WebView' im Play Store aktualisieren.")
        }
        val collected = mutableListOf<PageSnapshot>()
        val done = CompletableDeferred<PageSnapshot>()
        val webView = WebView(context.applicationContext)
        try {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            webView.settings.userAgentString = webView.settings.userAgentString.replace("; wv", "")
            webView.webViewClient = WebViewClient()
            // Ohne Layout rendern manche Seiten nichts.
            webView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, 1080, 2400)

            WebViewCompat.addDocumentStartJavaScript(webView, snapshotScript, setOf("*"))
            WebViewCompat.addWebMessageListener(webView, "AskBridge", setOf("*")) { _, message, _, _, _ ->
                val snap = parseSnapshot(message.data) ?: return@addWebMessageListener
                collected.removeAll { it.url == snap.url }
                collected.add(snap)
                // Fehler beim Auswerten dürfen die App nicht beenden (Rückruf läuft im Hauptthread)
                val ok = try { !done.isCompleted && accept(snap) } catch (t: Throwable) { false }
                if (ok) done.complete(snap)
            }

            when {
                html != null -> webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                url != null -> webView.loadUrl(url)
            }

            val result = withTimeoutOrNull(timeoutMs) { done.await() }
            ExtractResult(result, collected.toList(), null)
        } finally {
            Handler(Looper.getMainLooper()).post {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    private fun parseSnapshot(data: String?): PageSnapshot? {
        if (data.isNullOrBlank()) return null
        return try {
            val o = JSONObject(data)
            val imgs = o.optJSONArray("imgs")
            val links = o.optJSONArray("links")
            PageSnapshot(
                url = o.optString("url"),
                text = o.optString("text"),
                images = (0 until (imgs?.length() ?: 0)).map {
                    val i = imgs!!.getJSONObject(it)
                    PageImage(i.optString("src"), i.optString("alt"))
                },
                links = (0 until (links?.length() ?: 0)).map {
                    val l = links!!.getJSONObject(it)
                    PageLink(l.optString("href"), l.optString("text"))
                },
                rows = o.optJSONArray("rows")?.let { rows ->
                    (0 until rows.length()).map { idx ->
                        val r = rows.getJSONObject(idx)
                        fun strings(key: String) = r.optJSONArray(key)?.let { a ->
                            (0 until a.length()).map { a.optString(it) }
                        } ?: emptyList()
                        PageRow(strings("cells"), strings("links"), strings("imgs"))
                    }
                } ?: emptyList(),
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class ExtractResult(
    val accepted: PageSnapshot?,
    val all: List<PageSnapshot>,
    val error: String?,
)
