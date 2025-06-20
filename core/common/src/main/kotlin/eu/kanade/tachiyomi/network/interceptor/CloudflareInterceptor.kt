package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.network.helper.bypassCloudflareWithSearch
import eu.kanade.tachiyomi.util.system.toast
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import tachiyomi.i18n.MR
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
    private val client: OkHttpClient,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean {
        if (response.code !in ERROR_CODES) return false
        val serverHeader = response.header("Server") ?: return false
        if (!SERVER_CHECK.any { serverHeader.contains(it, ignoreCase = true) }) return false

        val body = response.peekBody(Long.MAX_VALUE).string()
        val doc = Jsoup.parse(body, response.request.url.toString())

        val challenge = listOf(
            doc.selectFirst("form[action*=\"challenge\"]"),
            doc.selectFirst("script[data-type=\"challenge-form\"]"),
            doc.selectFirst("#challenge-form"),
            doc.getElementById("challenge-error-title"),
            doc.getElementById("challenge-error-text")
        ).any { it != null }

        val jsChallenge = doc.select("script").any {
            it.html().contains("setTimeout") && it.html().contains("challenge-form")
        }

        val metaRefresh = doc.select("noscript meta[http-equiv=refresh]").isNotEmpty()
        val formCount = doc.select("form").size
        val bodyLen = body.length

        return challenge || jsChallenge || metaRefresh || (formCount == 1 && bodyLen < 20_000)
    }

    override fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response {
        val url = request.url
        if (hasValidClearanceCookie(url)) {
            Log.d("CloudflareInterceptor", "cf_clearance valid, proceeding without bypass")
            response.close()
            return chain.proceed(request)
        }

        Log.d("CloudflareInterceptor", "cf_clearance missing or expired, attempting bypass")
        response.close()
        cookieManager.remove(url, listOf("cf_clearance"), 0)

        Log.d("CloudflareInterceptor", "Fallback to visible WebView UI")
        val oldCookie = cookieManager.get(url).firstOrNull { it.name == "cf_clearance" }
        resolveWithWebView(request, oldCookie)
        return chain.proceed(request)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
        val latch = CountDownLatch(1)
        var webview: WebView? = null
        var cloudflareBypassed = false
        var isWebViewOutdated = false

        val origRequestUrl = originalRequest.url.toString()
        val headers = parseHeaders(originalRequest.headers)

        executor.execute {
            webview = createWebView(originalRequest).apply { setDefaultSettings() }

            webview?.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val cookie = cookieManager.get(origRequestUrl.toHttpUrl())
                        .firstOrNull { it.name == "cf_clearance" }

                    if (cookie != null && cookie != oldCookie && !cookie.hasExpired()) {
                        Log.d("CloudflareInterceptor", "cf_clearance obtained via visible WebView: ${cookie.value}")
                        cloudflareBypassed = true
                        latch.countDown()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true &&
                        errorResponse?.statusCode !in ERROR_CODES
                    ) {
                        latch.countDown()
                    }
                }
            }

            webview?.loadUrl(origRequestUrl, headers)
        }

        latch.await(30, TimeUnit.SECONDS)

        executor.execute {
            if (!cloudflareBypassed) {
                isWebViewOutdated = webview?.isOutdated() == true
            }

            webview?.run {
                stopLoading()
                destroy()
            }
        }

        if (!cloudflareBypassed) {
            try {
                Log.w("CloudflareInterceptor", "WebView UI failed, trying bypassCloudflareWithSearch")
                bypassCloudflareWithSearch(context, cookieManager, origRequestUrl)
            } catch (e: Exception) {
                if (isWebViewOutdated) {
                    context.toast(MR.strings.information_webview_outdated, Toast.LENGTH_LONG)
                }
                Log.e("CloudflareInterceptor", "All Cloudflare bypass methods failed")
                throw CloudflareBypassException()
            }
        }
    }

    private fun hasValidClearanceCookie(url: HttpUrl): Boolean {
        val cookie = cookieManager.get(url).firstOrNull { it.name == "cf_clearance" }
        val valid = cookie != null && !cookie.hasExpired()
        Log.d("CloudflareInterceptor", "hasValidClearanceCookie for ${url.host}: $valid")
        return valid
    }

    private class CloudflareBypassException : Exception()
}

fun Cookie.hasExpired(): Boolean {
    return this.expiresAt < System.currentTimeMillis()
}

private val ERROR_CODES = listOf(403, 503)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
