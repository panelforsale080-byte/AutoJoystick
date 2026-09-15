package com.axcel.autojoystick

import android.content.Context
import android.widget.TextView

object Prefs {
    private const val FILE = "aj_prefs"
    fun saveJoystick(ctx: Context, x: Float, y: Float) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("jx", x).putFloat("jy", y).apply()
    }
    fun loadJoystick(ctx: Context) {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        JoystickController.joystickBaseX = p.getFloat("jx", 0f)
        JoystickController.joystickBaseY = p.getFloat("jy", 0f)
    }
}
