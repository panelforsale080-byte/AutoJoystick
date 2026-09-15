package com.axcel.autojoystick

import android.os.Handler
import android.os.Looper
import android.widget.TextView

object JoystickController {
    var joystickBaseX: Float = 0f
    var joystickBaseY: Float = 0f
    var joystickRadius: Float = 200f
    var targetCoord: String = "51,35"
    var currentCoord: Pair<Int, Int>? = null
    @Volatile var running: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val coordRegex = Regex("""\(?\s*(-?\d+)\s*,\s*(-?\d+)\s*\)?""")

    fun parseCoord(s: String): Pair<Int, Int>? {
        val m = coordRegex.find(s) ?: return null
        return m.groupValues[1].toIntOrNull()?.let { x ->
            m.groupValues[2].toIntOrNull()?.let { y -> x to y }
        }
    }

    /** Screen Y grows downward; map Y grows upward → invert Y. */
    fun computeDrag(current: Pair<Int, Int>, target: Pair<Int, Int>, maxMapDist: Int = 30): Pair<Float, Float> {
        val cx = (target.first - current.first).toDouble()
        val cy = (target.second - current.second).toDouble()
        val n = Math.sqrt(cx * cx + cy * cy)
        if (n < 0.5) return 0f to 0f
        val ang = Math.atan2(-cy, cx) // invert map-Y for screen direction
        val ratio = (n / maxMapDist).coerceIn(0.0, 1.0)
        return (Math.cos(ang) * joystickRadius * ratio).toFloat() to
               (Math.sin(ang) * joystickRadius * ratio).toFloat()
    }

    fun onPositionUpdate(x: Int, y: Int) {
        // arrival check
        val t = parseCoord(targetCoord) ?: return
        val dist = Math.hypot((t.first - x).toDouble(), (t.second - y).toDouble())
        if (dist <= 2.0 && running) {
            running = false
            OverlayBus.status("ARRIVED at ${t.first},${t.second}")
        }
    }

    /** Main loop: read current coord (from OCR), drag joystick toward target. */
    fun tick(status: TextView?) {
        handler.removeCallbacksAndMessages(null)
        val target = parseCoord(targetCoord) ?: return
        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                val svc = AccessibilityJoystickService.instance
                val cur = currentCoord
                if (svc == null) { OverlayBus.status("enable accessibility first"); return }
                if (joystickBaseX <= 0f) { OverlayBus.status("set joystick center first (CAL)"); return }
                if (cur == null) {
                    // no OCR yet: hold straight toward target bearing using last known or neutral
                    svc.fireJoystickDrag(joystickBaseX, joystickBaseY, 0f, -joystickRadius, 600)
                    OverlayBus.status("waiting for coord OCR…")
                } else {
                    val dist = Math.hypot((target.first - cur.first).toDouble(), (target.second - cur.second).toDouble())
                    if (dist <= 2.0) {
                        running = false
                        OverlayBus.status("ARRIVED at ${target.first},${target.second}")
                        return
                    }
                    val (dx, dy) = computeDrag(cur, target)
                    svc.fireJoystickDrag(joystickBaseX, joystickBaseY, dx, dy, 600)
                    OverlayBus.status("go ${target.first},${target.second} | now ${cur.first},${cur.second} | d=%.1f".format(dist))
                }
                handler.postDelayed(this, 650)
            }
        })
    }
}
