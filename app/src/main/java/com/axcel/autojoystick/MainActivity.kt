package com.axcel.autojoystick

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var logs: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logs = findViewById(R.id.logs)

        findViewById<Button>(R.id.btn_perm_overlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else Toast.makeText(this, "Overlay already granted", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_perm_a11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant overlay first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            try {
                val i = Intent(this, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                log("Overlay shown — set coord & press START there")
            } catch (t: Throwable) {
                log("ERR: ${t.message}")
                Toast.makeText(this, "Failed: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            try { stopService(Intent(this, OverlayService::class.java)); log("STOP") } catch (_: Throwable) {}
        }

        // Open overlay immediately if permission already granted
        if (Settings.canDrawOverlays(this)) {
            try {
                val i = Intent(this, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } catch (_: Throwable) {}
        }
    }

    fun log(line: String) { runOnUiThread { logs.append("\n" + line) } }
}
