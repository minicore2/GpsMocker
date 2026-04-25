package com.devtool.gpsmocker.ui.fragments

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.preference.PreferenceManager
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.devtool.gpsmocker.R
import com.devtool.gpsmocker.databinding.FragmentMapBinding
import com.devtool.gpsmocker.service.MockLocationService
import com.devtool.gpsmocker.ui.SearchResultAdapter
import com.devtool.gpsmocker.ui.SharedViewModel
import com.devtool.gpsmocker.utils.*
import java.time.Instant
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MapFragment : Fragment() {

    private var _b: FragmentMapBinding? = null
    // Safe accessor — returns null instead of crashing when view is destroyed
    private val b get() = _b

    private val vm by lazy { SharedViewModel.get(requireActivity()) }

    // ── Service ───────────────────────────────────
    // Keep service bound for the Fragment's lifetime (not just View lifetime)
    // so Tab switches don't disconnect us.
    private var svc: MockLocationService? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, binder: IBinder) {
            svc = (binder as MockLocationService.LocalBinder).getService()
            bound = true
            restoreSpeedFromPrefs()
            // If a simulation was already running when View was recreated, re-wire callbacks
            if (svc?.isRunning == true) rewireRunningCallbacks()
        }
        override fun onServiceDisconnected(n: ComponentName) {
            svc = null
            bound = false
        }
    }

    // Map state — survives View recreation
    enum class Mode { FIXED, ROUTE }
    private var mode = Mode.FIXED
    private var fixedMarker: Marker? = null
    private val waypoints       = mutableListOf<GeoPoint>()
    private val waypointMarkers = mutableListOf<Marker>()
    private val routeLines      = mutableListOf<Polyline>()
    private var movingMarker:   Marker? = null

    private var searchJob: Job? = null

    // Handler for posting UI updates from Service callbacks (which run on Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Lifecycle ─────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bind service at Fragment level — survives tab switches
        bindService()
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        Configuration.getInstance().load(
            requireContext(),
            PreferenceManager.getDefaultSharedPreferences(requireContext())
        )
        Configuration.getInstance().userAgentValue = requireContext().packageName
        _b = FragmentMapBinding.inflate(i, c, false)
        return _b!!.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        setupMap()
        setupSearch()
        setupModeToggle()
        setupButtons()
        // Restore running state indicator if service is already running
        if (svc?.isRunning == true) setRunning(true)
        vm.sessionSteps.observe(viewLifecycleOwner) { /* stats fragment handles display */ }
    }

    override fun onResume() {
        super.onResume()
        b?.mapView?.onResume()
        restoreSpeedFromPrefs()
    }

    override fun onPause() {
        super.onPause()
        b?.mapView?.onPause()
    }

    override fun onDestroyView() {
        // Clear binding but do NOT unbind service here —
        // the service must stay connected across tab switches.
        searchJob?.cancel()
        _b = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        // Only unbind when the Fragment itself is truly destroyed
        if (bound) {
            requireContext().unbindService(conn)
            bound = false
        }
        super.onDestroy()
    }

    // ── Map setup ─────────────────────────────────

    private fun setupMap() {
        val binding = b ?: return
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(25.0330, 121.5654))
        }
        val tap = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                handleMapTap(p); return true
            }
            override fun longPressHelper(p: GeoPoint) = false
        }
        binding.mapView.overlays.add(0, MapEventsOverlay(tap))
        binding.btnZoomIn.setOnClickListener  { b?.mapView?.controller?.zoomIn() }
        binding.btnZoomOut.setOnClickListener { b?.mapView?.controller?.zoomOut() }
    }

    private fun handleMapTap(point: GeoPoint) {
        when (mode) {
            Mode.FIXED -> {
                placeFixedMarker(point)
                b?.tvCoords?.text = "📍 ${fmtCoord(point)}"
            }
            Mode.ROUTE -> addWaypoint(point)
        }
    }

    // ── Search ────────────────────────────────────

    private fun setupSearch() {
        val binding = b ?: return
        val adapter = SearchResultAdapter { result ->
            b?.cardSearchResults?.visibility = View.GONE
            b?.etSearch?.clearFocus()
            hideSoftKeyboard()
            b?.mapView?.controller?.animateTo(result.point)
            b?.mapView?.controller?.setZoom(17.0)
            if (mode == Mode.FIXED) {
                placeFixedMarker(result.point)
                b?.tvCoords?.text = "📍 ${result.shortName}\n${fmtCoord(result.point)}"
            } else {
                addWaypoint(result.point)
            }
        }
        binding.rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSearchResults.adapter = adapter

        binding.btnSearch.setOnClickListener {
            triggerSearch(b?.etSearch?.text?.toString() ?: "")
        }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerSearch(b?.etSearch?.text?.toString() ?: ""); true
            } else false
        }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                if (s.isNullOrBlank()) this@MapFragment.b?.cardSearchResults?.visibility = View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun triggerSearch(query: String) {
        if (query.isBlank()) return
        searchJob?.cancel()
        b?.cardSearchResults?.visibility = View.VISIBLE
        searchJob = lifecycleScope.launch {
            val results = GeocoderHelper.search(query)
            if (!isAdded) return@launch   // Fragment detached — bail out
            if (results.isEmpty()) {
                b?.cardSearchResults?.visibility = View.GONE
                toast("找不到「$query」的結果")
            } else {
                (b?.rvSearchResults?.adapter as? SearchResultAdapter)?.submitList(results)
                b?.cardSearchResults?.visibility = View.VISIBLE
            }
        }
    }

    private fun hideSoftKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(b?.etSearch?.windowToken, 0)
    }

    // ── Mode toggle ───────────────────────────────

    private var suppressToggleListener = false

    private fun setupModeToggle() {
        val binding = b ?: return
        binding.toggleMode.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || suppressToggleListener) return@addOnButtonCheckedListener
            mode = if (id == R.id.btnModeFixed) Mode.FIXED else Mode.ROUTE
            switchMode()
        }
        // Suppress the listener during the programmatic initial check so we don't
        // trigger switchMode() → stopMocking() → clearAllOverlays() before the
        // service is connected and before the map is fully set up.
        suppressToggleListener = true
        binding.toggleMode.check(if (mode == Mode.FIXED) R.id.btnModeFixed else R.id.btnModeRoute)
        suppressToggleListener = false
        // Apply the correct initial UI state without triggering the full switchMode() flow
        applyModeUI()
    }

    private fun switchMode() {
        stopMocking()
        clearAllOverlays()
        applyModeUI()
    }

    /** Update UI labels/visibility for the current mode without side effects. */
    private fun applyModeUI() {
        when (mode) {
            Mode.FIXED -> {
                b?.tvCoords?.text = "點選地圖設定固定位置"
                b?.routeControlsRow?.visibility = View.GONE
            }
            Mode.ROUTE -> {
                b?.tvCoords?.text = "點選地圖新增航點"
                b?.routeControlsRow?.visibility = View.VISIBLE
            }
        }
    }

    // ── Buttons ───────────────────────────────────

    private fun setupButtons() {
        val binding = b ?: return
        binding.btnStartStop.setOnClickListener {
            if (svc?.isRunning == true) stopMocking() else startMocking()
        }
        binding.btnClear.setOnClickListener { stopMocking(); clearAllOverlays(); switchMode() }
        binding.btnUndoWp.setOnClickListener { removeLastWaypoint() }
        binding.cbLoop.isChecked = AppPrefs.loadLoop(context ?: return)
        binding.cbLoop.setOnCheckedChangeListener { _, c ->
            context?.let { AppPrefs.saveLoop(it, c) }
        }
    }

    // ── Start / Stop ──────────────────────────────

    private fun startMocking() {
        val s = svc ?: run { toast("服務未就緒"); return }
        val ctx = context ?: return
        s.speedMps = AppPrefs.loadSpeed(ctx)
        vm.resetSession()

        when (mode) {
            Mode.FIXED -> {
                val pt = fixedMarker?.position ?: run { toast("請先點選地圖設定位置"); return }
                s.startFixedPoint(pt)
                setRunning(true)
            }
            Mode.ROUTE -> {
                if (waypoints.size < 2) { toast("至少需要 2 個航點"); return }

                // Determine override start point — always resolve to GeoPoint? safely
                val overrideStart: GeoPoint? = resolveStartPoint()

                val looping = b?.cbLoop?.isChecked ?: AppPrefs.loadLoop(ctx)
                val startPt = overrideStart ?: waypoints.first()

                placeMovingMarker(startPt)
                wireCallbacks(s)
                s.startRoute(waypoints.toList(), looping, overrideStart)
                setRunning(true)
            }
        }
    }

    /**
     * Wire service callbacks — safe to call multiple times.
     * Uses lambda captures that check `_b != null` before touching views.
     */
    private fun wireCallbacks(s: MockLocationService) {
        s.onLocationUpdate = { pt, segIdx, totalSegs, newSteps, ts, te ->
            // Service coroutine runs on Dispatchers.Default; use mainHandler to post to UI thread.
            // Do NOT use activity?.runOnUiThread — activity reference may be stale.
            mainHandler.post {
                val binding = _b ?: return@post   // view destroyed → skip silently
                movingMarker?.position = pt
                binding.mapView.invalidate()
                vm.updateLocation(pt, segIdx, totalSegs, newSteps, ts, te)
                binding.tvCoords.text = "🚶 ${fmtCoord(pt)}\n段落 ${segIdx + 1}/$totalSegs"
            }
        }
        s.onRouteFinished = {
            mainHandler.post {
                val ctx = context ?: return@post   // fragment detached → skip
                val last = s.currentLocation ?: waypoints.lastOrNull() ?: return@post
                LastPositionStore.save(ctx, last)
                vm.onRouteFinished()
                setRunning(false)
                val steps = vm.sessionSteps.value ?: 0
                Toast.makeText(ctx, "🏁 路線完成！共 $steps 步", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Called when the View is recreated but the service is already running
     * (e.g. user switched tab and came back).
     */
    private fun rewireRunningCallbacks() {
        val s = svc ?: return
        wireCallbacks(s)
        // Restore UI to running state
        if (isAdded && _b != null) setRunning(true)
    }

    /**
     * Determine the override start point based on user's setting.
     * Returns null to mean "use the first map waypoint as-is".
     * Never throws — all failure paths return null with a toast.
     */
    private fun resolveStartPoint(): GeoPoint? {
        val ctx = context ?: return null
        return when (AppPrefs.loadStartFrom(ctx)) {
            AppPrefs.StartFrom.LAST_POSITION -> {
                val last = LastPositionStore.load(ctx)
                if (last == null) toast("無上次位置，使用地圖起點")
                last
            }
            AppPrefs.StartFrom.DEVICE_GPS -> {
                val gps = getDeviceGpsPosition()
                if (gps == null) toast("無法取得 GPS，使用地圖起點")
                gps
            }
            AppPrefs.StartFrom.NONE -> null
        }
    }

    private fun stopMocking() {
        // context may be null if called before attachment or after detachment
        val ctx = context
        if (ctx != null) {
            svc?.currentLocation?.let { LastPositionStore.save(ctx, it) }
        }
        vm.flushSteps()
        svc?.onLocationUpdate = null
        svc?.onRouteFinished  = null
        svc?.stopMocking()
        val map = _b?.mapView
        movingMarker?.let { map?.overlays?.remove(it) }
        movingMarker = null
        map?.invalidate()
        setRunning(false)
    }

    private fun setRunning(running: Boolean) {
        val binding = _b ?: return
        val ctx = context ?: return
        binding.btnStartStop.text = if (running) "⏹ 停止" else "▶ 開始"
        binding.btnStartStop.backgroundTintList = ctx.getColorStateList(
            if (running) android.R.color.holo_red_dark else R.color.accent
        )
        binding.btnStartStop.setTextColor(ctx.getColor(R.color.bg_dark))
    }

    private fun getDeviceGpsPosition(): GeoPoint? {
        val ctx = context ?: return null
        if (ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = ctx.getSystemService(android.location.LocationManager::class.java)
        val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        return loc?.let { GeoPoint(it.latitude, it.longitude) }
    }

    private fun restoreSpeedFromPrefs() {
        val ctx = context ?: return
        svc?.speedMps = AppPrefs.loadSpeed(ctx)
    }

    // ── Waypoints ─────────────────────────────────

    private fun addWaypoint(point: GeoPoint) {
        val binding = b ?: return
        waypoints.add(point)
        val marker = Marker(binding.mapView).apply {
            position = point
            title = if (waypoints.size == 1) "🟢 起點" else "🔵 P${waypoints.size}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(
                context ?: return@apply,
                if (waypoints.size == 1) R.drawable.ic_marker_start else R.drawable.ic_marker_mid
            )
        }
        waypointMarkers.add(marker)
        binding.mapView.overlays.add(marker)
        if (waypoints.size >= 2) {
            val line = Polyline(binding.mapView).apply {
                setPoints(listOf(waypoints[waypoints.size - 2], point))
                outlinePaint.color = 0xFF1976D2.toInt()
                outlinePaint.strokeWidth = 8f
            }
            routeLines.add(line)
            binding.mapView.overlays.add(line)
        }
        refreshMarkerIcons()
        updateRouteInfo()
        binding.mapView.invalidate()
    }

    private fun removeLastWaypoint() {
        if (waypoints.isEmpty()) return
        val binding = b ?: return
        waypoints.removeAt(waypoints.size - 1)
        binding.mapView.overlays.remove(waypointMarkers.removeAt(waypointMarkers.size - 1))
        if (routeLines.isNotEmpty())
            binding.mapView.overlays.remove(routeLines.removeAt(routeLines.size - 1))
        refreshMarkerIcons()
        updateRouteInfo()
        binding.mapView.invalidate()
    }

    private fun refreshMarkerIcons() {
        val ctx = context ?: return
        waypointMarkers.forEachIndexed { i, m ->
            m.icon = ContextCompat.getDrawable(ctx, when {
                i == 0                                  -> R.drawable.ic_marker_start
                i == waypointMarkers.size - 1 && i > 0 -> R.drawable.ic_marker_end
                else                                    -> R.drawable.ic_marker_mid
            })
            m.title = when {
                i == 0                                  -> "🟢 起點"
                i == waypointMarkers.size - 1 && i > 0 -> "🔴 終點"
                else                                    -> "🔵 P${i + 1}"
            }
        }
    }

    private fun updateRouteInfo() {
        b?.tvCoords?.text = when {
            waypoints.isEmpty() -> "點選地圖新增航點"
            waypoints.size == 1 ->
                "🟢 起點：${fmtCoord(waypoints[0])}\n繼續點選新增更多航點"
            else -> {
                val dist = (0 until waypoints.size - 1)
                    .sumOf { haversineMeters(waypoints[it], waypoints[it + 1]) }
                val eta = (dist / AppPrefs.loadSpeed(context ?: return)).toInt()
                val estSteps = (dist / MockLocationService.METRES_PER_STEP).toInt()
                "📍 ${waypoints.size} 個航點｜${"%.0f".format(dist)}m\n${eta}s｜~$estSteps 步"
            }
        }
    }

    private fun placeFixedMarker(point: GeoPoint) {
        val binding = b ?: return
        fixedMarker?.let { binding.mapView.overlays.remove(it) }
        fixedMarker = Marker(binding.mapView).apply {
            position = point; title = "固定位置"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        binding.mapView.overlays.add(fixedMarker)
        binding.mapView.invalidate()
    }

    private fun placeMovingMarker(point: GeoPoint) {
        val binding = b ?: return
        movingMarker?.let { binding.mapView.overlays.remove(it) }
        movingMarker = Marker(binding.mapView).apply {
            position = point; title = "目前位置"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        binding.mapView.overlays.add(movingMarker)
        binding.mapView.invalidate()
    }

    private fun clearAllOverlays() {
        val binding = _b
        fixedMarker?.let { binding?.mapView?.overlays?.remove(it) }; fixedMarker = null
        movingMarker?.let { binding?.mapView?.overlays?.remove(it) }; movingMarker = null
        waypointMarkers.forEach { binding?.mapView?.overlays?.remove(it) }; waypointMarkers.clear()
        routeLines.forEach { binding?.mapView?.overlays?.remove(it) }; routeLines.clear()
        waypoints.clear()
        binding?.mapView?.invalidate()
    }

    // ── Service ───────────────────────────────────

    private fun bindService() {
        val ctx = context ?: return
        val intent = Intent(ctx, MockLocationService::class.java)
        // Start the service so it stays alive as a foreground service,
        // but only if it's not already running — avoid redundant starts.
        try { ctx.startForegroundService(intent) } catch (_: Exception) {}
        ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    // ── Utils ─────────────────────────────────────

    private fun fmtCoord(p: GeoPoint) =
        "${"%.6f".format(p.latitude)}, ${"%.6f".format(p.longitude)}"

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val r    = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h    = Math.sin(dLat / 2).pow(2) +
                   Math.cos(Math.toRadians(a.latitude)) *
                   Math.cos(Math.toRadians(b.latitude)) *
                   Math.sin(dLon / 2).pow(2)
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    private fun Double.pow(n: Int) = Math.pow(this, n.toDouble())
    private fun toast(m: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
    }
}
