package eu.kanade.tachiyomi.network.helper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Cookie
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("SetJavaScriptEnabled")
suspend fun bypassCloudflareWithSearch(context: Context, cookieJar: AndroidCookieJar, url: String) {
    Log.d("MGKomik", "Starting bypass via search injection at $url")

    val mainExecutor = ContextCompat.getMainExecutor(context)

    suspendCancellableCoroutine<Unit> { cont ->
        mainExecutor.execute {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

            webView.clearCache(true)
            webView.clearHistory()

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d("MGKomik", "Page loaded: $url")

                    val js = """
                        (function() {
                            var icon = document.querySelector('.search-icon, .ion-ios-search-strong');
                            if(icon) icon.click();

                            var input = document.querySelector('input[name="s"]');
                            if(input) {
                                input.value = 'test';
                                var form = input.closest('form');
                                if(form) form.submit();
                            }
                        })();
                    """.trimIndent()

                    view.evaluateJavascript(js) { result ->
                        Log.d("MGKomik", "JS executed: $result")

                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                val cookies = cookieJar.get(url.toHttpUrl())
                                cookies.forEach {
                                    Log.d("MGKomik", "Cookie: ${it.name}=${it.value}")
                                }
                                val cfCookie = cookies.firstOrNull { it.name == "cf_clearance" }
                                if (cfCookie != null && !cfCookie.hasExpired()) {
                                    Log.d("MGKomik", "✅ cf_clearance obtained: ${cfCookie.value}")
                                    cont.resume(Unit)
                                } else {
                                    Log.e("MGKomik", "❌ Failed to obtain cf_clearance cookie")
                                    cont.resumeWithException(Exception("Gagal bypass Cloudflare challenge"))
                                }
                            } finally {
                                webView.destroy()
                            }
                        }, 8000)
                    }
                }
            }

            Log.d("MGKomik", "Loading page in WebView: $url")
            webView.loadUrl(url)
        }
    }
}

// Extension function untuk cek apakah cookie sudah kedaluwarsa
fun Cookie.hasExpired(): Boolean {
    return this.expiresAt < System.currentTimeMillis()
}