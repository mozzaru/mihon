package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.helper.HeadlessCloudflareBypass
import eu.kanade.tachiyomi.network.helper.WebViewBypassHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
    private val client: OkHttpClient,
) : Interceptor {
    
    private val bypassCache = mutableSetOf<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        Log.d(TAG, "🌐 Request URL: $url")
        
        if (bypassCache.contains(url.host)) {
            Log.d(TAG, "♻️ Using cached bypass for ${url.host}")
            return chain.proceed(request)
        }

        var response = chain.proceed(request)
        Log.d(TAG, "📥 Initial response code: ${response.code}")

        if (!shouldIntercept(response)) {
            Log.d(TAG, "✅ No Cloudflare challenge detected, proceeding normally")
            return response
        }

        Log.w(TAG, "⚠️ Cloudflare challenge detected for ${url.host}")

        if (hasValidClearanceCookie(url)) {
            Log.d(TAG, "🍪 Valid cf_clearance cookie exists, retrying request")
            response.close()
            return chain.proceed(request)
        }

        Log.d(TAG, "🍪 cf_clearance missing or expired, starting bypass")
        response.close()
        cookieManager.remove(url, listOf("cf_clearance"), 0)

        runBlocking {
            try {
                attemptBypass(url.toString())
                bypassCache.add(url.host) // Cache domain yang berhasil di-bypass
            } catch (e: Exception) {
                Log.e(TAG, "❌ Cloudflare bypass failed: ${e.message}")
                Toast.makeText(context, "Cloudflare bypass failed", Toast.LENGTH_LONG).show()
                throw CloudflareBypassException()
            }
        }

        Log.d(TAG, "🔁 Retrying request after bypass")
        return chain.proceed(request)
    }

    private fun shouldIntercept(response: Response): Boolean {
        if (response.code !in ERROR_CODES) {
            Log.d(TAG, "🔎 Response code ${response.code} not in error codes")
            return false
        }
        val serverHeader = response.header("Server") ?: ""
        Log.d(TAG, "🔎 Server header: $serverHeader")
        if (!SERVER_CHECK.any { serverHeader.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "🔎 Server header does not indicate Cloudflare")
            return false
        }

        val body = response.peekBody(Long.MAX_VALUE).string()
        val doc = Jsoup.parse(body, response.request.url.toString())

        val challengeDetected = listOf(
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

        Log.d(TAG, "🔎 Challenge: $challengeDetected, JS Challenge: $jsChallenge, Meta Refresh: $metaRefresh, Form count: $formCount, Body length: $bodyLen")

        return challengeDetected || jsChallenge || metaRefresh || (formCount == 1 && bodyLen < 20_000)
    }

    private fun hasValidClearanceCookie(url: HttpUrl): Boolean {
        val cookie = cookieManager.get(url).firstOrNull { it.name == "cf_clearance" }
        val valid = cookie != null && !cookie.hasExpired()
        Log.d(TAG, "🍪 hasValidClearanceCookie for ${url.host}: $valid")
        return valid
    }

    private suspend fun attemptBypass(url: String) {
        withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            var success = false
    
            Log.d(TAG, "🚀 Starting headless bypass for: $url")
            HeadlessCloudflareBypass.fetchClearanceCookie(
                context,
                url,
                onSuccess = {
                    Log.d(TAG, "🎉 Headless bypass success: $it")
                    success = true
                    latch.countDown()
                },
                onFailure = {
                    Log.w(TAG, "⚠️ Headless bypass failed: ${it.message}")
                    latch.countDown()
                }
            )
    
            val completed = latch.await(30, TimeUnit.SECONDS)
            Log.d(TAG, "⏱ Headless bypass completed: $completed, success: $success")
    
            if (!success) {
                Log.d(TAG, "🌐 Falling back to WebView bypass")
                WebViewBypassHelper.fetchClearanceCookie(context, url)
                Log.d(TAG, "🎉 WebView bypass success")
            }
        }
    }

    private class CloudflareBypassException : Exception()

    companion object {
        private const val TAG = "MGKomik"
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
    }
}

fun Cookie.hasExpired(): Boolean {
    return this.expiresAt < System.currentTimeMillis()
}