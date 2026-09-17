package com.axcel.autojoystick

import android.os.Handler
import android.os.Looper

object JoystickController {
    var joystickBaseX: Float = 0f
    var joystickBaseY: Float = 0f
    var joystickRadius: Float = 180f
    var targetCoord: String = "51,35"
    @Volatile var arrivalRadius: Float = 6f   // stop when this close to target (map units)
    @Volatile var currentXY: Pair<Int, Int>? = null
    @Volatile var running: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val coordRegex = Regex("""[\[\(\{<]?\s*(\d{1,3})\s*[, /\-]\s*(\d{1,3})\s*[\]\)\}>]?""")

    private var strokeActive = false
    private var lastEndX = 0f
    private var lastEndY = 0f

    // --- stuck-on-wall detection state ---
    private var lastPos: Pair<Int, Int>? = null
    private var stillTicks = 0
    private var unstickDir = 1

    /** System cancelled a continued gesture — next drag must start fresh from base. */
    fun strokeBroken() { strokeActive = false }

    /** One-shot diagnostic: drag right from base for 500ms. Proves base+a11y+gesture in one tap. */
    fun testDrag() {
        val svc = AccessibilityJoystickService.instance
        if (svc == null) { OverlayBus.status("TEST: accessibility OFF"); return }
        if (joystickBaseX <= 0f) { OverlayBus.status("TEST: press CAL JOY first"); return }
        strokeActive = false
        svc.fireSegment(joystickBaseX, joystickBaseY, joystickBaseX + joystickRadius * 0.8f, joystickBaseY, 500, false)
        OverlayBus.status("TEST drag → right from ${joystickBaseX.toInt()},${joystickBaseY.toInt()}")
    }

    fun parseCoord(s: String): Pair<Int, Int>? {
        val m = coordRegex.find(s) ?: return null
        return m.groupValues[1].toIntOrNull()?.let { x ->
            m.groupValues[2].toIntOrNull()?.let { y -> x to y }
        }
    }

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
        currentXY = x to y
        OverlayBus.push("$x,$y")
        val t = parseCoord(targetCoord) ?: return
        val dist = Math.hypot((t.first - x).toDouble(), (t.second - y).toDouble())
        if (dist <= arrivalRadius && running) {
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

    fun tick() {
        handler.removeCallbacksAndMessages(null)
        stillTicks = 0; lastPos = null   // fresh stuck-detection state each run
        val target = parseCoord(targetCoord) ?: run {
            OverlayBus.status("invalid target $targetCoord"); return
        }
        handler.post(object : Runnable {
            override fun run() {
                if (!running) { releaseStroke(); return }
                val svc = AccessibilityJoystickService.instance
                if (svc == null) { OverlayBus.status("enable accessibility first"); running = false; releaseStroke(); return }
                if (joystickBaseX <= 0f) { OverlayBus.status("press CAL JOY first"); running = false; return }

                val cur = currentXY
                if (cur == null) {
                    // walk north (-y) as a probe while waiting for first OCR reading
                    val ex = joystickBaseX
                    val ey = joystickBaseY - joystickRadius * 0.6f
                    sendChain(svc, ex, ey)
                    OverlayBus.status("probing north — wait OCR")
                    handler.postDelayed(this, 300)
                    return
                }

                val dist = Math.hypot((target.first - cur.first).toDouble(), (target.second - cur.second).toDouble())
                if (dist <= arrivalRadius) {
                    running = false
                    releaseStroke()
                    OverlayBus.status("ARRIVED at ${target.first},${target.second}")
                    return
                }

                // --- stuck detection: same coord for 4+ consecutive ticks while pushing ---
                val lp = lastPos
                if (lp != null && Math.hypot((cur.first - lp.first).toDouble(), (cur.second - lp.second).toDouble()) < 1.0) {
                    stillTicks++
                } else {
                    stillTicks = 0
                }
                lastPos = cur

                val (ox, oy) = computeDrag(cur, target)
                if (stillTicks >= 6 && dist > 2.5 && strokeActive) {
                    // Wall-stuck: drag PERPENDICULAR (±90°) to slide along the wall,
                    // alternating side each episode, then resume normal pathing next tick.
                    stillTicks = 0
                    unstickDir = -unstickDir
                    val px = -oy * unstickDir
                    val py = ox * unstickDir
                    sendChain(svc, joystickBaseX + px, joystickBaseY + py)
                    OverlayBus.status("STUCK — sidestep ${if (unstickDir > 0) "left" else "right"} | now ${cur.first},${cur.second} | d=%.1f".format(dist))
                    handler.postDelayed(this, 700)
                    return
                }

                val ex = joystickBaseX + ox
                val ey = joystickBaseY + oy
                sendChain(svc, ex, ey)
                OverlayBus.status("→ ${target.first},${target.second} | now ${cur.first},${cur.second} | d=%.1f (stop≤${arrivalRadius.toInt()})".format(dist))
                handler.postDelayed(this, 300)
            }
        })
    }

    private fun sendChain(svc: AccessibilityJoystickService, ex: Float, ey: Float) {
        if (!strokeActive) {
            svc.fireSegment(joystickBaseX, joystickBaseY, ex, ey, 420, true)
            strokeActive = true
        } else {
            svc.fireSegment(lastEndX, lastEndY, ex, ey, 420, true)
        }
        lastEndX = ex; lastEndY = ey
    }
}
