package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPrefsCandidatesTest {

    private class DummySharedPreferences : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue ?: true
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = DummyEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class DummyEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = this
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?) = this
        override fun clear() = this
        override fun commit() = true
        override fun apply() {}
    }

    @Test
    fun testTwoRowCandidateBarIsRegistered() {
        val sp = DummySharedPreferences()
        val prefs = AppPrefs(sp)

        println("Candidates managedPreferences: ${prefs.candidates.managedPreferences.keys}")
        println("Candidates managedPreferencesUi: ${prefs.candidates.managedPreferencesUi.map { it.key }}")

        assertTrue(
            "two_row_candidate_bar must be registered in managedPreferences",
            prefs.candidates.managedPreferences.containsKey("two_row_candidate_bar")
        )
        assertTrue(
            "two_row_candidate_bar must be registered in managedPreferencesUi",
            prefs.candidates.managedPreferencesUi.any { it.key == "two_row_candidate_bar" }
        )
    }
}
