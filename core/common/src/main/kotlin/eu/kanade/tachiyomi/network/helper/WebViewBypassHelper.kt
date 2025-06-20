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
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Cookie

@SuppressLint("SetJavaScriptEnabled")
fun bypassCloudflareWithSearch(context: Context, cookieJar: AndroidCookieJar, url: String) {
    val latch = CountDownLatch(1)
    val mainExecutor = ContextCompat.getMainExecutor(context)

    Log.d("CloudflareInterceptor", "Starting bypass via search injection at $url")

    mainExecutor.execute {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.clearCache(true)
        webView.clearHistory()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.d("CloudflareInterceptor", "Page loaded: $url")

                val js = """
                    (function() {
                        var icon = document.querySelector('.search-icon, .ion-ios-search-strong');
                        if(icon) icon.click();

                        var input = document.querySelector('input[name=\"s\"]');
                        if(input) {
                            input.value = 'test';
                            var form = input.closest('form');
                            if(form) form.submit();
                        }
                    })();
                """.trimIndent()

                view.evaluateJavascript(js) { result ->
                    Log.d("CloudflareInterceptor", "JS executed: $result")
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d("CloudflareInterceptor", "Waiting complete after JS execution")
                        latch.countDown()
                    }, 8000)
                }
            }
        }

        Log.d("CloudflareInterceptor", "Loading page in WebView: $url")
        webView.loadUrl(url)
    }

    latch.await(15, TimeUnit.SECONDS)

    val cookies = cookieJar.get(url.toHttpUrl())
    val cfCookie = cookies.firstOrNull { it.name == "cf_clearance" }
    if (cfCookie != null && !cfCookie.hasExpired()) {
        Log.d("CloudflareInterceptor", "✅ cf_clearance obtained: ${cfCookie.value}")
    } else {
        Log.e("BypassSearch", "❌ Failed to obtain cf_clearance cookie")
        throw Exception("Gagal bypass Cloudflare challenge")
    }
}

// Extension function untuk cek apakah cookie sudah kedaluwarsa
fun Cookie.hasExpired(): Boolean {
    return this.expiresAt < System.currentTimeMillis()
}
