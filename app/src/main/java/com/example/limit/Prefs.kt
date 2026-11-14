package com.example.limit

import android.content.Context

/** Stan limitu trzymany na dysku, żeby przetrwał ubicie procesu i restart telefonu. */
object Prefs {
    /** Hasło odblokowujące / anulujące limit. */
    const val PASSWORD = "1987"

    private fun sp(c: Context) = c.getSharedPreferences("limit", Context.MODE_PRIVATE)

    fun endTime(c: Context): Long = sp(c).getLong("end", 0L)
    fun setEndTime(c: Context, v: Long) = sp(c).edit().putLong("end", v).apply()

    fun locked(c: Context): Boolean = sp(c).getBoolean("locked", false)
    fun setLocked(c: Context, v: Boolean) = sp(c).edit().putBoolean("locked", v).apply()

    /** Czyści tylko stan limitu - flaga filmiku powitalnego zostaje. */
    fun clear(c: Context) = sp(c).edit().remove("end").remove("locked").apply()

    fun welcomeSeen(c: Context): Boolean = sp(c).getBoolean("welcome_seen", false)
    fun setWelcomeSeen(c: Context) = sp(c).edit().putBoolean("welcome_seen", true).apply()
}
