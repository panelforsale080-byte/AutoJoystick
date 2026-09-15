package com.axcel.autojoystick

import android.graphics.Rect

object CoordinateCalibrator {
    /** Explicit override; when null, computed from screen size (top-right minimap box). */
    var overrideRect: Rect? = null

    var maxMapDistance: Int = 100

    /** Minimap coord text lives top-right. Screenshot shows it at ~x 80-97% , y 0-26% of screen. */
    fun coordRectFor(screenW: Int, screenH: Int): Rect {
        overrideRect?.let { return it }
        val l = (screenW * 0.80f).toInt()
        val t = 0
        val r = screenW
        val b = (screenH * 0.27f).toInt()
        return Rect(l, t, r, b)
    }
}
