package com.axcel.autojoystick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build

class AccessibilityJoystickService : AccessibilityService() {

    companion object {
        @Volatile var instance: AccessibilityJoystickService? = null
    }

    // Active held stroke — reused and extended so the finger never lifts.
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var strokeAgeMs: Long = 0
    private val STROKE_MAX_MS = 9000L   // platform hard cap is 10s; recycle before it

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null; return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** True while a hold stroke is in progress (used to skip "stuck" false-positives). */
    fun holding(): Boolean = activeStroke != null

    /**
     * HOLD the joystick at (hx,hy) starting from (bx,by).
     * - If no stroke is active: press at base and slide to the hold point (starts the hold).
     * - If one is active: extend it toward the new hold point with continueStroke — finger
     *   never lifts, so movement is truly continuous.
     * Retargeting mid-hold is allowed (path pivot), which is exactly what a joystick needs.
     */
    fun hold(bx: Float, by: Float, hx: Float, hy: Float) {
        if (Build.VERSION.SDK_INT < 26) { legacy(bx, by, hx, hy); return }
        val cur = activeStroke
        if (cur == null || strokeAgeMs > STROKE_MAX_MS) {
            beginStroke(bx, by, hx, hy)
        } else {
            val p = Path(); p.moveTo(hx, hy)   // continueStroke pivots from current end
            val next = cur.continueStroke(p, 0L, STROKE_MAX_MS, true)
            val g = GestureDescription.Builder().addStroke(next).build()
            val ok = dispatchGesture(g, cb(), null)
            if (ok) { activeStroke = next; strokeAgeMs += STROKE_MAX_MS } else endStroke()
        }
    }

    /** HARD stop: cancel the active gesture immediately (no momentum), then lift. */
    fun cancel() {
        if (activeStroke == null) return
        activeStroke = null; strokeAgeMs = 0
        try { dispatchGesture(GestureDescription.Builder().build(), null, null) } catch (_: Throwable) {}
    }

    /** Lift the finger (stop walking). */
    fun endStroke() {
        val cur = activeStroke ?: return
        activeStroke = null; strokeAgeMs = 0
        try {
            val g = GestureDescription.Builder().addStroke(cur).build()
            dispatchGesture(g, null, null)
        } catch (_: Throwable) {}
    }

    private fun beginStroke(bx: Float, by: Float, hx: Float, hy: Float) {
        val p = Path(); p.moveTo(bx, by); p.lineTo(hx, hy)
        val s = GestureDescription.StrokeDescription(p, 0L, 350L, true)
        val g = GestureDescription.Builder().addStroke(s).build()
        val ok = dispatchGesture(g, cb(), null)
        if (ok) { activeStroke = s; strokeAgeMs = 350 } else OverlayBus.debugText("press REJECTED")
    }

    private fun cb() = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) {}
        override fun onCancelled(g: GestureDescription?) {
            activeStroke = null; strokeAgeMs = 0
            JoystickController.strokeBroken()
            OverlayBus.debugText("gesture CANCELLED by system")
        }
    }

    // Pre-26 fallback: single short drag (old behavior).
    private fun legacy(sx: Float, sy: Float, ex: Float, ey: Float) {
        if (Build.VERSION.SDK_INT < 24) return
        val p = Path(); p.moveTo(sx, sy); p.lineTo(ex, ey)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 400)).build()
        dispatchGesture(g, null, null)
    }
}
