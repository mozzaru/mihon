package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.i18n.MR
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class WebViewInterceptor(
    private val context: Context,
    private val defaultUserAgentProvider: () -> String,
) : Interceptor {

    /**
     * When this is called, it initializes the WebView if it wasn't already. We use this to avoid
     * blocking the main thread too much. If used too often we could consider moving it to the
     * Application class.
     */
    private val initWebView by lazy {
        Log.d("CloudflareInterceptor", "Initializing WebView")
        if (DeviceUtil.isMiui || Build.VERSION.SDK_INT == Build.VERSION_CODES.S && DeviceUtil.isSamsung) {
            Log.d("CloudflareInterceptor", "Skipping WebView init on MIUI/Samsung Android 12 device")
            return@lazy
        }

        try {
            WebSettings.getDefaultUserAgent(context)
            Log.d("CloudflareInterceptor", "WebView default user-agent obtained")
        } catch (e: Exception) {
            Log.w("WebViewInterceptor", "Failed to get default user-agent: ${e.message}")
        }
    }

    abstract fun shouldIntercept(response: Response): Boolean

    abstract fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        Log.d("CloudflareInterceptor", "Intercepting request to ${request.url}")

        if (!shouldIntercept(response)) {
            Log.d("CloudflareInterceptor", "No interception needed for ${request.url}")
            return response
        }

        if (!WebViewUtil.supportsWebView(context)) {
            Log.w("WebViewInterceptor", "WebView not supported on device")
            launchUI {
                context.toast(MR.strings.information_webview_required, Toast.LENGTH_LONG)
            }
            return response
        }

        initWebView
        Log.d("CloudflareInterceptor", "Delegating to WebView-based interception")
        return intercept(chain, request, response)
    }

    fun parseHeaders(headers: Headers): Map<String, String> {
        return headers
            .filter { (name, value) ->
                isRequestHeaderSafe(name, value)
            }
            .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
            .mapValues { it.value.getOrNull(0).orEmpty() }
    }

    fun CountDownLatch.awaitFor30Seconds() {
        Log.d("CloudflareInterceptor", "Awaiting latch for up to 30 seconds")
        await(30, TimeUnit.SECONDS)
    }

    fun createWebView(request: Request): WebView {
        return WebView(context).apply {
            setDefaultSettings()
            val userAgent = request.header("User-Agent") ?: defaultUserAgentProvider()
            settings.userAgentString = userAgent
            Log.d("CloudflareInterceptor", "Created WebView with UA: $userAgent")
        }
    }
}

// Based on [IsRequestHeaderSafe] in
// https://source.chromium.org/chromium/chromium/src/+/main:services/network/public/cpp/header_util.cc
private fun isRequestHeaderSafe(_name: String, _value: String): Boolean {
    val name = _name.lowercase(Locale.ENGLISH)
    val value = _value.lowercase(Locale.ENGLISH)
    if (name in unsafeHeaderNames || name.startsWith("proxy-")) return false
    if (name == "connection" && value == "upgrade") return false
    return true
}
private val unsafeHeaderNames = listOf(
    "content-length", "host", "trailer", "te", "upgrade", "cookie2", "keep-alive", "transfer-encoding", "set-cookie",
)
