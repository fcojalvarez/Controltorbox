package com.controltorbox.app

import android.content.SharedPreferences

/** SharedPreferences en memoria para las pruebas. */
class MemoryPrefs : SharedPreferences {
    private val data = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)
    override fun getString(key: String, defValue: String?) = data[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = (data[key] as Set<String>?)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int) = data[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = data[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = data[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = data[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in data
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        override fun putString(k: String, v: String?) = apply { changes[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { changes[k] = v?.toSet() }
        override fun putInt(k: String, v: Int) = apply { changes[k] = v }
        override fun putLong(k: String, v: Long) = apply { changes[k] = v }
        override fun putFloat(k: String, v: Float) = apply { changes[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { changes[k] = v }
        override fun remove(k: String) = apply { removed += k }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) data.clear()
            removed.forEach { data.remove(it) }
            data.putAll(changes)
        }
    }
}
