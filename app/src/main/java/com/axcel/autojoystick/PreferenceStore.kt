package com.axcel.autojoystick

import android.content.Context
import android.content.res.Configuration
import android.graphics.RectF

class PreferenceStore(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("aj_prefs", Context.MODE_PRIVATE)

    private fun k(base: String, orient: Int) =
        if (orient == Configuration.ORIENTATION_LANDSCAPE) "${base}_l" else "${base}_p"

    // --- Joystick center (per-orientation, stored as screen fractions 0..1) ---
    fun joystickFracX(orient: Int): Float = p.getFloat(k("jx", orient), -1f)
    fun joystickFracY(orient: Int): Float = p.getFloat(k("jy", orient), -1f)
    fun setJoystickFrac(orient: Int, x: Float, y: Float) {
        p.edit().putFloat(k("jx", orient), x).putFloat(k("jy", orient), y).apply()
    }

    // --- Coord ROI (per-orientation, stored as screen fractions 0..1) ---
    fun coordRoi(orient: Int): RectF? {
        val l = p.getFloat(k("cl", orient), -1f); if (l < 0f) return null
        return RectF(l, p.getFloat(k("ct", orient), 0f),
            p.getFloat(k("cr", orient), 0f), p.getFloat(k("cb", orient), 0f))
    }
    fun setCoordRoi(orient: Int, v: RectF?) {
        if (v == null) p.edit()
            .remove(k("cl", orient)).remove(k("ct", orient))
            .remove(k("cr", orient)).remove(k("cb", orient)).apply()
        else p.edit()
            .putFloat(k("cl", orient), v.left).putFloat(k("ct", orient), v.top)
            .putFloat(k("cr", orient), v.right).putFloat(k("cb", orient), v.bottom).apply()
    }

    // --- OCR preprocessing (global, orientation-independent) ---
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
