package com.axcel.autojoystick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent

class AccessibilityJoystickService : AccessibilityService() {

    companion object {
        var appContext: Context? = null
        @Volatile var instance: AccessibilityJoystickService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used; OCR pipeline drives automation.
    }

    override fun onInterrupt() {
        // Required abstract method; nothing to clean up.
    }

    /**
     * Drag one segment. willContinue=true keeps the finger held so the next
     * stroke chains smoothly (no lift between ticks) — this is what makes the
     * joystick rotate continuously instead of stuttering.
     */
    fun fireSegment(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long, willContinue: Boolean) {
        if (Build.VERSION.SDK_INT < 24) return
        val p = Path()
        p.moveTo(sx, sy)
        p.lineTo(ex, ey)
        val stroke = if (Build.VERSION.SDK_INT >= 26) {
            GestureDescription.StrokeDescription(p, 0, durationMs, willContinue)
        } else {
            GestureDescription.StrokeDescription(p, 0, durationMs)
        }
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                OverlayBus.debugText("gesture completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                JoystickController.strokeBroken()
                OverlayBus.debugText("gesture CANCELLED by system")
            }
        }, null)
        if (!ok) OverlayBus.debugText("dispatch REJECTED (busy or no a11y)")
    }
}
