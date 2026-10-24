package com.dondeestamibondi.data

import com.dondeestamibondi.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Network layer for the Transporte Ya backend.
 *
 * All of this was reconstructed from the shipped APK (classes6.dex):
 *  - Base host and port:  gc/h.java  ->  hc/n.java  (api_host / api_port defaults)
 *  - Request signing:     gc/b.java  (HTTP client) + gc/g.java (HMAC-like signer)
 *
 * Every request to the backend must carry:
 *   User-Agent:        "TransporteYa/1.0 Android"
 *   X-Auth-Timestamp:  current epoch millis (+ server clock offset)
 *   X-Auth-Signature:  sha256Hex(timestampString + SECRET)
 *
 * On HTTP 403 the server returns an "x-server-time" header; the app resyncs its
 * clock offset from it and retries once. We replicate that here.
 */
object Net {

    const val BASE = "https://transporteya.com.ar:8999"
    const val COLECTIVOS_BASE = "$BASE/v2/countries/ar"

    // App-shared signing secret embedded in gc/g.java of the original APK.
    // Injected at build time from local.properties (ty.secret) — never committed.
    private val SECRET = BuildConfig.TY_SECRET
    private const val USER_AGENT = "TransporteYa/1.0 Android"

    // Difference (ms) between the server clock and ours, learned from 403s.
    @Volatile
    private var clockOffsetMs: Long = 0

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor())
        .build()

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }

    /** Live colectivos WebSocket (same endpoint the original app uses for AR). */
    const val WS_COLECTIVOS = "wss://transporteya.com.ar:8999/ws/countries/ar/colectivos"

    /**
     * The WebSocket authenticates with the same signature, but as query params
     * (`?t=<ts>&s=<sig>`), as in gc/i.java of the original APK.
     */
    fun signedWsUrl(base: String): String {
        val ts = System.currentTimeMillis() + clockOffsetMs
        return "$base?t=$ts&s=${sha256Hex(ts.toString() + SECRET)}"
    }

    private fun signed(request: Request): Request {
        val ts = System.currentTimeMillis() + clockOffsetMs
        val sig = sha256Hex(ts.toString() + SECRET)
        return request.newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("X-Auth-Timestamp", ts.toString())
            .header("X-Auth-Signature", sig)
            .build()
    }

    private class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            var response = chain.proceed(signed(original))
            if (response.code == 403) {
                val serverTime = response.header("x-server-time")?.toLongOrNull()
                if (serverTime != null) {
                    clockOffsetMs = serverTime - System.currentTimeMillis()
                    response.close()
                    response = chain.proceed(signed(original))
                }
            }
            return response
        }
    }

    /** Runs a GET and returns the body as a String, or throws on non-2xx. */
    fun getString(url: String): String {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} for $url")
            return body
        }
    }
}
