package com.axcel.autojoystick

import android.os.Handler
import android.os.Looper
import android.widget.TextView

object JoystickController {
    var joystickBaseX: Float = 0f
    var joystickBaseY: Float = 0f
    var joystickRadius: Float = 180f
    var targetCoord: String = "51,35"
    var currentCoord: String = ""
    @Volatile var currentXY: Pair<Int, Int>? = null
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

    fun setCurrent(s: String) {
        currentCoord = s
        currentXY = parseCoord(s)
    }

    /**
     * Map coords: X grows east, Y grows north.
     * Screen Y grows downward -> invert map-Y for the drag direction.
     */
    fun computeDrag(current: Pair<Int, Int>, target: Pair<Int, Int>, maxMapDist: Int = 30): Pair<Float, Float> {
        val dx = (target.first - current.first).toDouble()
        val dy = (target.second - current.second).toDouble()
        val n = Math.sqrt(dx * dx + dy * dy)
        if (n < 0.5) return 0f to 0f
        val ang = Math.atan2(-dy, dx)
        val ratio = (n / maxMapDist).coerceIn(0.0, 1.0)
        val r = joystickRadius * (0.4f + 0.6f * ratio.toFloat())
        return (Math.cos(ang) * r).toFloat() to (Math.sin(ang) * r).toFloat()
    }

    fun onPositionUpdate(x: Int, y: Int) {
        // OCR-derived update overrides whatever the user typed
        currentXY = x to y
        currentCoord = "$x,$y"
        OverlayBus.push("$x,$y")
        val t = parseCoord(targetCoord) ?: return
        val dist = Math.hypot((t.first - x).toDouble(), (t.second - y).toDouble())
        if (dist <= 1.5 && running) {
            running = false
            releaseStroke()
            OverlayBus.status("ARRIVED at ${t.first},${t.second}")
        }
    }

    fun releaseStroke() {
        val svc = AccessibilityJoystickService.instance ?: return
        if (strokeActive) {
            svc.fireSegment(lastEndX, lastEndY, joystickBaseX, joystickBaseY, 150, false)
            strokeActive = false
        }
    }

    /** Main loop: NEVER block. If user typed (or OCR gave) current coord, drag toward target.
     *  Otherwise walk in the straight direction (current->target) holding toward target. */
    fun tick(status: TextView?) {
        handler.removeCallbacksAndMessages(null)
        val target = parseCoord(targetCoord) ?: run {
            OverlayBus.status("invalid target $targetCoord"); return
        }
        handler.post(object : Runnable {
            override fun run() {
                if (!running) { releaseStroke(); return }
                val svc = AccessibilityJoystickService.instance
                if (svc == null) { OverlayBus.status("enable accessibility first"); running = false; releaseStroke(); return }
                if (joystickBaseX <= 0f) { OverlayBus.status("press CAL then tap joystick center"); running = false; return }

                val cur = currentXY
                if (cur == null) {
                    // No current coord available — drag blindly toward target angle, but keep moving.
                    // We treat "current" as (target.x - 1, target.y) to force an east-direction initial drag
                    // unless we have any other hint. Most useful default: head = north always works as probe.
                    OverlayBus.status("no current coord — dragging straight (set current to fix)")
                    val (ox, oy) = 0f to -joystickRadius * 0.7f // north on map = up on screen = -y
                    val ex = joystickBaseX + ox
                    val ey = joystickBaseY + oy
                    sendChain(svc, ex, ey)
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
                sendChain(svc, ex, ey)
                OverlayBus.status("→ ${target.first},${target.second} | now ${cur.first},${cur.second} | d=%.1f".format(dist))
                handler.postDelayed(this, 650)
            }
        })
    }

    private fun sendChain(svc: AccessibilityJoystickService, ex: Float, ey: Float) {
        if (!strokeActive) {
            svc.fireSegment(joystickBaseX, joystickBaseY, ex, ey, 620, true)
            strokeActive = true
        } else {
            svc.fireSegment(lastEndX, lastEndY, ex, ey, 620, true)
        }
        lastEndX = ex; lastEndY = ey
    }
}
