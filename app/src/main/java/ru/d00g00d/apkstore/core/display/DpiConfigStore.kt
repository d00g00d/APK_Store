package ru.d00g00d.apkstore.core.display

import android.content.Context

object DpiConfigStore {
    private const val PREFS_NAME = "ApkStoreDpiPrefs"

    fun getDpi(context: Context, packageName: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(packageName, 0)
    }

    fun setDpi(context: Context, packageName: String, dpi: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(packageName, dpi).apply()
    }
}
