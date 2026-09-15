package com.axcel.autojoystick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class AccessibilityJoystickService : AccessibilityService() {

    companion object {
        var appContext: Context? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used; OCR pipeline drives automation.
    }

    override fun onInterrupt() {
        // Required abstract method; nothing to clean up.
    }

    fun fireJoystickDrag(startX: Float, startY: Float, dx: Float, dy: Float, durationMs: Long) {
        if (Build.VERSION.SDK_INT < 24) return
        val p = Path()
        p.moveTo(startX, startY)
        p.lineTo(startX + dx, startY + dy)
        val g = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(p, 0, durationMs)
        ).build()
        dispatchGesture(g, null, null)
    }

    fun getScreenDpi(): Float {
        val wm = (getSystemService(WINDOW_SERVICE) as WindowManager)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(dm)
        return dm.density
    }
}
