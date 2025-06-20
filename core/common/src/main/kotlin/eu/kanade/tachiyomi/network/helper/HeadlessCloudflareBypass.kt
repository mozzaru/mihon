package eu.kanade.tachiyomi.network.helper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object HeadlessCloudflareBypass {

    private const val TIMEOUT_SECONDS = 30L

    /**
     * Menjalankan WebView tanpa UI untuk mendapatkan cookie cf_clearance.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun fetchClearanceCookie(
        context: Context,
        url: String,
        onSuccess: (cookie: String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        Log.d("CloudflareInterceptor", "Starting headless bypass for: $url")

        val handler = Handler(Looper.getMainLooper())
        val webView = WebView(context)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = USER_AGENT
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.removeAllCookies(null)
        cookieManager.flush()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d("CloudflareInterceptor", "Page finished loading: $url")
                val cookie = cookieManager.getCookie(url)
                Log.d("CloudflareInterceptor", "Cookies: $cookie")
                if (cookie?.contains("cf_clearance") == true) {
                    Log.d("CloudflareInterceptor", "cf_clearance obtained: $cookie")
                    handler.postDelayed({
                        onSuccess(cookie)
                        webView.destroy()
                    }, 1000)
                }
            }
        }

        handler.postDelayed({
            try {
                webView.stopLoading()
                webView.destroy()
            } catch (e: Exception) {
                Log.w("HeadlessBypass", "Error during timeout cleanup: ${e.message}")
            }
            Log.w("HeadlessBypass", "Timeout: cf_clearance not received in $TIMEOUT_SECONDS seconds")
            onFailure(Exception("Timeout: Cloudflare clearance not received in $TIMEOUT_SECONDS seconds."))
        }, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))

        Log.d("CloudflareInterceptor", "Loading URL in headless WebView: $url")
        webView.loadUrl(url)
    }
    
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
}
