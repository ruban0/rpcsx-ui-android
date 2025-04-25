
package net.rpcsx.utils

import android.content.Context
import android.content.SharedPreferences

object GeneralSettings {

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    operator fun get(key: String): Any? = with(prefs) {
        when {
            contains(key) -> {
                all[key]
            }
            else -> null
        }
    }

    fun setValue(key: String, value: Any?) {
        with(prefs.edit()) {
            when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
                is Long -> putLong(key, value)
                else -> throw IllegalArgumentException("Unsupported type: ${value::class.java.name}")
            }
            apply()
        }
    }

    operator fun set(key: String, value: Any?) {
        setValue(key, value)
    }

    fun sync() {
        prefs.edit().commit()
    }
}
