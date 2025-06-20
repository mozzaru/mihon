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
     * Menginisialisasi WebView jika diperlukan.
     * Menghindari crash atau bug pada perangkat MIUI atau Samsung dengan Android 12.
     */
    private val initWebView by lazy {
        Log.d("CloudflareInterceptor", "Initializing WebView...")

        if (DeviceUtil.isMiui || (Build.VERSION.SDK_INT == Build.VERSION_CODES.S && DeviceUtil.isSamsung)) {
            Log.w("CloudflareInterceptor", "WebView initialization skipped on MIUI/Samsung Android 12")
            return@lazy
        }

        try {
            WebSettings.getDefaultUserAgent(context)
            Log.d("CloudflareInterceptor", "WebView user-agent initialized")
        } catch (e: Exception) {
            Log.e("WebViewInterceptor", "Failed to initialize user-agent: ${e.message}")
        }
    }

    /**
     * Menentukan apakah respon perlu di-intercept untuk bypass Cloudflare.
     */
    abstract fun shouldIntercept(response: Response): Boolean

    /**
     * Dipanggil jika Cloudflare challenge terdeteksi.
     * Implementasi disediakan oleh subclass (misalnya CloudflareInterceptor).
     */
    abstract fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response

    /**
     * Interceptor utama untuk menangani respon dan meneruskan jika bypass diperlukan.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        Log.d("CloudflareInterceptor", "Checking response from: ${request.url}")

        if (!shouldIntercept(response)) {
            Log.d("CloudflareInterceptor", "Bypass not needed for: ${request.url}")
            return response
        }

        if (!WebViewUtil.supportsWebView(context)) {
            Log.w("WebViewInterceptor", "WebView not supported on this device")
            launchUI {
                context.toast(MR.strings.information_webview_required, Toast.LENGTH_LONG)
            }
            return response
        }

        initWebView
        Log.d("CloudflareInterceptor", "Delegating to WebView-based Cloudflare bypass")
        return intercept(chain, request, response)
    }

    /**
     * Membuat WebView dengan pengaturan default dan user-agent sesuai header.
     */
    fun createWebView(request: Request): WebView {
        return WebView(context.applicationContext).apply {
            setDefaultSettings()
            val userAgent = request.header("User-Agent") ?: defaultUserAgentProvider()
            settings.userAgentString = userAgent
            Log.d("CloudflareInterceptor", "Created WebView with UA: $userAgent")
        }
    }

    /**
     * Menyaring header yang aman untuk digunakan dalam WebView.
     */
    fun parseHeaders(headers: Headers): Map<String, String> {
        return headers
            .filter { (name, value) -> isRequestHeaderSafe(name, value) }
            .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
            .mapValues { (_, values) -> values.firstOrNull().orEmpty() }
    }

    /**
     * Menunggu hingga 30 detik dalam operasi blocking menggunakan latch.
     */
    fun CountDownLatch.awaitFor30Seconds() {
        Log.d("CloudflareInterceptor", "Waiting for WebView result (30s max)")
        await(30, TimeUnit.SECONDS)
    }
}

/**
 * Berdasarkan Chromium header sanitizer.
 * Mencegah header berbahaya digunakan saat memuat WebView.
 */
private fun isRequestHeaderSafe(_name: String, _value: String): Boolean {
    val name = _name.lowercase(Locale.ENGLISH)
    val value = _value.lowercase(Locale.ENGLISH)

    if (name in unsafeHeaderNames || name.startsWith("proxy-")) return false
    if (name == "connection" && value == "upgrade") return false

    return true
}

private val unsafeHeaderNames = listOf(
    "content-length", "host", "trailer", "te", "upgrade", "cookie2",
    "keep-alive", "transfer-encoding", "set-cookie",
)