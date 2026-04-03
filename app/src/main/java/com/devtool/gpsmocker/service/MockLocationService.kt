package com.devtool.gpsmocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.devtool.gpsmocker.ui.MainActivity
import kotlinx.coroutines.*
import org.osmdroid.util.GeoPoint
import java.time.Instant
import kotlin.math.*

class MockLocationService : Service() {

    companion object {
        const val CHANNEL_ID      = "gps_mocker_channel"
        const val NOTIFICATION_ID = 1001
        const val PROVIDER        = LocationManager.GPS_PROVIDER
        const val INTERVAL_MS     = 250L

        // 1 step = 0.4 m
        const val METRES_PER_STEP = 0.4
    }

    inner class LocalBinder : Binder() {
        fun getService(): MockLocationService = this@MockLocationService
    }

    private val binder       = LocalBinder()
    private lateinit var locationManager: LocationManager
    private var mockJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var isRunning = false
        private set
    var speedMps  = 1.5
    var currentLocation: GeoPoint? = null
        private set

    private var stepAccumulator = 0.0
    var sessionSteps = 0
        private set

    // Callback: pt, segIdx, totalSegs, newSteps, stepStartTime, stepEndTime
    var onLocationUpdate: ((GeoPoint, Int, Int, Int, Instant, Instant) -> Unit)? = null
    var onRouteFinished:  (() -> Unit)?                                           = null

    // ── Lifecycle ─────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasLocation) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, buildNotification("GPS Mocker 待機中"))
        return START_STICKY
    }

    // ── Mode 1: Fixed point ───────────────────────

    fun startFixedPoint(point: GeoPoint) {
        stopMocking()
        setupTestProvider()
        isRunning       = true
        currentLocation = point
        sessionSteps    = 0
        stepAccumulator = 0.0
        mockJob = serviceScope.launch {
            while (isActive) {
                injectLocation(point.latitude, point.longitude, 0f)
                delay(INTERVAL_MS)
            }
        }
        updateNotification("📍 固定點模擬中")
    }

    // ── Mode 2: Multi-waypoint route ──────────────

    fun startRoute(waypoints: List<GeoPoint>, looping: Boolean = false) {
        if (waypoints.size < 2) return
        stopMocking()
        setupTestProvider()
        isRunning       = true
        sessionSteps    = 0
        stepAccumulator = 0.0

        mockJob = serviceScope.launch {
            do {
                for (segIdx in 0 until waypoints.size - 1) {
                    val segStart   = waypoints[segIdx]
                    val segEnd     = waypoints[segIdx + 1]
                    val segDist    = haversineMeters(segStart, segEnd)
                    val stepM      = speedMps * (INTERVAL_MS / 1000.0)
                    val totalTicks = max(1, (segDist / stepM).toInt())

                    for (tick in 0..totalTicks) {
                        if (!isActive) return@launch

                        val tickStart = Instant.now()

                        val frac = if (totalTicks == 0) 1.0 else tick.toDouble() / totalTicks
                        val lat  = segStart.latitude  + (segEnd.latitude  - segStart.latitude)  * frac
                        val lon  = segStart.longitude + (segEnd.longitude - segStart.longitude) * frac
                        val pt   = GeoPoint(lat, lon)

                        // Accumulate steps with fractional precision
                        stepAccumulator += stepM / METRES_PER_STEP
                        val newSteps     = stepAccumulator.toInt()
                        stepAccumulator -= newSteps
                        sessionSteps    += newSteps

                        currentLocation = pt
                        injectLocation(lat, lon, speedMps.toFloat())

                        delay(INTERVAL_MS)

                        val tickEnd = Instant.now()

                        // Pass real tick timestamps so HC/Google Fit gets correct time range
                        onLocationUpdate?.invoke(pt, segIdx, waypoints.size - 1,
                                                 newSteps, tickStart, tickEnd)
                    }
                }

                if (!looping) {
                    val last = waypoints.last()
                    currentLocation = last
                    onLocationUpdate?.invoke(last, waypoints.size - 2, waypoints.size - 1,
                                             0, Instant.now(), Instant.now())
                    onRouteFinished?.invoke()
                    while (isActive) {
                        injectLocation(last.latitude, last.longitude, 0f)
                        delay(INTERVAL_MS)
                    }
                }
            } while (isActive && looping)
        }

        val totalDist = (0 until waypoints.size - 1)
            .sumOf { haversineMeters(waypoints[it], waypoints[it + 1]) }
        val etaSec = (totalDist / speedMps).toInt()
        updateNotification("🚶 路線模擬 ${"%.1f".format(speedMps)} m/s｜預計 ${etaSec}s")
    }

    // ── Stop ──────────────────────────────────────

    fun stopMocking() {
        mockJob?.cancel()
        mockJob   = null
        isRunning = false
        try { locationManager.removeTestProvider(PROVIDER) } catch (_: Exception) {}
        updateNotification("GPS Mocker 待機中")
    }

    // ── Helpers ───────────────────────────────────

    private fun setupTestProvider() {
        try { locationManager.removeTestProvider(PROVIDER) } catch (_: Exception) {}
        locationManager.addTestProvider(
            PROVIDER,
            false, false, false, false, true, true, true,
            android.location.provider.ProviderProperties.POWER_USAGE_LOW,
            android.location.provider.ProviderProperties.ACCURACY_FINE
        )
        locationManager.setTestProviderEnabled(PROVIDER, true)
    }

    private fun injectLocation(lat: Double, lon: Double, speed: Float) {
        try {
            val loc = Location(PROVIDER).apply {
                latitude             = lat
                longitude            = lon
                altitude             = 10.0
                accuracy             = 1.0f
                this.speed           = speed
                bearing              = 0f
                time                 = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            locationManager.setTestProviderLocation(PROVIDER, loc)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val r    = 6371000.0
        val lat1 = Math.toRadians(a.latitude);  val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude  - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h    = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    // ── Notification ──────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "GPS Mocker", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "GPS 模擬服務通知" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Mocker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopMocking()
        serviceScope.cancel()
        super.onDestroy()
    }
}
