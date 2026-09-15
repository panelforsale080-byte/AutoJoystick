package com.axcel.autojoystick

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
    private val CAPTURE_REQ = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logs = findViewById(R.id.logs)
        Prefs.loadJoystick(this)

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
                // 1) overlay menu
                val i = Intent(this, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                // 2) request screen capture consent for OCR
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), CAPTURE_REQ)
                log("Overlay shown; grant capture for OCR")
            } catch (t: Throwable) {
                log("ERR: ${t.message}")
                Toast.makeText(this, "Failed: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            try { stopService(Intent(this, OverlayService::class.java)) } catch (_: Throwable) {}
            try { stopService(Intent(this, CaptureService::class.java)) } catch (_: Throwable) {}
            log("STOP")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE_REQ && resultCode == RESULT_OK && data != null) {
            CaptureService.pendingResultCode = resultCode
            CaptureService.pendingData = data
            val i = Intent(this, CaptureService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            log("capture ON (OCR live)")
        } else if (requestCode == CAPTURE_REQ) {
            log("capture DENIED — coord OCR disabled; joystick still works")
        }
    }

    fun log(line: String) { runOnUiThread { logs.append("\n" + line) } }
}
