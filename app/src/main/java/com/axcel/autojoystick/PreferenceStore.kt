package com.axcel.autojoystick

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF

class PreferenceStore(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("aj_prefs", Context.MODE_PRIVATE)

    // Joystick center saved as screen fractions — survives orientation changes.
    var joystickCenterFracX: Float
        get() = p.getFloat("jx", -1f)
        set(v) { p.edit().putFloat("jx", v).apply() }
    var joystickCenterFracY: Float
        get() = p.getFloat("jy", -1f)
        set(v) { p.edit().putFloat("jy", v).apply() }

    // Coord ROI saved as screen fractions (0..1) — same box position in portrait & landscape.
    var coordRoiF: RectF?
        get() {
            val l = p.getFloat("cl", -1f); if (l < 0f) return null
            return RectF(l, p.getFloat("ct", 0f), p.getFloat("cr", 0f), p.getFloat("cb", 0f))
        }
        set(v) {
            if (v == null) p.edit().remove("cl").remove("ct").remove("cr").remove("cb").apply()
            else p.edit().putFloat("cl", v.left).putFloat("ct", v.top)
                .putFloat("cr", v.right).putFloat("cb", v.bottom).apply()
        }
    var roiOrientation: Int  // 1=portrait, 2=landscape when calibrated
        get() = p.getInt("roi_orient", 0)
        set(v) { p.edit().putInt("roi_orient", v).apply() }

    var contrast: Float
        get() = p.getFloat("contrast", 1.8f)
        set(v) { p.edit().putFloat("contrast", v).apply() }
    var brightness: Int
        get() = p.getInt("brightness", -60)
        set(v) { p.edit().putInt("brightness", v).apply() }
    var invert: Boolean
        get() = p.getBoolean("invert", false)
        set(v) { p.edit().putBoolean("invert", v).apply() }
}
