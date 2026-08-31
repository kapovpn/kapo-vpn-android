package org.amnezia.awg.ui

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the KAPO license server (kapo-license-server) to check whether an
 * account code is valid and how many days are left. No third-party library -
 * just HttpURLConnection and org.json, both already on Android.
 *
 * Privacy: the only thing sent is the account code and a stable device id.
 * Nothing personal, ever.
 *
 * Demo mode: if BASE_URL is left blank, validate() accepts any well-formed
 * 16-char code so the app keeps working before the server is deployed.
 */
object LicenseClient {

    /** Set this to your deployed server, e.g. "https://api.kapovpn.com".
     *  Leave "" for demo mode (offline, accepts any well-formed code). */
    const val BASE_URL = "https://api.kapovpn.com"

    data class Result(
        val valid: Boolean,
        val status: String,
        val daysLeft: Int,
        val demo: Boolean = false
    )

    /** A stable, non-resettable-per-launch id. ANDROID_ID is fine here - it is
     *  per-app-signing-key on modern Android and never leaves for anything but
     *  the device cap. */
    private fun deviceId(ctx: Context): String =
        try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown" }
        catch (e: Exception) { "unknown" }

    /** Blocking network call - always run off the main thread. */
    fun validate(ctx: Context, account: String): Result {
        val normalized = account.replace(Regex("[^0-9A-Za-z]"), "").uppercase()
        if (normalized.length != 16) return Result(false, "invalid", 0)

        if (BASE_URL.isBlank()) {
            // Demo mode: no server yet, accept any well-formed code.
            return Result(true, "demo", 3650, demo = true)
        }

        return try {
            val url = URL("$BASE_URL/api/v1/validate")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject()
                .put("account", normalized)
                .put("device_id", deviceId(ctx))
                .toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "{}"
            val j = JSONObject(text)
            Result(
                valid = j.optBoolean("valid", false),
                status = j.optString("status", "error"),
                daysLeft = j.optInt("days_left", 0)
            )
        } catch (e: Exception) {
            // Network failure: don't lock a paying user out. Treat as a soft
            // pass if we've validated before; caller decides. Here we report
            // an explicit error status so the caller can fall back to cache.
            Result(false, "network_error", 0)
        }
    }
}
