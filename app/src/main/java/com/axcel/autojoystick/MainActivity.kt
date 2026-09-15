package com.axcel.autojoystick

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var logs: TextView
    private val CAPTURE_REQ = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)
            logs = findViewById(R.id.logs)
            Prefs.loadJoystick(this)
        } catch (t: Throwable) {
            Log.e("AutoJoystick", "MainActivity onCreate crash", t)
            try {
                Toast.makeText(this, "Init error: ${t.message}", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
            finish(); return
        }

        try {
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
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), CAPTURE_REQ)
                    log("Overlay shown; grant screen capture for live OCR")
                } catch (t: Throwable) {
                    log("ERR start: ${t.message}")
                    Toast.makeText(this, "Failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }

            findViewById<Button>(R.id.btn_stop).setOnClickListener {
                try { stopService(Intent(this, OverlayService::class.java)) } catch (_: Throwable) {}
                try { stopService(Intent(this, CaptureService::class.java)) } catch (_: Throwable) {}
                log("STOP")
            }
        } catch (t: Throwable) {
            Log.e("AutoJoystick", "wiring buttons failed", t)
            Toast.makeText(this, "Wiring failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            if (requestCode == CAPTURE_REQ && resultCode == RESULT_OK && data != null) {
                CaptureService.pendingResultCode = resultCode
                CaptureService.pendingData = data
                val i = Intent(this, CaptureService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                log("screen capture ON (live OCR)")
            } else if (requestCode == CAPTURE_REQ) {
                log("screen capture DENIED — joystick still works, type current coord manually")
            }
        } catch (t: Throwable) {
            log("ERR capture: ${t.message}")
        }
    }

    fun log(line: String) { runOnUiThread { logs.append("\n" + line) } }
}
