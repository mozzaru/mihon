package eu.kanade.tachiyomi.network.helper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

object HeadlessCloudflareBypass {

    private const val TIMEOUT_SECONDS = 30L
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun fetchClearanceCookie(
        context: Context,
        url: String,
        onSuccess: (cookie: String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        Log.d("CloudflareInterceptor", "Starting headless bypass for: $url")

        val handler = Handler(Looper.getMainLooper())
        val webView = WebView(context.applicationContext)

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
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                Log.d("CloudflareInterceptor", "Page finished loading: $finishedUrl")

                handler.postDelayed({
                    val cookie = cookieManager.getCookie(finishedUrl)
                    Log.d("CloudflareInterceptor", "Cookies: $cookie")

                    if (cookie?.contains("cf_clearance") == true) {
                        Log.d("CloudflareInterceptor", "cf_clearance obtained: $cookie")
                        try {
                            onSuccess(cookie)
                        } catch (e: Exception) {
                            Log.w("HeadlessBypass", "onSuccess failed: ${e.message}")
                        } finally {
                            cleanup(webView)
                        }
                    }
                }, 2000)
            }
        }

        handler.postDelayed({
            Log.w("HeadlessBypass", "Timeout: cf_clearance not received in $TIMEOUT_SECONDS seconds")
            cleanup(webView)
            onFailure(Exception("Timeout: Cloudflare clearance not received in $TIMEOUT_SECONDS seconds."))
        }, TIMEOUT_SECONDS * 1000)

        webView.loadUrl(url)
    }

    private fun cleanup(webView: WebView) {
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (e: Exception) {
            Log.w("HeadlessBypass", "WebView cleanup failed: ${e.message}")
        }
    }
}