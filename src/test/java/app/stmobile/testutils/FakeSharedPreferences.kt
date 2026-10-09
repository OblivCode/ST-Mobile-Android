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

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String, defValue: String?): String? {
        val v = values[key] ?: return defValue
        return v as String
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        val v = values[key] ?: return defValues
        return (v as Set<String>).toMutableSet()
    }

    override fun getInt(key: String, defValue: Int): Int {
        val v = values[key] ?: return defValue
        return v as Int
    }

    override fun getLong(key: String, defValue: Long): Long {
        val v = values[key] ?: return defValue
        return v as Long
    }

    override fun getFloat(key: String, defValue: Float): Float {
        val v = values[key] ?: return defValue
        return v as Float
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val v = values[key] ?: return defValue
        return v as Boolean
    }

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private val listeners = java.util.WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Any>()
    private val CONTENT = Any()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) {
            synchronized(listeners) {
                listeners.put(listener, CONTENT)
            }
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) {
            synchronized(listeners) {
                listeners.remove(listener)
            }
        }
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
            val changedKeys = mutableListOf<String>()
            synchronized(values) {
                if (clearRequested) {
                    for (k in values.keys) {
                        if (!changedKeys.contains(k)) {
                            changedKeys.add(k)
                        }
                    }
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
                    if (!changedKeys.contains(key)) {
                        changedKeys.add(key)
                    }
                }
                clearRequested = false
                modifications.clear()
            }
            val listenersToNotify = synchronized(listeners) {
                listeners.keys.toList()
            }
            for (key in changedKeys) {
                for (listener in listenersToNotify) {
                    listener.onSharedPreferenceChanged(this@FakeSharedPreferences, key)
                }
            }
        }
    }
}
