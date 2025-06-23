package eu.kanade.tachiyomi.network.helper

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.TimeUnit

object WebViewBypassHelper {

    private const val TAG = "MGKomik"
    private const val TIMEOUT_SECONDS = 15L
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchClearanceCookie(
        context: Context,
        url: String,
    ): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            Log.d(TAG, "🚀 WebView bypass start: $url")

            val webView = WebView(context) // Deklarasi webView dipindah ke atas
            val cookieManager = CookieManager.getInstance()
            var timeoutTriggered = false

            // Fungsi fail yang bisa mengakses webView
            fun fail(message: String) {
                if (!cont.isCompleted) {
                    Log.w(TAG, message)
                    cleanup(webView)
                    cont.resumeWithException(Exception(message))
                }
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = USER_AGENT
            }

            cookieManager.setAcceptCookie(true)
            cookieManager.removeAllCookies(null)
            cookieManager.flush()

            val timeoutRunnable = Runnable {
                if (!cont.isCompleted) {
                    timeoutTriggered = true
                    Log.w(TAG, "⏰ Timeout: cf_clearance not received in $TIMEOUT_SECONDS seconds")
                    cleanup(webView)
                    cont.resumeWithException(Exception("Timeout: Cloudflare clearance not received"))
                }
            }

            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed(timeoutRunnable, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (loadedUrl == null) return

                    val cookie = cookieManager.getCookie(loadedUrl)
                    Log.d(TAG, "✅ Page loaded: $loadedUrl")
                    Log.d(TAG, "🍪 Cookies: $cookie")

                    if (cookie?.contains("cf_clearance=") == true && !cont.isCompleted) {
                        handler.removeCallbacks(timeoutRunnable)
                        Log.d(TAG, "🎉 cf_clearance obtained")
                        cleanup(webView)
                        cont.resume(cookie)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    fail("❌ WebView error [$errorCode]: $description")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    errorResponse?.let {
                        fail("❌ HTTP error: ${it.statusCode}")
                    }
                }
            }

            webView.loadUrl(url)

            cont.invokeOnCancellation {
                if (!timeoutTriggered) {
                    Log.w(TAG, "⚠️ WebView bypass cancelled")
                    handler.removeCallbacks(timeoutRunnable)
                    cleanup(webView)
                }
            }
        }
    }

    private fun cleanup(webView: WebView?) {
        try {
            webView?.stopLoading()
            webView?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error during WebView cleanup: ${e.message}")
        }
    }
}