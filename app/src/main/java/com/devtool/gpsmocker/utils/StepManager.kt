package com.devtool.gpsmocker.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StepManager {

    private const val TAG   = "StepManager"
    private const val PREFS = "step_manager_prefs"
    private const val KEY_PREFERRED = "preferred_backend"

    enum class Backend { HEALTH_CONNECT, LOCAL }

    // Runtime active backend (may differ from preferred if HC unavailable)
    var activeBackend: Backend = Backend.LOCAL
        private set

    var backendLabel: String = "本地計步"
        private set

    // ── User preference ───────────────────────────

    fun getPreferredBackend(context: Context): Backend {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED, Backend.HEALTH_CONNECT.name)
        return try { Backend.valueOf(saved!!) } catch (_: Exception) { Backend.HEALTH_CONNECT }
    }

    fun setPreferredBackend(context: Context, backend: Backend) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFERRED, backend.name).apply()
        Log.d(TAG, "Preferred backend set to $backend")
    }

    // ── Init ──────────────────────────────────────

    /**
     * Determine which backend to actually use.
     * Respects the user's preference but degrades gracefully.
     */
    suspend fun init(context: Context): Backend = withContext(Dispatchers.IO) {
        val preferred = getPreferredBackend(context)

        activeBackend = when {
            preferred == Backend.HEALTH_CONNECT &&
            HealthConnectHelper.isAvailable(context) &&
            HealthConnectHelper.hasPermissions(context) -> {
                backendLabel = "Health Connect ❤️"
                Backend.HEALTH_CONNECT
            }
            preferred == Backend.HEALTH_CONNECT &&
            HealthConnectHelper.isAvailable(context) &&
            !HealthConnectHelper.hasPermissions(context) -> {
                // Available but no permission yet — stay local until granted
                backendLabel = "本地計步 💾（HC 待授權）"
                Backend.LOCAL
            }
            else -> {
                backendLabel = "本地計步 💾"
                Backend.LOCAL
            }
        }
        Log.d(TAG, "Active backend: $activeBackend ($backendLabel)")
        activeBackend
    }

    // ── Read ──────────────────────────────────────

    suspend fun readTodaySteps(context: Context): Long = withContext(Dispatchers.IO) {
        when (activeBackend) {
            Backend.HEALTH_CONNECT -> {
                val s = HealthConnectHelper.readTodaySteps(context)
                if (s < 0) {
                    Log.w(TAG, "HC read failed → downgrade to local")
                    activeBackend = Backend.LOCAL
                    backendLabel  = "本地計步 💾（HC 失敗）"
                    LocalStepStore.getTodaySteps(context)
                } else s
            }
            Backend.LOCAL -> LocalStepStore.getTodaySteps(context)
        }
    }

    // ── Write ─────────────────────────────────────

    /**
     * Add steps. Always writes to local store.
     * Also writes to HC when active backend is HC.
     * Returns new local total.
     */
    suspend fun addSteps(context: Context, delta: Int): Long = withContext(Dispatchers.IO) {
        if (delta <= 0) return@withContext LocalStepStore.getTodaySteps(context)
        val localTotal = LocalStepStore.addSteps(context, delta)
        if (activeBackend == Backend.HEALTH_CONNECT) {
            val ok = HealthConnectHelper.writeSteps(context, delta)
            if (!ok) {
                activeBackend = Backend.LOCAL
                backendLabel  = "本地計步 💾（HC 寫入失敗）"
            }
        }
        localTotal
    }
}
