package com.aaotg.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        col.addView(TextView(this).apply { text = "AA Mouse OTG"; textSize = 24f })
        val perm = Button(this).apply {
            text = "Izinkan tampil di atas aplikasi lain"
            setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        }
        val start = Button(this).apply {
            text = "Mulai tangkap keyboard/mouse"
            setOnClickListener { startForegroundService(Intent(this@MainActivity, CaptureService::class.java)) }
        }
        col.addView(perm); col.addView(start)
        setContentView(col)
    }
}
