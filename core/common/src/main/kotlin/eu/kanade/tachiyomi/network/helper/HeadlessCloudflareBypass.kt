package eu.kanade.tachiyomi.network.helper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.TimeUnit

object HeadlessCloudflareBypass {

    private const val TIMEOUT_SECONDS = 30L
    
    @SuppressLint("SetJavaScriptEnabled")
    fun fetchClearanceCookie(
        context: Context,
        url: String,
        onSuccess: (cookie: String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        Log.d("MGKomik", "🚀 Starting headless bypass for: $url")

        Handler(Looper.getMainLooper()).post {
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)
            var called = false

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
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    Log.d("MGKomik", "✅ Page loaded: $loadedUrl")

                    // Inject JavaScript to handle Cloudflare challenge
                    view?.evaluateJavascript("""
                        (function() {
                            // Try to find challenge form first
                            var challengeForm = document.getElementById('challenge-form');
                            if (challengeForm) {
                                challengeForm.submit();
                                return;
                            }
                            
                            // Fallback to search method
                            var searchIcon = document.querySelector('.search-icon, .ion-ios-search-strong');
                            if (searchIcon) searchIcon.click();
                            setTimeout(function() {
                                var input = document.querySelector('input[name="s"]');
                                if (input) {
                                    input.value = "test";
                                    var form = input.closest('form');
                                    if (form) form.submit();
                                }
                            }, 500);
                        })();
                    """.trimIndent(), null)

                    val cookie = cookieManager.getCookie(loadedUrl)
                    Log.d("MGKomik", "🍪 Cookies: $cookie")
                    if (cookie?.contains("cf_clearance=") == true && !called) {
                        called = true
                        Log.d("MGKomik", "🎉 cf_clearance obtained")
                        handler.postDelayed({
                            onSuccess(cookie)
                            try {
                                view?.destroy()
                            } catch (e: Exception) {
                                Log.w("MGKomik", "⚠️ Error destroying WebView: ${e.message}")
                            }
                        }, 1000)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    Log.e("MGKomik", "❌ WebView error [$errorCode]: $description")
                    if (!called) {
                        called = true
                        onFailure(Exception("WebView error: $description"))
                    }
                }
            }

            handler.postDelayed({
                if (!called) {
                    called = true
                    try {
                        webView.stopLoading()
                        webView.destroy()
                    } catch (e: Exception) {
                        Log.w("MGKomik", "⚠️ Error during timeout cleanup: ${e.message}")
                    }
                    Log.w("MGKomik", "⏰ Timeout: cf_clearance not received in $TIMEOUT_SECONDS seconds")
                    onFailure(Exception("Timeout: Cloudflare clearance not received in $TIMEOUT_SECONDS seconds."))
                }
            }, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))

            Log.d("MGKomik", "🌐 Loading URL in headless WebView: $url")
            webView.loadUrl(url)
        }
    }

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
}