package org.amnezia.awg.ui

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * App-facing device management against the KAPO license server. The account
 * code is the only credential (the server normalizes dashes/spaces), so a user
 * can list and free their own device slots straight from the Account tab.
 *
 * Reuses LicenseClient.BASE_URL so there is a single place to point the app at
 * the control plane.
 */
object DevicesClient {

    data class Device(val key: String, val label: String, val node: String, val added: String)
    data class ListResult(
        val ok: Boolean,
        val max: Int,
        val devices: List<Device>,
        val status: String = ""
    )

    private fun post(path: String, body: JSONObject): JSONObject? {
        if (LicenseClient.BASE_URL.isBlank()) return null
        return try {
            val conn = URL(LicenseClient.BASE_URL + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "{}"
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    /** List the devices (tunnel peers) currently holding a slot on this code. */
    fun list(account: String): ListResult {
        val j = post("/api/v1/devices/list", JSONObject().put("account", account))
            ?: return ListResult(false, 5, emptyList(), "network_error")
        if (!j.optBoolean("ok", false))
            return ListResult(false, j.optInt("max_devices", 5), emptyList(), j.optString("status", "error"))
        val arr = j.optJSONArray("devices")
        val list = ArrayList<Device>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Device(
                    o.optString("key"),
                    o.optString("label", "Device"),
                    o.optString("node", ""),
                    o.optString("added", "")
                )
            )
        }
        return ListResult(true, j.optInt("max_devices", 5), list)
    }

    /** Free a slot by its device key. Returns true on success. */
    fun remove(account: String, key: String): Boolean {
        val j = post(
            "/api/v1/devices/remove",
            JSONObject().put("account", account).put("key", key)
        )
        return j?.optBoolean("ok", false) == true
    }
}
