package com.axcel.autojoystick

import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.widget.TextView

object JoystickController {
    var joystickBaseX: Float = 0f
    var joystickBaseY: Float = 0f
    var joystickRadius: Float = 220f
    var targetCoord: String = "51,35"
    @Volatile var running: Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    private val coordRegex = Regex("""\(?\s*(-?\d+)\s*,\s*(-?\d+)\s*\)?""")

    fun parseCoord(s: String): Pair<Int, Int>? {
        val m = coordRegex.find(s) ?: return null
        return m.groupValues[1].toIntOrNull()?.let { x ->
            m.groupValues[2].toIntOrNull()?.let { y -> x to y }
        }
    }

    fun computeDrag(current: Pair<Int, Int>, target: Pair<Int, Int>, maxMapDist: Int = 100): Pair<Float, Float> {
        val cx = (target.first - current.first).toDouble()
        val cy = (target.second - current.second).toDouble()
        val n = Math.sqrt(cx * cx + cy * cy)
        if (n < 1.0) return 0f to 0f
        val ang = Math.atan2(cy, cx)
        val ratio = (n / maxMapDist).coerceIn(0.0, 1.0)
        return (Math.cos(ang) * joystickRadius * ratio).toFloat() to
               (Math.sin(ang) * joystickRadius * ratio).toFloat()
    }

    // Simple loop: while running, keep joystick pressed toward target bearing.
    // OCR-based position feedback plugs in here later; for now hold direction toward target.
    fun tick(ctx: Context, status: TextView?) {
        handler.removeCallbacksAndMessages(null)
        val target = parseCoord(targetCoord) ?: return
        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                val svc = AccessibilityJoystickService.instance
                if (svc != null && joystickBaseX > 0f) {
                    val (dx, dy) = computeDrag(0 to 0, target)
                    svc.fireJoystickDrag(joystickBaseX, joystickBaseY, dx, dy, 550)
                    OverlayBus.push(targetCoord)
                } else {
                    status?.text = "set joystick center first / enable accessibility"
                }
                handler.postDelayed(this, 600)
            }
        })
    }
}
