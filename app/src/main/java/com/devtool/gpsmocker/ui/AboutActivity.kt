package com.devtool.gpsmocker.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.devtool.gpsmocker.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "關於"
            setDisplayHomeAsUpEnabled(true)
        }

        binding.btnContactDev.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:minicore@Claude.ai")
                putExtra(Intent.EXTRA_SUBJECT, "GPS Mocker 回饋")
            }
            startActivity(Intent.createChooser(intent, "傳送郵件"))
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
