package com.devtool.gpsmocker.utils

import android.content.Context

/** Persists all user settings so they survive app restarts. */
object AppPrefs {
    private const val PREFS = "app_prefs"

    // Keys
    private const val KEY_SPEED          = "speed_mps"
    private const val KEY_LOOP           = "loop_enabled"
    private const val KEY_START_FROM     = "start_from"   // "last", "gps", "none"
    private const val KEY_STEP_BACKEND   = "step_backend" // "hc", "local"

    enum class StartFrom { LAST_POSITION, DEVICE_GPS, NONE }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var speedMps: Double
        get() = _speed
        set(v) { _speed = v }
    private var _speed = 1.5

    fun saveSpeed(ctx: Context, v: Double) { p(ctx).edit().putFloat(KEY_SPEED, v.toFloat()).apply(); _speed = v }
    fun loadSpeed(ctx: Context): Double    { _speed = p(ctx).getFloat(KEY_SPEED, 1.5f).toDouble(); return _speed }

    fun saveLoop(ctx: Context, v: Boolean)  = p(ctx).edit().putBoolean(KEY_LOOP, v).apply()
    fun loadLoop(ctx: Context): Boolean     = p(ctx).getBoolean(KEY_LOOP, false)

    fun saveStartFrom(ctx: Context, v: StartFrom) = p(ctx).edit().putString(KEY_START_FROM, v.name).apply()
    fun loadStartFrom(ctx: Context): StartFrom = try {
        StartFrom.valueOf(p(ctx).getString(KEY_START_FROM, StartFrom.NONE.name)!!)
    } catch (_: Exception) { StartFrom.NONE }

    fun saveStepBackend(ctx: Context, hc: Boolean) = p(ctx).edit().putBoolean(KEY_STEP_BACKEND, hc).apply()
    fun loadStepBackend(ctx: Context): Boolean = p(ctx).getBoolean(KEY_STEP_BACKEND, true)
}
