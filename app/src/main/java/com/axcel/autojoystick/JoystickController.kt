package com.axcel.autojoystick

import android.os.Handler
import android.os.Looper
import android.widget.TextView

object JoystickController {
    var joystickBaseX: Float = 0f
    var joystickBaseY: Float = 0f
    var joystickRadius: Float = 180f
    var targetCoord: String = "51,35"
    @Volatile var currentCoord: Pair<Int, Int>? = null
    @Volatile var running: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val coordRegex = Regex("""\(?\s*(-?\d+)\s*,\s*(-?\d+)\s*\)?""")

    // chained-stroke state (finger stays down, rotates smoothly)
    private var strokeActive = false
    private var lastEndX = 0f
    private var lastEndY = 0f

    fun parseCoord(s: String): Pair<Int, Int>? {
        val m = coordRegex.find(s) ?: return null
        return m.groupValues[1].toIntOrNull()?.let { x ->
            m.groupValues[2].toIntOrNull()?.let { y -> x to y }
        }
    }

    /**
     * Map coords: X grows east (screen right), Y grows north.
     * Screen Y grows downward → invert map-Y for the drag direction.
     * Returns pixel offset from joystick center for FULL 360-degree aiming.
     */
    fun computeDrag(current: Pair<Int, Int>, target: Pair<Int, Int>, maxMapDist: Int = 25): Pair<Float, Float> {
        val dx = (target.first - current.first).toDouble()
        val dy = (target.second - current.second).toDouble()
        val n = Math.sqrt(dx * dx + dy * dy)
        if (n < 0.5) return 0f to 0f
        val ang = Math.atan2(-dy, dx) // invert map-Y → screen direction
        val ratio = (n / maxMapDist).coerceIn(0.0, 1.0)
        // gentle push when very close so we don't overshoot
        val r = joystickRadius * (0.35f + 0.65f * ratio.toFloat())
        return (Math.cos(ang) * r).toFloat() to (Math.sin(ang) * r).toFloat()
    }

    fun onPositionUpdate(x: Int, y: Int) {
        val t = parseCoord(targetCoord) ?: return
        val dist = Math.hypot((t.first - x).toDouble(), (t.second - y).toDouble())
        if (dist <= 1.5 && running) {
            running = false
            releaseStroke()
            OverlayBus.status("ARRIVED at ${t.first},${t.second}")
        }
    }

    /** End the chained gesture: lift the finger back toward center. */
    fun releaseStroke() {
        val svc = AccessibilityJoystickService.instance ?: return
        if (strokeActive) {
            svc.fireSegment(lastEndX, lastEndY, joystickBaseX, joystickBaseY, 150, false)
            strokeActive = false
        }
    }

    /** Main loop: OCR position → rotate joystick toward target. */
    fun tick(status: TextView?) {
        handler.removeCallbacksAndMessages(null)
        val target = parseCoord(targetCoord) ?: return
        handler.post(object : Runnable {
            override fun run() {
                if (!running) { releaseStroke(); return }
                val svc = AccessibilityJoystickService.instance
                if (svc == null) { OverlayBus.status("enable accessibility first"); running = false; releaseStroke(); return }
                if (joystickBaseX <= 0f) { OverlayBus.status("set joystick center first (CAL)"); running = false; return }

                val cur = currentCoord
                if (cur == null) {
                    // NO blind drag — wait for a real OCR reading so we never walk the wrong way
                    OverlayBus.status("reading coord… (check minimap visible)")
                    handler.postDelayed(this, 650)
                    return
                }

                val dist = Math.hypot((target.first - cur.first).toDouble(), (target.second - cur.second).toDouble())
                if (dist <= 1.5) {
                    running = false
                    releaseStroke()
                    OverlayBus.status("ARRIVED at ${target.first},${target.second}")
                    return
                }

                val (ox, oy) = computeDrag(cur, target)
                val ex = joystickBaseX + ox
                val ey = joystickBaseY + oy

                if (!strokeActive) {
                    // start gesture at joystick center, drag out to offset, finger stays down
                    svc.fireSegment(joystickBaseX, joystickBaseY, ex, ey, 620, true)
                    strokeActive = true
                } else {
                    // chain from last endpoint to new endpoint → smooth rotation
                    svc.fireSegment(lastEndX, lastEndY, ex, ey, 620, true)
                }
                lastEndX = ex; lastEndY = ey

                OverlayBus.push("${cur.first},${cur.second}")
                OverlayBus.status("→ ${target.first},${target.second} | now ${cur.first},${cur.second} | d=%.1f".format(dist))
                handler.postDelayed(this, 650)
            }
        })
    }
}
