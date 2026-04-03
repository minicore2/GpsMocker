package com.devtool.gpsmocker.utils

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simple local step counter backed by SharedPreferences.
 * Resets automatically when the date changes.
 */
object LocalStepStore {

    private const val PREFS = "gps_mocker_steps"
    private const val KEY_DATE  = "step_date"
    private const val KEY_STEPS = "step_count"
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun today() = dateFmt.format(Date())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Returns today's locally stored step count (resets at midnight). */
    fun getTodaySteps(context: Context): Long {
        val p = prefs(context)
        val savedDate = p.getString(KEY_DATE, "")
        return if (savedDate == today()) {
            p.getLong(KEY_STEPS, 0L)
        } else {
            // New day — reset
            p.edit().putString(KEY_DATE, today()).putLong(KEY_STEPS, 0L).apply()
            0L
        }
    }

    /** Add [delta] steps to today's local count and return new total. */
    fun addSteps(context: Context, delta: Int): Long {
        if (delta <= 0) return getTodaySteps(context)
        val p = prefs(context)
        val savedDate = p.getString(KEY_DATE, "")
        val current = if (savedDate == today()) p.getLong(KEY_STEPS, 0L) else 0L
        val newTotal = current + delta
        p.edit()
            .putString(KEY_DATE, today())
            .putLong(KEY_STEPS, newTotal)
            .apply()
        return newTotal
    }
}
