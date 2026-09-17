package com.axcel.autojoystick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper

class AccessibilityJoystickService : AccessibilityService() {

    companion object {
        @Volatile var instance: AccessibilityJoystickService? = null
    }

    // Active held stroke — extended in short slices so a stop command lifts the finger fast.
    @Volatile private var activeStroke: GestureDescription.StrokeDescription? = null
    private var lastHoldX = 0f
    private var lastHoldY = 0f
    private var strokeAgeMs: Long = 0

    // Each continuation slice is only a bit longer than the controller tick (300ms),
    // so when ticks stop (arrival/STOP/crash) the finger lifts within ~400ms max.
    private val SLICE_MS = 420L
    private val STROKE_RECYCLE_MS = 8500L   // platform hard cap per gesture is 10s
    private val WATCHDOG_MS = 700L          // no hold() within this -> auto lift

    private val ui = Handler(Looper.getMainLooper())
    private val watchdog = Runnable { endStroke() }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null; return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() { endStroke() }

    fun holding(): Boolean = activeStroke != null

    /**
     * HOLD the joystick at (hx,hy) starting from base (bx,by).
     * First call: press at base, slide to hold point. Next calls: extend the SAME
     * stroke via continueStroke — the finger never lifts between direction changes.
     */
    fun hold(bx: Float, by: Float, hx: Float, hy: Float) {
        if (Build.VERSION.SDK_INT < 26) { legacy(bx, by, hx, hy); return }
        ui.removeCallbacks(watchdog)
        ui.postDelayed(watchdog, WATCHDOG_MS)
        lastHoldX = hx; lastHoldY = hy
        val cur = activeStroke
        if (cur == null || strokeAgeMs > STROKE_RECYCLE_MS) {
            beginStroke(bx, by, hx, hy)
        } else {
            val p = Path(); p.moveTo(hx, hy)
            val next = cur.continueStroke(p, 0L, SLICE_MS, true)
            if (dispatchGesture(GestureDescription.Builder().addStroke(next).build(), cb(), null)) {
                activeStroke = next; strokeAgeMs += SLICE_MS
            } else {
                forceLift()
            }
        }
    }

    /** Lift the finger NOW: end the chain with a final short non-continuing stroke. */
    fun endStroke() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            endStrokeOnUi()
        } else {
            ui.postAtFrontOfQueue { endStrokeOnUi() }
        }
    }

    private fun endStrokeOnUi() {
        ui.removeCallbacks(watchdog)
        val cur = activeStroke ?: return
        activeStroke = null; strokeAgeMs = 0
        try {
            // Final 40ms slice at the current hold point with willContinue=false => instant lift.
            val p = Path(); p.moveTo(lastHoldX, lastHoldY)
            val fin = cur.continueStroke(p, 0L, 40L, false)
            dispatchGesture(GestureDescription.Builder().addStroke(fin).build(), null, null)
        } catch (t: Throwable) {
            // Stroke chain already broken — nothing more to lift.
            android.util.Log.w("AJ", "endStroke: ${t.message}")
        }
    }

    /** Hard stop used on arrival/STOP — same as endStroke (instant, no momentum). */
    fun cancel() = endStroke()

    private fun forceLift() {
        activeStroke = null; strokeAgeMs = 0
    }

    private fun beginStroke(bx: Float, by: Float, hx: Float, hy: Float) {
        val p = Path(); p.moveTo(bx, by); p.lineTo(hx, hy)
        val s = GestureDescription.StrokeDescription(p, 0L, 300L, true)
        if (dispatchGesture(GestureDescription.Builder().addStroke(s).build(), cb(), null)) {
            activeStroke = s; strokeAgeMs = 300
        } else {
            OverlayBus.debugText("press REJECTED")
        }
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
