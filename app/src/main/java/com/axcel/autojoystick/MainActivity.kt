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
    private lateinit var prefs: PreferenceStore
    private val CAPTURE_REQ = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)
            logs = findViewById(R.id.logs)
            prefs = PreferenceStore(this)
            // Joystick center is restored from fractions inside OverlayService (screen-size aware).
        } catch (t: Throwable) {
            Log.e("AJ", "init crash", t)
            try { Toast.makeText(this, "Init: ${t.message}", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
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
                if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "Grant overlay first", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                try {
                    val i = Intent(this, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), CAPTURE_REQ)
                    log("overlay on; grant capture")
                } catch (t: Throwable) {
                    log("ERR ${t.message}")
                    Toast.makeText(this, "Err: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
            findViewById<Button>(R.id.btn_stop).setOnClickListener {
                try { JoystickController.releaseStroke() } catch (_: Throwable) {}
                try { stopService(Intent(this, OverlayService::class.java)) } catch (_: Throwable) {}
                try { stopService(Intent(this, CaptureService::class.java)) } catch (_: Throwable) {}
                log("STOP")
            }
        } catch (t: Throwable) {
            Log.e("AJ", "wire buttons crash", t)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(r: Int, c: Int, data: Intent?) {
        super.onActivityResult(r, c, data)
        if (r == CAPTURE_REQ && c == RESULT_OK && data != null) {
            CaptureService.pendingResultCode = c
            CaptureService.pendingData = data
            try {
                val i = Intent(this, CaptureService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                log("capture ON")
            } catch (t: Throwable) { log("capture err ${t.message}") }
        } else if (r == CAPTURE_REQ) { log("capture denied") }
    }

    fun log(s: String) { runOnUiThread { logs.append("\n$s") } }
}
