package com.devtool.gpsmocker.ui.fragments

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.devtool.gpsmocker.databinding.FragmentSettingsBinding
import com.devtool.gpsmocker.ui.SharedViewModel
import com.devtool.gpsmocker.utils.*
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

    // ── Settings load ─────────────────────────────

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

    // ── Listeners ─────────────────────────────────

    private fun setupListeners() {
        val binding = _b ?: return

        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                val ctx = context ?: return
                val spd = sliderToSpeed(p)
                updateSpeedLabel(spd)
                AppPrefs.saveSpeed(ctx, spd)
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
        }

        binding.rgStepBackend.setOnCheckedChangeListener { _, id ->
            val ctx = context ?: return@setOnCheckedChangeListener
            val useHc = id == binding.rbStepHC.id
            AppPrefs.saveStepBackend(ctx, useHc)
            vm.setPreferHC(ctx, useHc)
        }

        binding.btnConnectHC.setOnClickListener {
            lifecycleScope.launch {
                val ctx = context ?: return@launch
                if (!HealthConnectHelper.isAvailable(ctx)) {
                    toast("此裝置不支援 Health Connect"); return@launch
                }
                if (HealthConnectHelper.hasPermissions(ctx)) {
                    vm.refreshSteps(); toast("Health Connect 已授權")
                } else {
                    hcPermLauncher.launch(HealthConnectHelper.PERMISSIONS)
                }
            }
        }

        // ── Landmark DB fetch ──────────────────────
        binding.btnFetchLandmarks.setOnClickListener { startFetch() }
        binding.btnStopFetch.setOnClickListener     { stopFetch() }
    }

    // ── Landmark DB fetch ─────────────────────────

    private fun startFetch() {
        val ctx = context ?: return
        setFetchUI(running = true)
        _b?.tvFetchProgress?.text = "準備抓取…"

        WikiFetcher.onProgress = { continent, fetched, target, msg ->
            activity?.runOnUiThread {
                val binding = _b ?: return@runOnUiThread
                binding.tvFetchProgress?.text = msg
                val pct = if (target > 0) (fetched * 100 / target).coerceIn(0, 100) else 0
                binding.progressFetch?.progress = pct
                if (continent == "完成") {
                    setFetchUI(running = false)
                    refreshDbStats()
                    WikiFetcher.onProgress = null
                }
            }
        }

        fetchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                WikiFetcher.fetchAll(ctx)
            } catch (e: CancellationException) {
                // user stopped
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    toast("抓取失敗：${e.message}")
                    setFetchUI(running = false)
                }
            }
        }
    }

    private fun stopFetch() {
        fetchJob?.cancel()
        fetchJob = null
        WikiFetcher.onProgress = null
        setFetchUI(running = false)
        _b?.tvFetchProgress?.text = "已停止"
        refreshDbStats()
    }

    private fun setFetchUI(running: Boolean) {
        val binding = _b ?: return
        binding.btnFetchLandmarks?.isEnabled = !running
        binding.btnStopFetch?.visibility =
            if (running) android.view.View.VISIBLE else android.view.View.GONE
        binding.progressFetch?.visibility =
            if (running) android.view.View.VISIBLE else android.view.View.GONE
        binding.tvFetchProgress?.visibility =
            if (running) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun refreshDbStats() {
        val ctx = context ?: return
        lifecycleScope.launch {
            val count = WikiLandmarkHelper.dbCount(ctx)
            val stats = WikiLandmarkHelper.dbStats(ctx)
            val binding = _b ?: return@launch
            if (count == 0) {
                binding.tvLandmarkDbStats?.text = "資料庫：空（點下方按鈕從 Wikipedia 抓取）"
            } else {
                val detail = stats.joinToString("  ") { "${it.continent}:${it.cnt}" }
                binding.tvLandmarkDbStats?.text = "資料庫：共 $count 個地點\n$detail"
            }
        }
    }

    // ── Helpers ───────────────────────────────────

    private fun updateSpeedLabel(mps: Double) {
        val tag = when {
            mps < 1.0  -> "🐢 爬行"
            mps < 2.0  -> "🚶 步行"
            mps < 4.0  -> "🏃 慢跑"
            mps < 8.0  -> "🚴 騎車"
            mps < 14.0 -> "🚗 開車"
            else       -> "✈️ 飛行"
        }
        _b?.tvSpeedValue?.text = "${"%.1f".format(mps)} m/s  $tag"
    }

    private fun sliderToSpeed(p: Int): Double = 0.5 * Math.pow(40.0, p / 100.0)
    private fun speedToSlider(spd: Double): Int =
        (Math.log(spd / 0.5) / Math.log(40.0) * 100).toInt().coerceIn(0, 100)

    private fun toast(m: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        WikiFetcher.onProgress = null
        _b = null
        super.onDestroyView()
    }
}
