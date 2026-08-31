package org.amnezia.awg.ui

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Enrolls this device as a WireGuard peer with the KAPO control plane.
 *
 * The phone generates its own keypair (see KapoVpn); only the PUBLIC key is
 * sent here. The server validates the licence, assigns a tunnel IP, registers
 * the peer on the chosen node, and returns everything needed to build the
 * tunnel. The private key never leaves the device.
 */
object EnrollClient {

    // Same host as the licence server.
    const val BASE_URL = "https://api.kapovpn.com"

    data class Result(
        val ok: Boolean,
        val status: String,          // when !ok: not_found / expired / device_limit / ...
        val assignedIp: String = "",
        val endpoint: String = "",
        val serverPublicKey: String = "",
        val dns: String = "9.9.9.9",
        val jc: Int = 0, val jmin: Int = 0, val jmax: Int = 0,
        val s1: Int = 0, val s2: Int = 0,
        val h1: Long = 0, val h2: Long = 0, val h3: Long = 0, val h4: Long = 0
    )

    private fun deviceId(ctx: Context): String =
        try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown" }
        catch (e: Exception) { "unknown" }

    /** Blocking - always call off the main thread. */
    fun enroll(ctx: Context, account: String, publicKey: String, chain: List<String> = listOf("is"), adblock: Boolean = false): Result {
        return try {
            val conn = (URL("$BASE_URL/api/v1/enroll").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val chainArr = org.json.JSONArray()
            (if (chain.isEmpty()) listOf("is") else chain).forEach { chainArr.put(it) }
            val payload = JSONObject()
                .put("account", account.replace(Regex("[^0-9A-Za-z]"), "").uppercase())
                .put("public_key", publicKey)
                .put("chain", chainArr)
                .put("node_id", chain.firstOrNull() ?: "is")   // legacy fallback
                .put("adblock", adblock)
                .put("device_id", deviceId(ctx))
                .toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "{}"
            val j = JSONObject(text)
            if (!j.optBoolean("ok", false)) {
                return Result(false, j.optString("status", j.optString("error", "error")))
            }
            val node = j.getJSONObject("node")
            val obfs = node.getJSONObject("obfs")
            Result(
                ok = true, status = "ok",
                assignedIp = j.getString("assigned_ip"),
                endpoint = node.getString("endpoint"),
                serverPublicKey = node.getString("server_public_key"),
                dns = node.optString("dns", "9.9.9.9"),
                jc = obfs.getInt("Jc"), jmin = obfs.getInt("Jmin"), jmax = obfs.getInt("Jmax"),
                s1 = obfs.getInt("S1"), s2 = obfs.getInt("S2"),
                h1 = obfs.getLong("H1"), h2 = obfs.getLong("H2"),
                h3 = obfs.getLong("H3"), h4 = obfs.getLong("H4")
            )
        } catch (e: Exception) {
            Result(false, "network_error")
        }
    }
}
