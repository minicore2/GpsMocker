package com.devtool.gpsmocker.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.devtool.gpsmocker.R

/**
 * Health Connect requires every app to handle ACTION_SHOW_PERMISSIONS_RATIONALE.
 * This Activity explains WHY we need step read/write access.
 * Without this Activity declared in the manifest, HC will refuse to show
 * the permission dialog.
 */
class PermissionRationaleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_rationale)

        findViewById<Button>(R.id.btnRationaleOk).setOnClickListener {
            finish()
        }
    }
}
