package com.devtool.gpsmocker.ui.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.devtool.gpsmocker.databinding.FragmentSettingsBinding
import com.devtool.gpsmocker.ui.SharedViewModel
import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.devtool.gpsmocker.utils.*
import org.osmdroid.util.GeoPoint
import kotlinx.coroutines.*

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b
    private val vm by lazy { SharedViewModel.get(requireActivity()) }
    private var fetchJob: Job? = null

    private val hcPermLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        lifecycleScope.launch {
            if (granted.containsAll(HealthConnectHelper.PERMISSIONS)) {
                StepManager.setPreferredBackend(requireContext(), StepManager.Backend.HEALTH_CONNECT)
                vm.refreshSteps()
                toast("✅ Health Connect 授權成功")
            } else {
                toast("⚠️ 未完全授權，改用本地計步")
            }
        }
    }

    private val csvPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> importCsv(uri) }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(i, c, false)
        return _b!!.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        loadSettings()
        setupListeners()
        refreshDbStats()
    }

    private fun loadSettings() {
        val binding = _b ?: return
        val ctx = context ?: return
        val speed = AppPrefs.loadSpeed(ctx)
        updateSpeedLabel(speed)
        binding.seekSpeed.progress = speedToSlider(speed)
        when (AppPrefs.loadStartFrom(ctx)) {
            AppPrefs.StartFrom.NONE          -> binding.rgStartFrom.check(binding.rbStartNone.id)
            AppPrefs.StartFrom.LAST_POSITION -> binding.rgStartFrom.check(binding.rbStartLast.id)
            AppPrefs.StartFrom.DEVICE_GPS    -> binding.rgStartFrom.check(binding.rbStartGPS.id)
        }
        val lp = LastPositionStore.load(ctx)
        binding.tvLastPosition.text = lp?.let {
            "上次位置：${"%.6f".format(it.latitude)}, ${"%.6f".format(it.longitude)}"
        } ?: "（尚無上次位置記錄）"
        val preferHC = AppPrefs.loadStepBackend(ctx)
        binding.rgStepBackend.check(if (preferHC) binding.rbStepHC.id else binding.rbStepLocal.id)
    }

    private fun setupListeners() {
        val binding = _b ?: return

        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                val ctx = context ?: return
                val spd = sliderToSpeed(p); updateSpeedLabel(spd); AppPrefs.saveSpeed(ctx, spd)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.rgStartFrom.setOnCheckedChangeListener { _, id ->
            val ctx = context ?: return@setOnCheckedChangeListener
            val sf = when (id) {
                binding.rbStartLast.id -> AppPrefs.StartFrom.LAST_POSITION
                binding.rbStartGPS.id  -> AppPrefs.StartFrom.DEVICE_GPS
                else                   -> AppPrefs.StartFrom.NONE
            }
            AppPrefs.saveStartFrom(ctx, sf)

            // Immediately jump the map to the selected position
            when (sf) {
                AppPrefs.StartFrom.LAST_POSITION -> {
                    val last = LastPositionStore.load(ctx)
                    if (last != null) vm.requestMapJump(last)
                    else toast("尚無上次位置記錄")
                }
                AppPrefs.StartFrom.DEVICE_GPS -> {
                    // Try fast path first (last-known), then request live fix
                    val fast = getLastKnownGps(ctx)
                    if (fast != null) {
                        vm.requestMapJump(fast)
                    } else {
                        toast("正在取得 GPS 位置…")
                        requestLiveGps(ctx)
                    }
                }
                AppPrefs.StartFrom.NONE -> {
                    // Jump back to default (Taipei 101)
                    vm.requestMapJump(GeoPoint(25.0330, 121.5654))
                }
            }
        }

        binding.rgStepBackend.setOnCheckedChangeListener { _, id ->
            val ctx = context ?: return@setOnCheckedChangeListener
            val useHc = id == binding.rbStepHC.id
            AppPrefs.saveStepBackend(ctx, useHc); vm.setPreferHC(ctx, useHc)
        }

        binding.btnConnectHC.setOnClickListener {
            lifecycleScope.launch {
                val ctx = context ?: return@launch
                if (!HealthConnectHelper.isAvailable(ctx)) { toast("此裝置不支援 Health Connect"); return@launch }
                if (HealthConnectHelper.hasPermissions(ctx)) { vm.refreshSteps(); toast("Health Connect 已授權") }
                else hcPermLauncher.launch(HealthConnectHelper.PERMISSIONS)
            }
        }

        binding.btnFetchLandmarks.setOnClickListener { startFetch() }
        binding.btnStopFetch.setOnClickListener     { stopFetch() }
        binding.btnExportCsv.setOnClickListener     { exportCsv() }
        binding.btnImportCsv.setOnClickListener     { openCsvPicker() }
    }

    // ── Fetch ─────────────────────────────────────────────────────────────────

    private fun startFetch() {
        val ctx = context ?: return
        setFetchUI(running = true)
        _b?.tvFetchProgress?.text = "連線中…"
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        WikiFetcher.onProgress = { continent, fetched, target, msg ->
            mainHandler.post {
                val binding = _b ?: return@post
                binding.tvFetchProgress?.text = msg
                binding.progressFetch?.progress =
                    if (target > 0) (fetched * 100 / target).coerceIn(0, 100) else 0
                if (continent == "完成") {
                    setFetchUI(running = false); refreshDbStats(); WikiFetcher.onProgress = null
                }
            }
        }
        fetchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                WikiFetcher.fetchAll(ctx)
            } catch (e: CancellationException) {
                mainHandler.post { _b?.tvFetchProgress?.text = "已停止" }
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "fetchAll failed", e)
                mainHandler.post { toast("抓取失敗：${e.message}"); setFetchUI(running = false) }
            }
        }
    }

    private fun stopFetch() {
        fetchJob?.cancel(); fetchJob = null; WikiFetcher.onProgress = null
        setFetchUI(running = false); _b?.tvFetchProgress?.text = "已停止"; refreshDbStats()
    }

    private fun setFetchUI(running: Boolean) {
        val binding = _b ?: return
        binding.btnFetchLandmarks?.isEnabled = !running
        binding.btnStopFetch?.visibility  = if (running) View.VISIBLE else View.GONE
        binding.progressFetch?.visibility = if (running) View.VISIBLE else View.GONE
        binding.tvFetchProgress?.visibility = if (running) View.VISIBLE else View.GONE
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private fun exportCsv() {
        val ctx = context ?: return
        _b?.tvCsvStatus?.visibility = View.VISIBLE
        _b?.tvCsvStatus?.text = "匯出中…"
        _b?.btnExportCsv?.isEnabled = false
        lifecycleScope.launch {
            val uri = CsvHelper.exportToCsv(ctx)
            val binding = _b ?: return@launch
            binding.btnExportCsv?.isEnabled = true
            if (uri == null) { binding.tvCsvStatus?.text = "⚠️ 資料庫是空的，無法匯出"; return@launch }
            binding.tvCsvStatus?.text = "✅ 匯出完成，選擇傳送方式…"
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "PikminGPSMocker 地標資料")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "匯出 CSV 到…"
            ))
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    private fun openCsvPicker() {
        csvPickerLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv","text/plain","application/octet-stream"))
            addCategory(Intent.CATEGORY_OPENABLE)
        })
    }

    private fun importCsv(uri: android.net.Uri) {
        val ctx = context ?: return
        _b?.tvCsvStatus?.visibility = View.VISIBLE
        _b?.tvCsvStatus?.text = "匯入中…"
        _b?.btnImportCsv?.isEnabled = false
        lifecycleScope.launch {
            val r = CsvHelper.importFromCsv(ctx, uri)
            val binding = _b ?: return@launch
            binding.btnImportCsv?.isEnabled = true
            binding.tvCsvStatus?.text = buildString {
                append("✅ 匯入完成\n新增：${r.inserted} 筆  略過（重複）：${r.skipped} 筆")
                if (r.errors > 0) append("  錯誤：${r.errors} 筆")
            }
            refreshDbStats()
        }
    }

    // ── DB stats ──────────────────────────────────────────────────────────────

    private fun refreshDbStats() {
        val ctx = context ?: return
        lifecycleScope.launch {
            val count = WikiLandmarkHelper.dbCount(ctx)
            val stats = WikiLandmarkHelper.dbStats(ctx)
            val binding = _b ?: return@launch
            binding.tvLandmarkDbStats?.text = if (count == 0)
                "資料庫：空（點下方按鈕從 Wikipedia 抓取）"
            else
                "資料庫：共 $count 個地點\n${stats.joinToString("  ") { "${it.continent}:${it.cnt}" }}"
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateSpeedLabel(mps: Double) {
        _b?.tvSpeedValue?.text = "${"%.1f".format(mps)} m/s  " + when {
            mps < 1.0  -> "🐢 爬行"
            mps < 2.0  -> "🚶 步行"
            mps < 4.0  -> "🏃 慢跑"
            mps < 8.0  -> "🚴 騎車"
            mps < 14.0 -> "🚗 開車"
            else       -> "✈️ 飛行"
        }
    }

    private fun sliderToSpeed(p: Int) = 0.5 * Math.pow(40.0, p / 100.0)
    private fun speedToSlider(s: Double) =
        (Math.log(s / 0.5) / Math.log(40.0) * 100).toInt().coerceIn(0, 100)

    /** Fast path: returns cached GPS location without blocking */
    private fun getLastKnownGps(ctx: android.content.Context): GeoPoint? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return null
        val lm  = ctx.getSystemService(LocationManager::class.java)
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        return loc?.let { GeoPoint(it.latitude, it.longitude) }
    }

    /**
     * Request a single live GPS fix (fires once, then removes itself).
     * Used as fallback when getLastKnownLocation returns null.
     */
    private fun requestLiveGps(ctx: android.content.Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            toast("需要位置權限才能取得目前 GPS"); return
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: android.location.Location) {
                vm.requestMapJump(GeoPoint(loc.latitude, loc.longitude))
                lm.removeUpdates(this)
            }
        }
        try {
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null)
        } catch (e: Exception) {
            // Fallback to network provider
            try { lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, null) }
            catch (e2: Exception) { toast("無法取得 GPS 位置") }
        }
    }

    private fun toast(m: String) { context?.let { Toast.makeText(it, m, Toast.LENGTH_SHORT).show() } }

    override fun onDestroyView() { WikiFetcher.onProgress = null; _b = null; super.onDestroyView() }
}
