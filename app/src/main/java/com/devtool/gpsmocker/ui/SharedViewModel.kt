package com.devtool.gpsmocker.ui

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.*
import com.devtool.gpsmocker.utils.StepManager
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.time.Instant

/** Shared state between Map, Stats, and Settings fragments. */
class SharedViewModel(private val app: android.app.Application) : AndroidViewModel(app) {

    companion object {
        fun get(activity: FragmentActivity) =
            ViewModelProvider(
                activity,
                ViewModelProvider.AndroidViewModelFactory(activity.application)
            )[SharedViewModel::class.java]
    }

    // ── Observables ───────────────────────────────
    val todaySteps    = MutableLiveData(0L)
    val sessionSteps  = MutableLiveData(0)
    val backendLabel  = MutableLiveData("本地計步 💾")
    val currentCoord  = MutableLiveData<GeoPoint?>()
    val isRunning     = MutableLiveData(false)
    val segmentText   = MutableLiveData("")
    val sessionDistM  = MutableLiveData(0.0)

    // ── Step batching ─────────────────────────────
    private var pendingWrite = 0
    private var windowStart  = Instant.now()
    private var windowEnd    = Instant.now()
    private val WRITE_EVERY  = 20

    // ── Init ──────────────────────────────────────

    fun init() {
        viewModelScope.launch {
            StepManager.init(app)
            val s = StepManager.readTodaySteps(app)
            todaySteps.postValue(s)
            backendLabel.postValue(StepManager.backendLabel)
        }
    }

    // ── Session ───────────────────────────────────

    fun resetSession() {
        sessionSteps.value = 0
        sessionDistM.value = 0.0
        pendingWrite       = 0
        segmentText.value  = ""
        isRunning.value    = true
    }

    fun updateLocation(
        pt: GeoPoint, segIdx: Int, totalSegs: Int,
        newSteps: Int, ts: Instant, te: Instant
    ) {
        currentCoord.postValue(pt)
        isRunning.postValue(true)
        segmentText.postValue("段落 ${segIdx + 1}/$totalSegs")

        if (newSteps > 0) {
            if (pendingWrite == 0) windowStart = ts
            windowEnd = te

            sessionSteps.postValue((sessionSteps.value ?: 0) + newSteps)
            sessionDistM.postValue((sessionDistM.value ?: 0.0) + newSteps * 0.4)
            todaySteps.postValue((todaySteps.value ?: 0) + newSteps)

            pendingWrite += newSteps
            if (pendingWrite >= WRITE_EVERY) {
                flushInternal(pendingWrite, windowStart, windowEnd)
                pendingWrite = 0
            }
        }
    }

    fun flushSteps() {
        if (pendingWrite > 0) {
            flushInternal(pendingWrite, windowStart, windowEnd)
            pendingWrite = 0
        }
    }

    private fun flushInternal(delta: Int, start: Instant, end: Instant) {
        viewModelScope.launch {
            StepManager.addSteps(app, delta, start, end)
        }
    }

    fun onRouteFinished() {
        flushSteps()
        isRunning.postValue(false)
    }

    // ── Step backend ─────────────────────────────

    fun refreshSteps() {
        viewModelScope.launch {
            StepManager.init(app)
            val s = StepManager.readTodaySteps(app)
            todaySteps.postValue(s)
            backendLabel.postValue(StepManager.backendLabel)
        }
    }

    fun setPreferHC(ctx: Context, useHc: Boolean) {
        StepManager.setPreferredBackend(
            ctx,
            if (useHc) StepManager.Backend.HEALTH_CONNECT else StepManager.Backend.LOCAL
        )
    }
}
