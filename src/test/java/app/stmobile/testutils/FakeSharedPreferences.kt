package app.stmobile.testutils

import android.content.SharedPreferences

/**
 * Lightweight, in-memory implementation of [SharedPreferences] for fast (<1ms) JVM unit tests.
 * Avoids any dependency on Robolectric or Mockito.
 */
class FakeSharedPreferences(
    initialValues: Map<String, Any> = emptyMap()
) : SharedPreferences {

    private val values = java.util.concurrent.ConcurrentHashMap<String, Any>(initialValues)
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String, defValue: String?): String? {
        return (values[key] as? String) ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        return (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int {
        return (values[key] as? Number)?.toInt() ?: defValue
    }

    override fun getLong(key: String, defValue: Long): Long {
        return (values[key] as? Number)?.toLong() ?: defValue
    }

    override fun getFloat(key: String, defValue: Float): Float {
        return (values[key] as? Number)?.toFloat() ?: defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return (values[key] as? Boolean) ?: defValue
    }

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners.remove(listener)
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val modifications = mutableMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            modifications[key] = value
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            modifications[key] = values?.toSet()
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            modifications[key] = value
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            modifications[key] = value
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            modifications[key] = value
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            modifications[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply {
            modifications[key] = this
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            synchronized(values) {
                if (clearRequested) {
                    values.clear()
                }
                for ((key, value) in modifications) {
                    if (value === this) {
                        values.remove(key)
                    } else if (value != null) {
                        values[key] = value
                    } else {
                        values.remove(key)
                    }
                }
            }
        }
    }
}
