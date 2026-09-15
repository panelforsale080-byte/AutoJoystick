package com.axcel.autojoystick

import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var logs: TextView
    private lateinit var targetInput: EditText
    private lateinit var lastCoord: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logs = findViewById(R.id.logs)
        targetInput = findViewById(R.id.target_coord)
        lastCoord = findViewById(R.id.last_coord)

        findViewById<Button>(R.id.btn_perm_overlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
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
            val target = targetInput.text.toString().ifBlank { "51,35" }
            OverlayController.target = target
            startService(Intent(this, OverlayService::class.java))
            log("START target=$target")
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            log("STOP")
        }
    }

    fun updateCoord(c: String) {
        runOnUiThread {
            lastCoord.text = c
            findViewById<TextView>(R.id.ov_coord)?.text = "coord: $c"
        }
    }

    fun log(line: String) {
        runOnUiThread { logs.append("\n" + line) }
    }

    object OverlayController {
        var target: String = "51,35"
    }
}
