package org.amnezia.awg.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.amnezia.awg.R
import org.amnezia.awg.databinding.FragmentProfileBinding

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private var accountVisible = false
    private var account: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("kapo_prefs", Context.MODE_PRIVATE)
        account = prefs.getString("account_number", "") ?: ""
        val display = if (account.isNotEmpty()) account else "----·----·----·----"

        updateAccountDisplay(display)

        binding.btnToggleAccount.setOnClickListener {
            accountVisible = !accountVisible
            updateAccountDisplay(display)
        }

        binding.btnCopyAccount.setOnClickListener {
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("account", display))
            binding.btnCopyAccount.text = "COPIED!"
            binding.root.postDelayed({ binding.btnCopyAccount.text = "COPY ACCOUNT NUMBER" }, 2000)
        }

        // Notifications live here now; the NEW tag and the nav dot both clear
        // the first time the list is opened.
        binding.tvNotifNew.visibility =
            if (prefs.getBoolean("notifs_seen_v1", false)) View.GONE else View.VISIBLE
        binding.btnNotifications.setOnClickListener {
            prefs.edit().putBoolean("notifs_seen_v1", true).apply()
            binding.tvNotifNew.visibility = View.GONE
            (activity as? MainActivity)?.clearProfileBadge()
            startActivity(Intent(requireContext(), NotificationsActivity::class.java))
        }

        // Real subscription state, from the last successful license check.
        val daysLeftAtCheck = prefs.getInt("days_left", 0)
        val lastValidated   = prefs.getLong("last_validated", 0L)
        val elapsedDays     = if (lastValidated > 0L)
            ((System.currentTimeMillis() - lastValidated) / 86_400_000L).toInt() else 0
        val daysLeft        = (daysLeftAtCheck - elapsedDays).coerceAtLeast(0)

        if (lastValidated == 0L) {
            binding.tvSubPlan.text   = "KAPO Access"
            binding.tvSubExpiry.text = "Not validated yet"
            binding.tvSubStatus.text = "PENDING"
        } else {
            binding.tvSubStatus.text = if (daysLeft > 0) "ACTIVE" else "EXPIRED"
            binding.tvSubPlan.text = when {
                daysLeft <= 0    -> "Expired"
                daysLeft == 1    -> "1 day left"
                daysLeft >= 3650 -> "Lifetime access"
                else             -> "$daysLeft days left"
            }
            val expiryMillis = lastValidated + daysLeftAtCheck * 86_400_000L
            val fmt = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
            binding.tvSubExpiry.text =
                if (daysLeft <= 0) "Add time to keep your access"
                else "Expires: " + fmt.format(java.util.Date(expiryMillis))
        }

        // Add time / renew: open the crypto checkout pre-filled with this code.
        binding.btnAddTime.setOnClickListener {
            val url = if (account.isNotEmpty())
                "https://kapovpn.com/account.html?code=$account"
            else "https://kapovpn.com/account.html"
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }

        binding.btnLogout.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { KapoVpn.disconnect() }
            KapoState.setConnected(false)
            KapoState.resetTraffic()
            prefs.edit().clear().apply()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }

        loadDevices()
    }

    override fun onResume() {
        super.onResume()
        // Refresh the slot list whenever the tab comes forward (e.g. after
        // connecting a new device or returning from checkout).
        if (_binding != null && account.isNotEmpty()) loadDevices()
    }

    private fun updateAccountDisplay(account: String) {
        if (accountVisible) {
            binding.tvAccountNumber.text = account
            binding.btnToggleAccount.text = "HIDE"
        } else {
            binding.tvAccountNumber.text = "••••-••••-••••-••••"
            binding.btnToggleAccount.text = "SHOW"
        }
    }

    // ---------------------------------------------------------------- devices

    private fun loadDevices() {
        val b = _binding ?: return
        if (account.isEmpty()) {
            b.tvDevicesCount.text = "0 / 5"
            b.tvDevicesEmpty.visibility = View.VISIBLE
            b.tvDevicesEmpty.text = "Sign in to manage devices."
            b.devicesList.removeAllViews()
            return
        }
        b.tvDevicesEmpty.visibility = View.VISIBLE
        b.tvDevicesEmpty.text = "Loading devices…"
        Thread {
            val res = DevicesClient.list(account)
            activity?.runOnUiThread {
                val bb = _binding ?: return@runOnUiThread
                bb.tvDevicesCount.text = "${res.devices.size} / ${res.max}"
                if (!res.ok) {
                    bb.devicesList.removeAllViews()
                    bb.tvDevicesEmpty.visibility = View.VISIBLE
                    bb.tvDevicesEmpty.text = when (res.status) {
                        "network_error"        -> "Can't reach the server."
                        "not_found", "invalid" -> "Sign in to manage devices."
                        else                   -> "Couldn't load devices."
                    }
                    bb.tvDevicesCount.text = "– / ${res.max}"
                    return@runOnUiThread
                }
                renderDevices(res.devices)
            }
        }.start()
    }

    private fun renderDevices(devices: List<DevicesClient.Device>) {
        val b = _binding ?: return
        val ctx = requireContext()
        b.devicesList.removeAllViews()
        if (devices.isEmpty()) {
            b.tvDevicesEmpty.visibility = View.VISIBLE
            b.tvDevicesEmpty.text = "No devices connected yet."
            return
        }
        b.tvDevicesEmpty.visibility = View.GONE

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val rounded = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        val normal  = Typeface.create("sans-serif-rounded", Typeface.NORMAL)

        devices.forEachIndexed { i, d ->
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val rlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (i > 0) rlp.topMargin = dp(14)
            row.layoutParams = rlp

            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val name = TextView(ctx)
            name.text = d.label
            name.setTextColor(ctx.getColor(R.color.text))
            name.textSize = 14f
            name.typeface = rounded
            col.addView(name)
            val sub = TextView(ctx)
            sub.text = nodeLabel(d.node) + "  ·  added " + shortDate(d.added)
            sub.setTextColor(ctx.getColor(R.color.text_faint))
            sub.textSize = 10f
            sub.typeface = normal
            sub.setPadding(0, dp(2), 0, 0)
            col.addView(sub)
            row.addView(col)

            val rm = TextView(ctx)
            rm.text = "REMOVE"
            rm.setTextColor(ctx.getColor(R.color.coral_ink))
            rm.textSize = 9f
            rm.typeface = rounded
            rm.letterSpacing = 0.16f
            rm.setPadding(dp(12), dp(7), dp(12), dp(7))
            rm.setBackgroundResource(R.drawable.bg_pill_glass)
            rm.isClickable = true
            rm.setOnClickListener { removeDevice(d.key, rm) }
            row.addView(rm)

            b.devicesList.addView(row)
        }
    }

    private fun removeDevice(key: String, btn: TextView) {
        btn.text = "…"
        btn.isClickable = false
        Thread {
            val ok = DevicesClient.remove(account, key)
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (ok) {
                    loadDevices()
                } else {
                    btn.text = "REMOVE"
                    btn.isClickable = true
                    Toast.makeText(requireContext(), "Couldn't remove that device. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** IS/FI/MY -> a friendly node label; unknown ids pass through uppercased. */
    private fun nodeLabel(node: String): String {
        val info = KapoState.nodeCatalog[node.lowercase()]
        return if (info != null) info.flag + " " + info.city else node.uppercase()
    }

    /** ISO timestamp -> just the YYYY-MM-DD date. */
    private fun shortDate(iso: String): String =
        if (iso.length >= 10) iso.substring(0, 10) else iso

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
