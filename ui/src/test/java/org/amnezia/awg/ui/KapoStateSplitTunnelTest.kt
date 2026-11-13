package org.amnezia.awg.ui

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KapoStateSplitTunnelTest {
    /** Just enough SharedPreferences for KapoState: a map, written on apply(). */
    private class FakePrefs : SharedPreferences {
        val data = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String?, defValue: String?) = data[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
            (data[key] as MutableSet<String>?) ?: defValues
        override fun getInt(key: String?, defValue: Int) = data[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long) = data[key] as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float) = data[key] as Float? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = data[key] as Boolean? ?: defValue
        override fun contains(key: String?) = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = HashMap<String, Any?>()
            var clear = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.let { HashSet(it) } }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) data.clear()
                for ((k, v) in pending) if (v == null) data.remove(k) else data[k] = v
            }
        }
    }

    @After
    fun reset() = KapoState.panicReset()

    @Test
    fun choiceSurvivesAProcessRestart() {
        val prefs = FakePrefs()
        KapoState.attachStore(prefs)
        KapoState.setSplitTunnelApps(setOf("com.example.bank", "com.example.maps"), excluded = false)

        // A new process: memory back to its defaults, then the store is read again.
        KapoState.panicReset()
        assertTrue(KapoState.splitTunnelApps.isEmpty())
        KapoState.attachStore(prefs)

        assertEquals(setOf("com.example.bank", "com.example.maps"), KapoState.splitTunnelApps)
        assertFalse(KapoState.splitTunnelExcluded)
    }

    @Test
    fun nothingSavedMeansAllAppsUseTheTunnel() {
        KapoState.attachStore(FakePrefs())
        assertTrue(KapoState.splitTunnelApps.isEmpty())
        assertTrue(KapoState.splitTunnelExcluded)
    }

    @Test
    fun aWipedStoreForgetsTheChoice() {
        val prefs = FakePrefs()
        KapoState.attachStore(prefs)
        KapoState.setSplitTunnelApps(setOf("com.example.chat"), excluded = true)
        prefs.edit().clear().commit()   // what the panic wipe and logout do
        KapoState.panicReset()
        KapoState.attachStore(prefs)
        assertTrue(KapoState.splitTunnelApps.isEmpty())
    }
}
