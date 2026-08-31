package org.amnezia.awg.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import org.amnezia.awg.R
import org.amnezia.awg.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    internal lateinit var binding: ActivityMainBinding

    private companion object {
        const val TAG_HOME = "home"
        const val TAG_SERVERS = "servers"
        const val TAG_STATS = "stats"
        const val TAG_PROFILE = "profile"
        const val KEY_ACTIVE = "active_tab"
    }

    private var activeTag = TAG_HOME

    /** Crossfades the whole app between graphite and deep teal whenever the
     *  tunnel state flips - the signature move of the approved design. */
    private val onKapoStateChanged: () -> Unit = {
        binding.bgStateOn.animate()
            .alpha(if (KapoState.connected) 1f else 0f)
            .setDuration(900L)
            .start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        KapoState.addListener(onKapoStateChanged)
        binding.bgStateOn.alpha = if (KapoState.connected) 1f else 0f

        activeTag = savedInstanceState?.getString(KEY_ACTIVE) ?: TAG_HOME

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, HomeFragment(), TAG_HOME)
                .commit()
        }

        // Custom unread dot over the profile tab (Material badges mis-anchor
        // under the AppCompat theme, which is what produced the stray gray dot).
        val prefs = getSharedPreferences("kapo_prefs", MODE_PRIVATE)
        binding.navDot.visibility =
            if (prefs.getBoolean("notifs_seen_v1", false)) android.view.View.GONE
            else android.view.View.VISIBLE

        binding.bottomNav.setOnItemSelectedListener { item ->
            val tag = when (item.itemId) {
                R.id.nav_home -> TAG_HOME
                R.id.nav_servers -> TAG_SERVERS
                R.id.nav_stats -> TAG_STATS
                R.id.nav_profile -> TAG_PROFILE
                else -> null
            }
            if (tag != null) { showTab(tag); true } else false
        }
    }

    /**
     * Tabs are hidden and shown, never replaced.
     *
     * The old code called replace(), which tears the fragment down and builds it
     * again on every switch - so Home lost its connection state, its timer and its
     * byte counters each time you looked at Servers. Hiding keeps them alive and
     * running in the background.
     */
    private fun showTab(tag: String) {
        if (tag == activeTag && supportFragmentManager.findFragmentByTag(tag) != null) return

        val fm = supportFragmentManager
        val tx = fm.beginTransaction()

        fm.findFragmentByTag(activeTag)?.let { tx.hide(it) }

        val existing = fm.findFragmentByTag(tag)
        if (existing != null) {
            tx.show(existing)
        } else {
            tx.add(R.id.fragmentContainer, newFragment(tag), tag)
        }

        tx.commit()
        activeTag = tag
    }

    /** Called by ProfileFragment once notifications have been opened. */
    fun clearProfileBadge() {
        binding.navDot.visibility = android.view.View.GONE
    }

    private fun newFragment(tag: String): Fragment = when (tag) {
        TAG_SERVERS -> ServersFragment()
        TAG_STATS -> StatsFragment()
        TAG_PROFILE -> ProfileFragment()
        else -> HomeFragment()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ACTIVE, activeTag)
    }

    override fun onDestroy() {
        KapoState.removeListener(onKapoStateChanged)
        super.onDestroy()
    }
}
