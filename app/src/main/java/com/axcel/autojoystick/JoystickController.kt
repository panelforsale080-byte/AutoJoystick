package com.axcel.autojoystick

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

object JoystickController {
    var joystickBaseX: Float = 0f
    var joystickBaseY: Float = 0f
    var joystickRadius: Float = 180f
    var targetCoord: String = "51,35"
    @Volatile var arrivalRadius: Float = 5f
    @Volatile var currentXY: Pair<Int, Int>? = null
    @Volatile var running: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val coordRegex = Regex("""[\[\(\{<]?\s*(\d{1,3})\s*[, /\-]\s*(\d{1,3})\s*[\]\)\}>]?""")

    private var strokeActive = false
    private var lastEndX = 0f
    private var lastEndY = 0f

    // --- smart stuck/recovery state ---
    private var lastPos: Pair<Int, Int>? = null
    private var stillTicks = 0
    private var bestDistance = Double.POSITIVE_INFINITY
    private var noProgressTicks = 0
    private var recoveryAttempts = 0
    private var recoveryStage = 0
    private var recoveryCooldownUntil = 0L
    private var lastOcrAt = 0L
    private var probeTicks = 0

    private const val TICK_MS = 650L
    private const val OCR_STALE_MS = 3_000L
    private const val STUCK_SAME_POSITION_TICKS = 4
    private const val STUCK_NO_PROGRESS_TICKS = 5
    private const val MAX_RECOVERY_ATTEMPTS = 6
    private const val PROGRESS_EPSILON = 0.75

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
        // OCR runs on CaptureService's background thread. Keep controller state,
        // gesture release, and stuck counters on the main handler thread.
        handler.post { acceptPositionUpdate(x, y) }
    }

    private fun acceptPositionUpdate(x: Int, y: Int) {
        currentXY = x to y
        lastOcrAt = SystemClock.elapsedRealtime()
        OverlayBus.push("$x,$y")
        val t = parseCoord(targetCoord) ?: return
        val dist = Math.hypot((t.first - x).toDouble(), (t.second - y).toDouble())
        if (dist <= arrivalRadius && running) {
            running = false
            releaseStroke()
            OverlayBus.status("ARRIVED at ${t.first},${t.second} | d=%.1f ≤ r=%.1f".format(dist, arrivalRadius))
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
        stillTicks = 0
        lastPos = null
        bestDistance = Double.POSITIVE_INFINITY
        noProgressTicks = 0
        recoveryAttempts = 0
        recoveryStage = 0
        recoveryCooldownUntil = 0L
        probeTicks = 0
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
                    probeTicks++
                    if (probeTicks >= 8) {
                        running = false
                        releaseStroke()
                        OverlayBus.status("OCR no coordinate — stopped safely")
                        return
                    }
                    // walk north (-y) as a probe while waiting for first OCR reading
                    val ex = joystickBaseX
                    val ey = joystickBaseY - joystickRadius * 0.6f
                    sendChain(svc, ex, ey)
                    OverlayBus.status("probing north — wait OCR")
                    handler.postDelayed(this, TICK_MS)
                    return
                }
                probeTicks = 0

                val now = SystemClock.elapsedRealtime()
                if (lastOcrAt > 0L && now - lastOcrAt > OCR_STALE_MS) {
                    running = false
                    releaseStroke()
                    OverlayBus.status("OCR stale — stopped safely")
                    return
                }

                val dist = Math.hypot((target.first - cur.first).toDouble(), (target.second - cur.second).toDouble())
                if (dist <= arrivalRadius) {
                    running = false
                    releaseStroke()
                    OverlayBus.status("ARRIVED at ${target.first},${target.second} | d=%.1f ≤ r=%.1f".format(dist, arrivalRadius))
                    return
                }

                // A character can move by a pixel while still being pinned to an
                // obstacle. Track both exact movement and distance-to-target progress.
                val lp = lastPos
                if (lp != null && Math.hypot((cur.first - lp.first).toDouble(), (cur.second - lp.second).toDouble()) < 1.0) {
                    stillTicks++
                } else {
                    stillTicks = 0
                }
                lastPos = cur

                val madeProgress = bestDistance == Double.POSITIVE_INFINITY ||
                    dist < bestDistance - PROGRESS_EPSILON
                if (madeProgress) {
                    bestDistance = dist
                    noProgressTicks = 0
                    if (recoveryAttempts > 0) {
                        OverlayBus.status("RECOVERED | now ${cur.first},${cur.second} | d=%.1f".format(dist))
                        recoveryAttempts = 0
                        recoveryStage = 0
                    }
                } else {
                    noProgressTicks++
                }

                if (now < recoveryCooldownUntil) {
                    OverlayBus.status("RECOVERY holding direction | now ${cur.first},${cur.second} | d=%.1f".format(dist))
                    handler.postDelayed(this, TICK_MS)
                    return
                }

                val (ox, oy) = computeDrag(cur, target)
                val stuck = dist > arrivalRadius + 1.0 &&
                    (stillTicks >= STUCK_SAME_POSITION_TICKS || noProgressTicks >= STUCK_NO_PROGRESS_TICKS)
                if (stuck && now >= recoveryCooldownUntil) {
                    if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
                        running = false
                        releaseStroke()
                        OverlayBus.status("STUCK — stopped safely after $MAX_RECOVERY_ATTEMPTS recoveries")
                        return
                    }
                    recoverFromStuck(svc, ox, oy, dist, now)
                    handler.postDelayed(this, 900L)
                    return
                }

                val ex = joystickBaseX + ox
                val ey = joystickBaseY + oy
                sendChain(svc, ex, ey)
                val progress = if (bestDistance.isFinite()) " | best=%.1f".format(bestDistance) else ""
                OverlayBus.status("→ ${target.first},${target.second} | now ${cur.first},${cur.second} | d=%.1f%s".format(dist, progress))
                handler.postDelayed(this, TICK_MS)
            }
        })
    }

    /**
     * Break a wall collision with a fresh touch sequence. Recovery directions
     * rotate through perpendicular, diagonal-slide, and reverse escape instead
     * of repeating the same sidestep forever.
     */
    private fun recoverFromStuck(
        svc: AccessibilityJoystickService,
        ox: Float,
        oy: Float,
        dist: Double,
        now: Long
    ) {
        val length = Math.hypot(ox.toDouble(), oy.toDouble()).coerceAtLeast(1.0)
        val ux = ox / length.toFloat()
        val uy = oy / length.toFloat()
        val side = if (recoveryAttempts % 2 == 0) 1f else -1f
        val px = -uy * side
        val py = ux * side
        val radius = joystickRadius

        val recovery = when (recoveryStage % 3) {
            0 -> px * radius * 0.85f to py * radius * 0.85f
            1 -> (px * 0.72f + ux * 0.38f) * radius to
                (py * 0.72f + uy * 0.38f) * radius
            else -> -ux * radius * 0.78f to -uy * radius * 0.78f
        }

        recoveryAttempts++
        recoveryStage = (recoveryStage + 1) % 3
        stillTicks = 0
        noProgressTicks = 0
        bestDistance = dist
        recoveryCooldownUntil = now + 2_200L

        // Explicitly lift the old chain before changing direction. This avoids
        // carrying a blocked gesture into the recovery gesture.
        releaseStroke()
        sendChain(svc, joystickBaseX + recovery.first, joystickBaseY + recovery.second)

        val mode = when ((recoveryStage + 2) % 3) {
            0 -> "sidestep"
            1 -> "diagonal-slide"
            else -> "reverse escape"
        }
        OverlayBus.status("STUCK #$recoveryAttempts — $mode | d=%.1f".format(dist))
    }

    private fun sendChain(svc: AccessibilityJoystickService, ex: Float, ey: Float) {
        if (!strokeActive) {
            svc.fireSegment(joystickBaseX, joystickBaseY, ex, ey, 500, true)
            strokeActive = true
        } else {
            svc.fireSegment(lastEndX, lastEndY, ex, ey, 500, true)
        }
        lastEndX = ex; lastEndY = ey
    }
}
