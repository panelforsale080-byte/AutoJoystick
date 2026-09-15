package com.axcel.autojoystick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.slider.Slider

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var rootView: View? = null
    private var calibrateView: View? = null
    private lateinit var prefs: PreferenceStore

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = PreferenceStore(this)
            ensureChannel()
            startForeground(1, Notification.Builder(this, "autojoy")
                .setContentTitle("AutoJoystick running")
                .setSmallIcon(android.R.drawable.ic_media_play).build())
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            showOverlay()
        } catch (t: Throwable) {
            android.util.Log.e("AJ", "overlay onCreate crash", t)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { rootView?.let { wm.removeView(it) } } catch (_: Throwable) {}
        try { calibrateView?.let { wm.removeView(it) } } catch (_: Throwable) {}
        rootView = null; calibrateView = null
        try { JoystickController.running = false } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel("autojoy") == null) {
                nm.createNotificationChannel(NotificationChannel("autojoy", "AutoJoystick", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun showOverlay() {
        val v = LayoutInflater.from(this).inflate(R.layout.overlay, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = 40; lp.y = 80

        val panel = v.findViewById<View>(R.id.ov_panel)
        val dot = v.findViewById<View>(R.id.ov_dot)
        val title = v.findViewById<TextView>(R.id.ov_title)
        val minimize = v.findViewById<TextView>(R.id.ov_minimize)
        val coordView = v.findViewById<TextView>(R.id.ov_coord)
        val debug = v.findViewById<TextView>(R.id.ov_debug)
        val preview = v.findViewById<ImageView>(R.id.ov_preview)
        val targetInput = v.findViewById<EditText>(R.id.ov_target)
        val btnStart = v.findViewById<Button>(R.id.ov_start)
        val btnStop = v.findViewById<Button>(R.id.ov_stop)
        val btnCalJoy = v.findViewById<Button>(R.id.ov_cal_joy)
        val btnCalCoord = v.findViewById<Button>(R.id.ov_cal_coord)
        val status = v.findViewById<TextView>(R.id.ov_status)
        val contrastSlider = v.findViewById<Slider>(R.id.ov_contrast)
        val brightSlider = v.findViewById<Slider>(R.id.ov_brightness)
        val invertSwitch = v.findViewById<Switch>(R.id.ov_invert)

        targetInput.setText(JoystickController.targetCoord)
        contrastSlider.value = prefs.contrast.coerceIn(0.5f, 3.0f)
        brightSlider.value = prefs.brightness.toFloat().coerceIn(-128f, 128f)
        invertSwitch.isChecked = prefs.invert

        title.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var px = 0f; var py = 0f
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; px = e.rawX; py = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = sx + (e.rawX - px).toInt()
                        lp.y = sy + (e.rawY - py).toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                    }
                }
                return true
            }
        })

        fun setCollapsed(c: Boolean) {
            panel.visibility = if (c) View.GONE else View.VISIBLE
            dot.visibility = if (c) View.VISIBLE else View.GONE
            try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
        }
        minimize.setOnClickListener { setCollapsed(true) }
        dot.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var px = 0f; var py = 0f; var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; px = e.rawX; py = e.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val nx = sx + (e.rawX - px).toInt(); val ny = sy + (e.rawY - py).toInt()
                        if (Math.abs(nx - sx) > 12 || Math.abs(ny - sy) > 12) moved = true
                        lp.x = nx; lp.y = ny
                        try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                    }
                    MotionEvent.ACTION_UP -> { if (!moved) setCollapsed(false) }
                }
                return true
            }
        })

        btnCalJoy.setOnClickListener {
            status.text = "tap JOIN-STICK CENTER on screen…"
            setCollapsed(true)
            showJoystickCalLayer(status)
        }
        btnCalCoord.setOnClickListener {
            status.text = "draw box over coord text"
            setCollapsed(true)
            showCoordCalLayer(status)
        }

        contrastSlider.addOnChangeListener { _, value, _ ->
            prefs.contrast = value
            status.text = "contrast=${"%.2f".format(value)}"
        }
        brightSlider.addOnChangeListener { _, value, _ ->
            prefs.brightness = value.toInt()
            status.text = "bright=${value.toInt()}"
        }
        invertSwitch.setOnCheckedChangeListener { _, c ->
            prefs.invert = c
            status.text = "invert=$c"
        }

        btnStart.setOnClickListener {
            val t = targetInput.text.toString().trim().ifBlank { "51,35" }
            val parsed = JoystickController.parseCoord(t)
            if (parsed == null) {
                Toast.makeText(this, "Invalid target: $t", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (JoystickController.joystickBaseX <= 0f) {
                Toast.makeText(this, "Press CAL JOY first", Toast.LENGTH_LONG).show(); return@setOnClickListener
            }
            if (AccessibilityJoystickService.instance == null) {
                Toast.makeText(this, "Enable AutoJoystick in Accessibility first", Toast.LENGTH_LONG).show(); return@setOnClickListener
            }
            try { JoystickController.releaseStroke() } catch (_: Throwable) {}
            JoystickController.targetCoord = t
            JoystickController.running = true
            JoystickController.tick()
            status.text = "running → $t"
        }
        btnStop.setOnClickListener {
            JoystickController.running = false
            JoystickController.releaseStroke()
            status.text = "stopped"
        }

        try { wm.addView(v, lp) } catch (t: Throwable) {
            android.util.Log.e("AJ", "addView failed", t)
        }
        rootView = v
        OverlayBus.coordView = coordView
        OverlayBus.statusView = status
        OverlayBus.debugView = debug
        OverlayBus.previewView = preview
    }

    /** Drag-tap to record the joystick center. */
    private fun showJoystickCalLayer(status: TextView) {
        if (calibrateView != null) try { wm.removeView(calibrateView) } catch (_: Throwable) {}
        val tv = TextView(this).apply {
            text = "TAP joystick center"; setBackgroundColor(0x3300FF00)
            setTextColor(0xFFFFFFFF.toInt()); textSize = 20f; gravity = Gravity.CENTER
        }
        val clp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ); clp.gravity = Gravity.TOP or Gravity.START
        tv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                JoystickController.joystickBaseX = e.rawX
                JoystickController.joystickBaseY = e.rawY
                prefs.joystickCenterX = e.rawX; prefs.joystickCenterY = e.rawY
                status.text = "joystick center = ${e.rawX.toInt()},${e.rawY.toInt()}"
                try { wm.removeView(tv) } catch (_: Throwable) {}
                calibrateView = null
                rootView?.let {
                    it.findViewById<View>(R.id.ov_panel).visibility = View.VISIBLE
                    it.findViewById<View>(R.id.ov_dot).visibility = View.GONE
                }
            }
            true
        }
        try { wm.addView(tv, clp) } catch (_: Throwable) {}
        calibrateView = tv
    }

    /** Full-screen layer with draggable+resizable rect over the coord text. */
    private fun showCoordCalLayer(status: TextView) {
        if (calibrateView != null) try { wm.removeView(calibrateView) } catch (_: Throwable) {}

        val dm = resources.displayMetrics
        val saved = prefs.coordROI
        val initial = saved ?: Rect(
            (dm.widthPixels * 0.82f).toInt(),
            (dm.heightPixels * 0.05f).toInt(),
            (dm.widthPixels * 0.99f).toInt(),
            (dm.heightPixels * 0.18f).toInt()
        )
        var rect = Rect(initial)
        val corner = 56

        val draw = CoordCalView(this) { newRect -> rect = newRect }
        draw.rect.set(rect)

        val clp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ); clp.gravity = Gravity.TOP or Gravity.START

        val wrap = object : FrameLayout(this) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        draw.onTouch(e.rawX.toInt(), e.rawY.toInt(), corner, MotionEvent.ACTION_DOWN)
                        invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        draw.onTouch(e.rawX.toInt(), e.rawY.toInt(), corner, MotionEvent.ACTION_MOVE)
                        invalidate()
                    }
                    MotionEvent.ACTION_UP -> {
                        val saved2 = draw.onTouch(e.rawX.toInt(), e.rawY.toInt(), corner, MotionEvent.ACTION_UP)
                        if (saved2) {
                            prefs.coordROI = Rect(
                                draw.rect.left.toInt(),
                                draw.rect.top.toInt(),
                                draw.rect.right.toInt(),
                                draw.rect.bottom.toInt()
                            )
                            status.text = "coord ROI saved ${draw.rect.left.toInt()},${draw.rect.top.toInt()} → ${draw.rect.right.toInt()},${draw.rect.bottom.toInt()}"
                            try { wm.removeView(this) } catch (_: Throwable) {}
                            calibrateView = null
                            rootView?.let {
                                it.findViewById<View>(R.id.ov_panel).visibility = View.VISIBLE
                                it.findViewById<View>(R.id.ov_dot).visibility = View.GONE
                            }
                            Toast.makeText(this@OverlayService, "Coord ROI saved", Toast.LENGTH_SHORT).show()
                        }
                        invalidate()
                    }
                }
                return true
            }
        }
        wrap.setBackgroundColor(0x33000000)
        draw.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        wrap.addView(draw)
        try { wm.addView(wrap, clp) } catch (_: Throwable) {}
        calibrateView = wrap
    }
}

class CoordCalView(ctx: Context, val setter: (Rect) -> Unit) : View(ctx) {
    val rect: RectF = RectF()
    private val stroke = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val fill = Paint().apply { color = 0x3300FF00 }
    private val cornerFill = Paint().apply { color = Color.YELLOW }
    private val cornerR = 28f
    private var mode = 0
    private var downX = 0f
    private var downY = 0f
    private var saved = false

    init { setBackgroundColor(Color.TRANSPARENT); isFocusable = false; isClickable = false }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawRect(rect, fill)
        c.drawRect(rect, stroke)
        // corners
        c.drawRect(rect.left, rect.top, rect.left + cornerR, rect.top + cornerR, cornerFill)
        c.drawRect(rect.right - cornerR, rect.top, rect.right, rect.top + cornerR, cornerFill)
        c.drawRect(rect.left, rect.bottom - cornerR, rect.left + cornerR, rect.bottom, cornerFill)
        c.drawRect(rect.right - cornerR, rect.bottom - cornerR, rect.right, rect.bottom, cornerFill)
    }

    fun onTouch(x: Int, y: Int, maxDrag: Int, action: Int): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x.toFloat(); downY = y.toFloat()
                mode = when {
                    isInside(rect.left, rect.top, cornerR, x.toFloat(), y.toFloat()) -> 1
                    isInside(rect.right - cornerR, rect.top, cornerR, x.toFloat(), y.toFloat()) -> 2
                    isInside(rect.left, rect.bottom - cornerR, cornerR, x.toFloat(), y.toFloat()) -> 3
                    isInside(rect.right - cornerR, rect.bottom - cornerR, cornerR, x.toFloat(), y.toFloat()) -> 4
                    rect.contains(x.toFloat(), y.toFloat()) -> 5
                    else -> 0
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val nx = x.toFloat(); val ny = y.toFloat()
                when (mode) {
                    1 -> { rect.right = nx; rect.bottom = ny }
                    2 -> { rect.left = nx; rect.bottom = ny }
                    3 -> { rect.right = nx; rect.top = ny }
                    4 -> { rect.left = nx; rect.top = ny }
                    5 -> {
                        rect.offset(nx - downX, ny - downY); downX = nx; downY = ny
                        clamp()
                    }
                }
                clamp()
            }
            MotionEvent.ACTION_UP -> {
                if (mode == 0) {
                    rect.set(
                        (x - maxDrag).toFloat(), (y - maxDrag / 2).toFloat(),
                        (x + maxDrag).toFloat(), (y + maxDrag / 2).toFloat()
                    )
                    clamp()
                    saved = true
                    setter(Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()))
                    return true
                }
                saved = true
                setter(Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()))
                return true
            }
        }
        return false
    }

    private fun clamp() {
        val w = width.toFloat(); val h = height.toFloat()
        val minW = 60f; val minH = 30f
        if (rect.width() < minW) {
            if (mode == 1 || mode == 3) rect.right = rect.left + minW
            else rect.left = rect.right - minW
        }
        if (rect.height() < minH) {
            if (mode == 1 || mode == 2) rect.bottom = rect.top + minH
            else rect.top = rect.bottom - minH
        }
        if (rect.left < 0) { rect.right -= rect.left; rect.left = 0f }
        if (rect.top < 0) { rect.bottom -= rect.top; rect.top = 0f }
        if (rect.right > w) { rect.left -= (rect.right - w); rect.right = w }
        if (rect.bottom > h) { rect.top -= (rect.bottom - h); rect.bottom = h }
    }

    private fun isInside(cx: Float, cy: Float, r: Float, x: Float, y: Float): Boolean =
        x in cx..(cx + r) && y in cy..(cy + r)
}

object OverlayBus {
    var coordView: TextView? = null
    var statusView: TextView? = null
    var debugView: TextView? = null
    var previewView: ImageView? = null
    fun push(c: String) { coordView?.post { coordView?.text = "coord: $c" } }
    fun status(s: String) { statusView?.post { statusView?.text = s } }
    fun debugText(s: String) { debugView?.post { debugView?.text = "raw: ${s.ifBlank { "—" }}" } }
    fun preview(bmp: Bitmap) {
        previewView?.post {
            try { previewView?.setImageBitmap(bmp) } catch (_: Throwable) {}
        }
    }
}
