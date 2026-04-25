package com.devtool.gpsmocker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.devtool.gpsmocker.R
import com.devtool.gpsmocker.databinding.ActivityMainBinding
import com.devtool.gpsmocker.ui.fragments.MapFragment
import com.devtool.gpsmocker.ui.fragments.SettingsFragment
import com.devtool.gpsmocker.ui.fragments.StatsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm by lazy { SharedViewModel.get(this) }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val ok = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                 results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) vm.init()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupViewPager()
        setupBottomNav()
        requestPermissions()
        vm.init()
    }

    private fun setupViewPager() {
        b.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(pos: Int): Fragment = when (pos) {
                0    -> MapFragment()
                1    -> StatsFragment()
                2    -> SettingsFragment()
                else -> MapFragment()
            }
        }

        // Keep ALL fragments alive simultaneously — this is the key fix.
        // Without this, ViewPager2 destroys off-screen fragments' Views (and
        // triggers onDestroyView) when the user switches tabs, which previously
        // caused the service unbind and the subsequent NPE crash.
        b.viewPager.offscreenPageLimit = 2

        b.viewPager.isUserInputEnabled = false
    }

    private fun setupBottomNav() {
        b.bottomNav.setOnItemSelectedListener { item ->
            b.viewPager.currentItem = when (item.itemId) {
                R.id.nav_map      -> 0
                R.id.nav_stats    -> 1
                R.id.nav_settings -> 2
                else              -> 0
            }
            true
        }
        b.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(pos: Int) {
                b.bottomNav.selectedItemId = when (pos) {
                    0    -> R.id.nav_map
                    1    -> R.id.nav_stats
                    2    -> R.id.nav_settings
                    else -> R.id.nav_map
                }
            }
        })
    }

    private fun requestPermissions() {
        val perms = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) locationPermLauncher.launch(needed.toTypedArray())
    }
}
