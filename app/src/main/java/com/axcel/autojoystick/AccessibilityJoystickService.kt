package com.axcel.autojoystick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlin.math.max

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
        clearGestureState()
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
    private data class Segment(
        val sx: Float,
        val sy: Float,
        val ex: Float,
        val ey: Float,
        val durationMs: Long,
        val willContinue: Boolean
    )

    private val gestureLock = Any()
    private var dispatching = false
    private var queuedSegment: Segment? = null
    private var queuedRelease: Segment? = null
    private var continuedStroke: GestureDescription.StrokeDescription? = null

    /**
     * Queue the newest joystick endpoint and serialize gesture dispatches.
     * Android cancels an active accessibility gesture when another gesture is
     * dispatched too early; the old implementation did exactly that after a
     * few seconds of 650ms chained updates.
     */
    fun fireSegment(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long, willContinue: Boolean) {
        if (Build.VERSION.SDK_INT < 24) return
        synchronized(gestureLock) {
            val segment = Segment(sx, sy, ex, ey, max(80L, durationMs), willContinue)
            if (willContinue) queuedSegment = segment else queuedRelease = segment
            dispatchQueuedLocked()
        }
    }

    private fun dispatchQueuedLocked() {
        if (dispatching) return
        val segment = queuedRelease ?: queuedSegment ?: return
        if (queuedRelease != null) queuedRelease = null else queuedSegment = null

        val path = Path().apply {
            moveTo(segment.sx, segment.sy)
            lineTo(segment.ex, segment.ey)
        }
        val stroke = if (Build.VERSION.SDK_INT >= 26 &&
            segment.willContinue &&
            continuedStroke != null
        ) {
            continuedStroke!!.continueStroke(path, 0, segment.durationMs, true)
        } else if (Build.VERSION.SDK_INT >= 26) {
            GestureDescription.StrokeDescription(path, 0, segment.durationMs, segment.willContinue)
        } else {
            GestureDescription.StrokeDescription(path, 0, segment.durationMs)
        }
        if (segment.willContinue && Build.VERSION.SDK_INT >= 26) {
            continuedStroke = stroke
        } else {
            continuedStroke = null
        }

        dispatching = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    synchronized(gestureLock) {
                        dispatching = false
                        if (!segment.willContinue) continuedStroke = null
                        dispatchQueuedLocked()
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    synchronized(gestureLock) {
                        dispatching = false
                        continuedStroke = null
                        queuedSegment = null
                        queuedRelease = null
                    }
                    JoystickController.strokeBroken()
                    OverlayBus.debugText("gesture CANCELLED by system")
                }
            },
            null
        )
        if (!ok) {
            dispatching = false
            continuedStroke = null
            queuedSegment = null
            queuedRelease = null
            JoystickController.strokeBroken()
            OverlayBus.debugText("dispatch REJECTED (busy or no a11y)")
        }
    }

    private fun clearGestureState() {
        synchronized(gestureLock) {
            dispatching = false
            queuedSegment = null
            queuedRelease = null
            continuedStroke = null
        }
    }
}
