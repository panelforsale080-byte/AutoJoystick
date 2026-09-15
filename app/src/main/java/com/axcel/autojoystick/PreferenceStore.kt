package com.axcel.autojoystick

import android.content.Context
import android.graphics.Rect

class PreferenceStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("aj_prefs", Context.MODE_PRIVATE)

    var joystickCenterX: Float
        get() = p.getFloat("jx", 0f)
        set(v) { p.edit().putFloat("jx", v).apply() }
    var joystickCenterY: Float
        get() = p.getFloat("jy", 0f)
        set(v) { p.edit().putFloat("jy", v).apply() }

    var coordROI: Rect?
        get() {
            val l = p.getInt("cl", -1); if (l < 0) return null
            return Rect(l, p.getInt("ct", 0), p.getInt("cr", 0), p.getInt("cb", 0))
        }
        set(v) {
            if (v == null) p.edit().remove("cl").remove("ct").remove("cr").remove("cb").apply()
            else p.edit().putInt("cl", v.left).putInt("ct", v.top)
                .putInt("cr", v.right).putInt("cb", v.bottom).apply()
        }

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
