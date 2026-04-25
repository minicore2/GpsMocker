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
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b
    private val vm by lazy { SharedViewModel.get(requireActivity()) }

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
        _b = FragmentSettingsBinding.inflate(i, c, false); return _b!!.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val binding = _b ?: return
        val ctx = context ?: return

        // Speed
        val speed = AppPrefs.loadSpeed(ctx)
        updateSpeedLabel(speed)
        binding.seekSpeed.progress = speedToSlider(speed)

        // Start-from radio
        when (AppPrefs.loadStartFrom(ctx)) {
            AppPrefs.StartFrom.NONE          -> binding.rgStartFrom.check(binding.rbStartNone.id)
            AppPrefs.StartFrom.LAST_POSITION -> binding.rgStartFrom.check(binding.rbStartLast.id)
            AppPrefs.StartFrom.DEVICE_GPS    -> binding.rgStartFrom.check(binding.rbStartGPS.id)
        }

        // Last position display
        val lp = LastPositionStore.load(ctx)
        binding.tvLastPosition.text = lp?.let {
            "上次位置：${"%.6f".format(it.latitude)}, ${"%.6f".format(it.longitude)}"
        } ?: "（尚無上次位置記錄）"

        // Step backend radio
        val preferHC = AppPrefs.loadStepBackend(ctx)
        binding.rgStepBackend.check(if (preferHC) binding.rbStepHC.id else binding.rbStepLocal.id)
    }

    private fun setupListeners() {
        val binding = _b ?: return

        // Speed slider
        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                val ctx = context ?: return
                val spd = sliderToSpeed(p)
                updateSpeedLabel(spd)
                AppPrefs.saveSpeed(ctx, spd)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar)  {}
        })

        // Start-from
        binding.rgStartFrom.setOnCheckedChangeListener { _, id ->
            val ctx = context ?: return@setOnCheckedChangeListener
            val sf = when (id) {
                binding.rbStartLast.id -> AppPrefs.StartFrom.LAST_POSITION
                binding.rbStartGPS.id  -> AppPrefs.StartFrom.DEVICE_GPS
                else                   -> AppPrefs.StartFrom.NONE
            }
            AppPrefs.saveStartFrom(ctx, sf)
        }

        // Step backend
        binding.rgStepBackend.setOnCheckedChangeListener { _, id ->
            val ctx = context ?: return@setOnCheckedChangeListener
            val useHc = id == binding.rbStepHC.id
            AppPrefs.saveStepBackend(ctx, useHc)
            vm.setPreferHC(ctx, useHc)
        }

        // HC connect button
        binding.btnConnectHC.setOnClickListener {
            lifecycleScope.launch {
                val ctx = context ?: return@launch
                if (!HealthConnectHelper.isAvailable(ctx)) {
                    toast("此裝置不支援 Health Connect")
                    return@launch
                }
                if (HealthConnectHelper.hasPermissions(ctx)) {
                    vm.refreshSteps(); toast("Health Connect 已授權")
                } else {
                    hcPermLauncher.launch(HealthConnectHelper.PERMISSIONS)
                }
            }
        }
    }

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
    private fun speedToSlider(spd: Double): Int = (Math.log(spd / 0.5) / Math.log(40.0) * 100).toInt().coerceIn(0, 100)
    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
