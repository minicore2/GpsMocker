package com.devtool.gpsmocker.utils

import android.content.Context
import org.osmdroid.util.GeoPoint

/**
 * Persists the last simulated GPS position so the next session
 * can optionally resume from there.
 */
object LastPositionStore {
    private const val PREFS = "last_position"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_HAS = "has_position"

    fun save(context: Context, point: GeoPoint) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAT, point.latitude.toString())
            .putString(KEY_LON, point.longitude.toString())
            .putBoolean(KEY_HAS, true)
            .apply()
    }

    fun load(context: Context): GeoPoint? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_HAS, false)) return null
        return try {
            GeoPoint(
                p.getString(KEY_LAT, "0")!!.toDouble(),
                p.getString(KEY_LON, "0")!!.toDouble()
            )
        } catch (_: Exception) { null }
    }

    fun has(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HAS, false)

    fun clear(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HAS, false).apply()
}
