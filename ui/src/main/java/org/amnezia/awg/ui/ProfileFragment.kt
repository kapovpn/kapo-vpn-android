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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.R
import org.amnezia.awg.databinding.FragmentProfileBinding
import org.amnezia.awg.util.SecureAccountStore

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
        account = SecureAccountStore.read(prefs)
        val display = if (account.isNotEmpty()) account else "----·----·----·----"

        updateAccountDisplay(display)

        binding.btnToggleAccount.setOnClickListener {
            accountVisible = !accountVisible
            updateAccountDisplay(display)
        }

        // The redesigned tab flow (Home/Servers/Stats/Account) never routes through
        // MainActivity's options-menu Settings item - that menu only exists on the
        // legacy tunnel-editor screens and needs a Toolbar/ActionBar this flow doesn't
        // show. Without this, Settings (and "Verify my protection") had no way in.
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(requireContext(), org.amnezia.awg.activity.SettingsActivity::class.java))
        }

        binding.btnCopyAccount.setOnClickListener {
            copyAccount(display)
            binding.btnCopyAccount.text = "COPIED!"
            binding.root.postDelayed({ _binding?.btnCopyAccount?.text = "COPY ACCOUNT NUMBER" }, 2000)
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

        // Add time / renew: open the checkout WITHOUT the code. A code in the link
        // reached whichever app opened it (browser history, synced tabs, another app that
        // claims https links, the web server's logs), where Sign out and the panic wipe
        // can't clear it. The page asks for the code; offer to copy it first.
        binding.btnAddTime.setOnClickListener {
            val url = AddTimeLink.URL
            if (account.isEmpty()) {
                openAddTime(url)
                return@setOnClickListener
            }
            val themed = android.view.ContextThemeWrapper(requireContext(), R.style.ThemeOverlay_KapoVPN_MaterialDialog)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(themed)
                .setTitle("Add time")
                .setMessage(
                    "This opens kapovpn.com in your browser. The page asks for your account number: " +
                    "copy it now and paste it there. Clear it from your clipboard when you're done."
                )
                .setPositiveButton("Copy and open") { _, _ ->
                    copyAccount(account)
                    openAddTime(url)
                }
                .setNeutralButton("Just open") { _, _ -> openAddTime(url) }
                .setNegativeButton("Cancel", null)
                .show()
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

    private fun openAddTime(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No browser found. Open $url", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyAccount(code: String) {
        val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // The code is the whole credential: keep it out of Android 13+'s clipboard preview.
        val clip = ClipData.newPlainText("account", code)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        cb.setPrimaryClip(clip)
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
        // viewLifecycleOwner-scoped (not a raw Thread + activity?.runOnUiThread):
        // that pattern silently dropped the result if the fragment's view was
        // recreated before the request finished, leaving "Loading devices…"
        // stuck forever with no error shown. This cancels cleanly instead.
        viewLifecycleOwner.lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { DevicesClient.list(account) }
            val bb = _binding ?: return@launch
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
                return@launch
            }
            renderDevices(res.devices)
        }
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
        // Same fix as loadDevices(): lifecycle-aware coroutine instead of a raw
        // Thread + activity?.runOnUiThread, which could silently drop the result
        // (button stuck on "…" forever) if the view was recreated meanwhile.
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { DevicesClient.remove(account, key) }
            if (_binding == null) return@launch
            if (ok) {
                loadDevices()
            } else {
                btn.text = "REMOVE"
                btn.isClickable = true
                Toast.makeText(requireContext(), "Couldn't remove that device. Try again.", Toast.LENGTH_SHORT).show()
            }
        }
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
