package com.devtool.gpsmocker.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.preference.PreferenceManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.devtool.gpsmocker.R
import com.devtool.gpsmocker.databinding.ActivityMainBinding
import com.devtool.gpsmocker.service.MockLocationService
import com.devtool.gpsmocker.utils.HealthConnectHelper
import com.devtool.gpsmocker.utils.LocalStepStore
import com.devtool.gpsmocker.utils.StepManager
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── Service ───────────────────────────────────
    private var mockService: MockLocationService? = null
    private var serviceBound = false

    // ── Mode ──────────────────────────────────────
    enum class Mode { FIXED, ROUTE }
    private var mode = Mode.FIXED

    // ── Map overlays ──────────────────────────────
    private var fixedMarker: Marker? = null
    private val waypoints       = mutableListOf<GeoPoint>()
    private val waypointMarkers = mutableListOf<Marker>()
    private val routeLines      = mutableListOf<Polyline>()
    private var movingMarker:   Marker? = null
    private var loopEnabled = false

    // ── Speed ─────────────────────────────────────
    private var currentSpeedMps = 1.5

    // ── Steps ─────────────────────────────────────
    private var baseSteps    = 0L
    private var sessionSteps = 0
    private var pendingWrite = 0
    companion object { const val WRITE_EVERY_N = 20 }

    // ── Health Connect permission contract ────────
    // Must use PermissionController.createRequestPermissionResultContract()
    // This is the ONLY correct way to request HC permissions
    private val hcPermLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        lifecycleScope.launch {
            if (granted.containsAll(HealthConnectHelper.PERMISSIONS)) {
                toast("✅ Health Connect 授權成功")
                initStepsBackend()
            } else {
                toast("⚠️ Health Connect 未完全授權，改用本地計步")
                StepManager.setPreferredBackend(this@MainActivity, StepManager.Backend.LOCAL)
                initStepsBackend()
            }
        }
    }

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupModeToggle()
        setupSpeedSlider()
        setupButtons()
        requestLocationPermissionThenStart()
        lifecycleScope.launch { initStepsBackend() }
    }

    override fun onResume()  { super.onResume(); binding.map.onResume() }
    override fun onPause()   { super.onPause();  binding.map.onPause()  }
    override fun onDestroy() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        super.onDestroy()
    }

    // ─────────────────────────────────────────────
    // Steps backend
    // ─────────────────────────────────────────────
    private suspend fun initStepsBackend() {
        runOnUiThread { binding.tvFitSteps.text = "👟 步數：初始化中…" }
        StepManager.init(this)
        val steps = StepManager.readTodaySteps(this)
        baseSteps    = if (steps < 0) LocalStepStore.getTodaySteps(this) else steps
        sessionSteps = 0
        runOnUiThread { updateStepDisplay() }
    }

    private fun updateStepDisplay() {
        val total = baseSteps + sessionSteps
        binding.tvFitSteps.text = "${StepManager.backendLabel}：$total 步（+$sessionSteps）"
    }

    /** 🔄 button — show backend selector */
    private fun showStepSettings() {
        val hcAvailable = HealthConnectHelper.isAvailable(this)
        val current     = StepManager.getPreferredBackend(this)

        val options = if (hcAvailable) {
            arrayOf("❤️ Health Connect（同步健康 App）", "💾 本地計步（無需授權）")
        } else {
            arrayOf("❤️ Health Connect（此裝置不支援）", "💾 本地計步（無需授權）")
        }

        AlertDialog.Builder(this, R.style.AlertDialogDark)
            .setTitle("步數來源設定")
            .setSingleChoiceItems(
                options,
                if (current == StepManager.Backend.HEALTH_CONNECT) 0 else 1
            ) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> selectHealthConnect(hcAvailable)
                    1 -> {
                        StepManager.setPreferredBackend(this, StepManager.Backend.LOCAL)
                        lifecycleScope.launch { initStepsBackend() }
                        toast("已切換為本地計步")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectHealthConnect(available: Boolean) {
        if (!available) {
            toast("此裝置不支援 Health Connect")
            return
        }
        StepManager.setPreferredBackend(this, StepManager.Backend.HEALTH_CONNECT)
        lifecycleScope.launch {
            if (HealthConnectHelper.hasPermissions(this@MainActivity)) {
                // Already authorized
                initStepsBackend()
            } else {
                // Launch the correct HC permission request
                hcPermLauncher.launch(HealthConnectHelper.PERMISSIONS)
            }
        }
    }

    private fun flushSteps(delta: Int) {
        if (delta <= 0) return
        lifecycleScope.launch { StepManager.addSteps(this@MainActivity, delta) }
    }

    // ─────────────────────────────────────────────
    // Map
    // ─────────────────────────────────────────────
    private fun setupMap() {
        binding.map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(25.0330, 121.5654))
        }
        val tap = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { handleMapTap(p); return true }
            override fun longPressHelper(p: GeoPoint) = false
        }
        binding.map.overlays.add(0, MapEventsOverlay(tap))
    }

    private fun handleMapTap(point: GeoPoint) {
        when (mode) {
            Mode.FIXED -> { placeFixedMarker(point); binding.tvCoords.text = "📍 ${fmtCoord(point)}" }
            Mode.ROUTE -> addWaypoint(point)
        }
    }

    // ─────────────────────────────────────────────
    // Waypoints
    // ─────────────────────────────────────────────
    private fun addWaypoint(point: GeoPoint) {
        waypoints.add(point)
        val marker = Marker(binding.map).apply {
            position = point
            title    = if (waypoints.size == 1) "🟢 起點" else "🔵 P${waypoints.size}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = if (waypoints.size == 1)
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_marker_start)
            else
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_marker_mid)
        }
        waypointMarkers.add(marker)
        binding.map.overlays.add(marker)
        if (waypoints.size >= 2) {
            val line = Polyline(binding.map).apply {
                setPoints(listOf(waypoints[waypoints.size - 2], point))
                outlinePaint.color       = 0xFF1976D2.toInt()
                outlinePaint.strokeWidth = 8f
            }
            routeLines.add(line)
            binding.map.overlays.add(line)
        }
        refreshLastMarkerIcon()
        updateRouteInfo()
        binding.map.invalidate()
    }

    private fun removeLastWaypoint() {
        if (waypoints.isEmpty()) return
        waypoints.removeAt(waypoints.size - 1)
        binding.map.overlays.remove(waypointMarkers.removeAt(waypointMarkers.size - 1))
        if (routeLines.isNotEmpty())
            binding.map.overlays.remove(routeLines.removeAt(routeLines.size - 1))
        refreshLastMarkerIcon()
        updateRouteInfo()
        binding.map.invalidate()
    }

    private fun refreshLastMarkerIcon() {
        waypointMarkers.forEachIndexed { i, m ->
            m.icon = when {
                i == 0                                   -> ContextCompat.getDrawable(this, R.drawable.ic_marker_start)
                i == waypointMarkers.size - 1 && i > 0  -> ContextCompat.getDrawable(this, R.drawable.ic_marker_end)
                else                                     -> ContextCompat.getDrawable(this, R.drawable.ic_marker_mid)
            }
            m.title = when {
                i == 0                                   -> "🟢 起點"
                i == waypointMarkers.size - 1 && i > 0  -> "🔴 終點"
                else                                     -> "🔵 P${i + 1}"
            }
        }
    }

    private fun updateRouteInfo() {
        binding.tvCoords.text = when {
            waypoints.isEmpty() -> "點選地圖新增航點"
            waypoints.size == 1 -> "🟢 起點：${fmtCoord(waypoints[0])}\n繼續點選新增更多航點"
            else -> {
                val dist     = (0 until waypoints.size - 1).sumOf { haversineMeters(waypoints[it], waypoints[it + 1]) }
                val eta      = (dist / currentSpeedMps).toInt()
                val estSteps = (dist / MockLocationService.METRES_PER_STEP).toInt()
                "📍 ${waypoints.size} 個航點｜${"%.0f".format(dist)}m\n" +
                "速度 ${"%.1f".format(currentSpeedMps)} m/s｜${eta}s｜預計 ~$estSteps 步"
            }
        }
    }

    // ─────────────────────────────────────────────
    // Mode toggle
    // ─────────────────────────────────────────────
    private fun setupModeToggle() {
        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = if (checkedId == R.id.btnModeFixed) Mode.FIXED else Mode.ROUTE
            switchMode()
        }
        binding.toggleMode.check(R.id.btnModeFixed)
    }

    private fun switchMode() {
        stopAll(); clearAllOverlays()
        when (mode) {
            Mode.FIXED -> {
                binding.tvCoords.text   = "點選地圖設定固定位置"
                binding.tvModeHint.text = "模式一：固定座標"
                binding.btnUndoWp.isEnabled    = false
                binding.btnLoopRoute.isEnabled = false
            }
            Mode.ROUTE -> {
                binding.tvCoords.text   = "點選地圖新增航點（可新增多個中繼點）"
                binding.tvModeHint.text = "模式二：多航點路線"
                binding.btnUndoWp.isEnabled    = true
                binding.btnLoopRoute.isEnabled = true
            }
        }
    }

    // ─────────────────────────────────────────────
    // Speed slider
    // ─────────────────────────────────────────────
    private fun setupSpeedSlider() {
        updateSpeedLabel(sliderToSpeed(binding.seekSpeed.progress))
        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                currentSpeedMps = sliderToSpeed(progress)
                mockService?.speedMps = currentSpeedMps
                updateSpeedLabel(currentSpeedMps)
                if (mode == Mode.ROUTE && waypoints.size >= 2) updateRouteInfo()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar)  {}
        })
    }

    private fun sliderToSpeed(p: Int): Double = 0.5 * Math.pow(40.0, p / 100.0)

    private fun updateSpeedLabel(mps: Double) {
        binding.tvSpeed.text = "⚡ ${"%.1f".format(mps)} m/s  " + when {
            mps < 1.0  -> "🐢 爬行"
            mps < 2.0  -> "🚶 步行"
            mps < 4.0  -> "🏃 慢跑"
            mps < 8.0  -> "🚴 騎車"
            mps < 14.0 -> "🚗 開車"
            else       -> "✈️ 飛行"
        }
    }

    // ─────────────────────────────────────────────
    // Buttons
    // ─────────────────────────────────────────────
    private fun setupButtons() {
        binding.btnStart.setOnClickListener     { startMocking() }
        binding.btnStop.setOnClickListener      { stopAll() }
        binding.btnClear.setOnClickListener     { stopAll(); clearAllOverlays(); switchMode() }
        binding.btnUndoWp.setOnClickListener    { removeLastWaypoint() }
        binding.btnLoopRoute.setOnCheckedChangeListener { _, checked -> loopEnabled = checked }
        binding.btnAbout.setOnClickListener     { startActivity(Intent(this, AboutActivity::class.java)) }
        // 🔄 now opens step source settings
        binding.btnRefreshFit.setOnClickListener { showStepSettings() }
    }

    // ─────────────────────────────────────────────
    // Start / Stop
    // ─────────────────────────────────────────────
    private fun startMocking() {
        val svc = mockService ?: run { toast("服務尚未連接，請稍候"); return }
        svc.speedMps = currentSpeedMps
        sessionSteps = 0; pendingWrite = 0
        updateStepDisplay()

        when (mode) {
            Mode.FIXED -> {
                val pt = fixedMarker?.position ?: run { toast("請先點選地圖設定位置"); return }
                svc.startFixedPoint(pt)
                setRunningUI(true)
                toast("✅ 固定座標注入開始")
            }
            Mode.ROUTE -> {
                if (waypoints.size < 2) { toast("至少需要 2 個航點"); return }
                placeMovingMarker(waypoints.first())
                svc.onLocationUpdate = { pt, segIdx, totalSegs, newSteps ->
                    runOnUiThread {
                        movingMarker?.position = pt
                        binding.map.invalidate()
                        if (newSteps > 0) {
                            sessionSteps += newSteps
                            pendingWrite += newSteps
                            updateStepDisplay()
                            if (pendingWrite >= WRITE_EVERY_N) {
                                flushSteps(pendingWrite); pendingWrite = 0
                            }
                        }
                        binding.tvCoords.text =
                            "🚶 ${fmtCoord(pt)}\n段落 ${segIdx + 1}/$totalSegs｜" +
                            "${"%.1f".format(currentSpeedMps)} m/s｜本次 $sessionSteps 步"
                    }
                }
                svc.onRouteFinished = {
                    runOnUiThread {
                        if (pendingWrite > 0) { flushSteps(pendingWrite); pendingWrite = 0 }
                        setRunningUI(false)
                        toast("🏁 路線完成！共 $sessionSteps 步")
                        lifecycleScope.launch { initStepsBackend() }
                    }
                }
                svc.startRoute(waypoints.toList(), loopEnabled)
                setRunningUI(true)
                toast("✅ 路線模擬開始（${waypoints.size} 個航點）")
            }
        }
    }

    private fun stopAll() {
        if (pendingWrite > 0) { flushSteps(pendingWrite); pendingWrite = 0 }
        mockService?.stopMocking()
        movingMarker?.let { binding.map.overlays.remove(it) }
        movingMarker = null
        binding.map.invalidate()
        setRunningUI(false)
    }

    private fun setRunningUI(running: Boolean) {
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled  = running
        binding.statusDot.setBackgroundResource(
            if (running) R.drawable.dot_green else R.drawable.dot_grey
        )
        binding.tvStatus.text = if (running) "模擬中" else "待機"
    }

    // ─────────────────────────────────────────────
    // Markers
    // ─────────────────────────────────────────────
    private fun placeFixedMarker(point: GeoPoint) {
        fixedMarker?.let { binding.map.overlays.remove(it) }
        fixedMarker = Marker(binding.map).apply {
            position = point; title = "固定位置"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        binding.map.overlays.add(fixedMarker)
        binding.map.invalidate()
    }

    private fun placeMovingMarker(point: GeoPoint) {
        movingMarker?.let { binding.map.overlays.remove(it) }
        movingMarker = Marker(binding.map).apply {
            position = point; title = "目前位置"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        binding.map.overlays.add(movingMarker)
        binding.map.invalidate()
    }

    private fun clearAllOverlays() {
        fixedMarker?.let { binding.map.overlays.remove(it) }; fixedMarker = null
        movingMarker?.let { binding.map.overlays.remove(it) }; movingMarker = null
        waypointMarkers.forEach { binding.map.overlays.remove(it) }; waypointMarkers.clear()
        routeLines.forEach { binding.map.overlays.remove(it) }; routeLines.clear()
        waypoints.clear(); binding.map.invalidate()
    }

    // ─────────────────────────────────────────────
    // Service
    // ─────────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            mockService = (binder as MockLocationService.LocalBinder).getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            mockService = null; serviceBound = false
        }
    }

    private fun bindMockService() {
        val intent = Intent(this, MockLocationService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requestLocationPermissionThenStart() {
        val perms = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            bindMockService()
        } else {
            locationPermLauncher.launch(perms.toTypedArray())
        }
    }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            bindMockService()
        } else {
            toast("⚠️ 需要位置權限才能啟動 GPS 模擬服務")
            binding.btnStart.isEnabled = false
        }
    }

    // ─────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────
    private fun fmtCoord(p: GeoPoint) =
        "${"%.6f".format(p.latitude)}, ${"%.6f".format(p.longitude)}"

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val r    = 6371000.0
        val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h    = Math.sin(dLat/2).pow(2) + Math.cos(lat1)*Math.cos(lat2)*Math.sin(dLon/2).pow(2)
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    private fun Double.pow(n: Int) = Math.pow(this, n.toDouble())
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
