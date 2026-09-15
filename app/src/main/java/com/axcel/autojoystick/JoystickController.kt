package com.axcel.autojoystick

object JoystickController {
    var joystickBaseX: Float = 0f
    var joystickBaseY: Float = 0f
    var joystickRadius: Float = 220f

    fun computeDrag(current: Pair<Int, Int>, target: Pair<Int, Int>, maxMapDist: Int = 100): Pair<Float, Float> {
        val cx = target.first - current.first
        val cy = target.second - current.second
        val n = Math.sqrt((cx * cx + cy * cy).toDouble())
        if (n < 1.0) return 0f to 0f
        val angular = Math.atan2(cy.toDouble(), cx.toDouble())
        val ratio = (n / maxMapDist).coerceIn(0.0, 1.0)
        val dx = (Math.cos(angular) * joystickRadius * ratio).toFloat()
        val dy = (Math.sin(angular) * joystickRadius * ratio).toFloat()
        return dx to dy
    }
}
