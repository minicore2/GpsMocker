package com.devtool.gpsmocker.ui.fragments

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import com.devtool.gpsmocker.databinding.FragmentStatsBinding
import com.devtool.gpsmocker.ui.SharedViewModel
import com.devtool.gpsmocker.utils.AppPrefs

class StatsFragment : Fragment() {
    private var _b: FragmentStatsBinding? = null
    private val b get() = _b
    private val vm by lazy { SharedViewModel.get(requireActivity()) }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentStatsBinding.inflate(i, c, false); return _b!!.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        vm.todaySteps.observe(viewLifecycleOwner)   { _b?.tvTodaySteps?.text = it.toString() }
        vm.sessionSteps.observe(viewLifecycleOwner) { _b?.tvSessionSteps?.text = it.toString() }
        vm.backendLabel.observe(viewLifecycleOwner) { _b?.tvBackendBadge?.text = it }
        vm.isRunning.observe(viewLifecycleOwner) { running ->
            _b?.statusDot?.setBackgroundResource(
                if (running) com.devtool.gpsmocker.R.drawable.dot_green
                else         com.devtool.gpsmocker.R.drawable.dot_grey
            )
            _b?.tvStatusLabel?.text = if (running) "模擬中" else "待機"
        }
        vm.currentCoord.observe(viewLifecycleOwner) {
            _b?.tvCurrentCoord?.text = it?.let { p ->
                "${"%.6f".format(p.latitude)}, ${"%.6f".format(p.longitude)}"
            } ?: "—"
        }
        vm.segmentText.observe(viewLifecycleOwner) { _b?.tvSegmentInfo?.text = it }
        vm.sessionDistM.observe(viewLifecycleOwner) {
            _b?.tvSessionDist?.text = if (it < 1000) "${"%.0f".format(it)} m"
                                   else "${"%.2f".format(it/1000)} km"
        }

        val spd = AppPrefs.loadSpeed(requireContext())
        _b?.tvSpeed?.text = "${"%.1f".format(spd)} m/s"

        _b?.btnRefreshSteps?.setOnClickListener { vm.refreshSteps() }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
