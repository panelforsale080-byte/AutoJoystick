package com.axcel.autojoystick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

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
            val notif = Notification.Builder(this, "autojoy")
                .setContentTitle("AutoJoystick running")
                .setSmallIcon(android.R.drawable.ic_media_play).build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, notif)
            }
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
        // Hard fail if overlay permission isn't granted yet (Android 6+ requirement).
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            android.util.Log.e("AJ", "overlay skipped: SYSTEM_ALERT_WINDOW not granted")
            try { Toast.makeText(this, "Grant 'Display over other apps' first", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
            stopSelf(); return
        }
        val v = LayoutInflater.from(this).inflate(R.layout.overlay, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = 40; lp.y = 200

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
        val contrastBar = v.findViewById<SeekBar>(R.id.ov_contrast)
        val brightBar = v.findViewById<SeekBar>(R.id.ov_brightness)
        val invertSwitch = v.findViewById<Switch>(R.id.ov_invert)

        targetInput.setText(JoystickController.targetCoord)
        contrastBar.progress = ((prefs.contrast - 0.5f) * 100f).toInt().coerceIn(0, 250)
        brightBar.progress = (prefs.brightness + 128).coerceIn(0, 256)
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

        contrastBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = 0.5f + p / 100f
                prefs.contrast = value
                status.text = "contrast=${"%.2f".format(value)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        brightBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = p - 128
                prefs.brightness = value
                status.text = "bright=$value"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
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

        try { wm.addView(v, lp) }
        catch (t: Throwable) {
            android.util.Log.e("AJ", "addView failed; retrying in 400ms", t)
            // Retry once after a short delay in case system hadn't propagated the permission yet.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { wm.addView(v, lp) }
                catch (t2: Throwable) {
                    android.util.Log.e("AJ", "addView retry failed", t2)
                    try { Toast.makeText(this, "Overlay addView failed: ${t2.javaClass.simpleName}", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
                    stopSelf(); return@postDelayed
                }
            }, 400)
        }
        // Always show the full panel and only the panel (dot stays hidden until user minimizes).
        try { dot.visibility = View.GONE; panel.visibility = View.VISIBLE } catch (_: Throwable) {}
        rootView = v
        OverlayBus.coordView = coordView
        OverlayBus.statusView = status
        OverlayBus.debugView = debug
        OverlayBus.previewView = preview
        android.util.Log.i("AJ", "overlay panel added; panel visible=VISIBLE dot=GONE at x=${lp.x},y=${lp.y}")
        try { Toast.makeText(this, "AutoJoystick overlay ready", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
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

    /** Normalize a possibly-inverted rect (left<=right, top<=bottom). */
    private fun normalizeRect(r: Rect?): Rect? {
        if (r == null) return null
        return Rect(minOf(r.left, r.right), minOf(r.top, r.bottom), maxOf(r.left, r.right), maxOf(r.top, r.bottom))
    }

    /** Full-screen layer with draggable+resizable rect over the coord text.
     *  Crash-safe: every touch/draw path is guarded; saving only happens via SAVE button. */
    private fun showCoordCalLayer(status: TextView) {
        try {
            if (calibrateView != null) try { wm.removeView(calibrateView) } catch (_: Throwable) {}

            val dm = resources.displayMetrics
            val initial = normalizeRect(prefs.coordROI) ?: Rect(
                (dm.widthPixels * 0.82f).toInt(),
                (dm.heightPixels * 0.05f).toInt(),
                (dm.widthPixels * 0.99f).toInt(),
                (dm.heightPixels * 0.18f).toInt()
            )
            val corner = 56

            val draw = CoordCalView(this)
            draw.rect.set(initial.left.toFloat(), initial.top.toFloat(), initial.right.toFloat(), initial.bottom.toFloat())

            val barH = 120
            val bar = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setBackgroundColor(0xEE101014.toInt())
                gravity = Gravity.CENTER
            }
            val saveBtn = Button(this).apply { text = "SAVE BOX"; textSize = 13f }
            val cancelBtn = Button(this).apply { text = "CLOSE"; textSize = 13f }
            val hintTv = TextView(this).apply {
                text = "Drag corners/box, then SAVE"
                setTextColor(0xFFFFFFFF.toInt()); textSize = 11f; setPadding(16, 0, 0, 0)
            }
            bar.addView(saveBtn, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(cancelBtn, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(hintTv, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f))

            val wrap = object : FrameLayout(this) {
                override fun onTouchEvent(e: MotionEvent): Boolean {
                    return try {
                        draw.onTouch(e.rawX.toInt(), e.rawY.toInt(), corner, e.action)
                        draw.invalidate()
                        true
                    } catch (t: Throwable) {
                        android.util.Log.e("AJ", "coord cal touch", t)
                        false
                    }
                }
            }
            wrap.setBackgroundColor(0x33000000)

            fun close() {
                try { wm.removeView(wrap) } catch (_: Throwable) {}
                calibrateView = null
                rootView?.let {
                    it.findViewById<View>(R.id.ov_panel).visibility = View.VISIBLE
                    it.findViewById<View>(R.id.ov_dot).visibility = View.GONE
                }
            }
            saveBtn.setOnClickListener {
                try {
                    val r = normalizeRect(Rect(
                        draw.rect.left.toInt(), draw.rect.top.toInt(),
                        draw.rect.right.toInt(), draw.rect.bottom.toInt()
                    ))
                    if (r != null && r.width() >= 40 && r.height() >= 20) {
                        prefs.coordROI = r
                        status.text = "coord ROI saved ${r.left},${r.top} → ${r.right},${r.bottom}"
                        Toast.makeText(this@OverlayService, "Coord ROI saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@OverlayService, "Box too small — drag a corner first", Toast.LENGTH_SHORT).show()
                    }
                } catch (t: Throwable) { android.util.Log.e("AJ", "save ROI", t) }
                close()
            }
            cancelBtn.setOnClickListener { close() }

            wrap.addView(draw, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            wrap.addView(bar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM))

            val clp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ); clp.gravity = Gravity.TOP or Gravity.START
            try { wm.addView(wrap, clp) } catch (t: Throwable) {
                android.util.Log.e("AJ", "coord cal addView", t); return
            }
            calibrateView = wrap
        } catch (t: Throwable) {
            android.util.Log.e("AJ", "showCoordCalLayer crash-safe", t)
            rootView?.let {
                it.findViewById<View>(R.id.ov_panel).visibility = View.VISIBLE
                it.findViewById<View>(R.id.ov_dot).visibility = View.GONE
            }
        }
    }
}

class CoordCalView(ctx: Context) : View(ctx) {
    val rect: RectF = RectF(100f, 100f, 400f, 220f)
    private val stroke = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
    private val fill = Paint().apply { color = 0x3300FF00 }
    private val cornerFill = Paint().apply { color = Color.YELLOW }
    private val cornerR = 28f
    private var mode = 0
    private var downX = 0f
    private var downY = 0f

    init { setBackgroundColor(Color.TRANSPARENT); isFocusable = false; isClickable = false }

    override fun onDraw(c: Canvas) {
        try {
            super.onDraw(c)
            c.drawRect(rect, fill)
            c.drawRect(rect, stroke)
            c.drawRect(rect.left, rect.top, rect.left + cornerR, rect.top + cornerR, cornerFill)
            c.drawRect(rect.right - cornerR, rect.top, rect.right, rect.top + cornerR, cornerFill)
            c.drawRect(rect.left, rect.bottom - cornerR, rect.left + cornerR, rect.bottom, cornerFill)
            c.drawRect(rect.right - cornerR, rect.bottom - cornerR, rect.right, rect.bottom, cornerFill)
        } catch (t: Throwable) {
            android.util.Log.e("AJ", "cal draw", t)
        }
    }

    /** mode: 1=TL 2=TR 3=BL 4=BR 5=move 0=idle. All paths guarded — nothing here may crash the app. */
    fun onTouch(x: Int, y: Int, maxDrag: Int, action: Int): Boolean {
        try {
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
                    if (mode == 0) {
                        // Tap outside: start a fresh box here and drag its bottom-right corner.
                        rect.set(
                            (x - maxDrag).toFloat(), (y - maxDrag / 2).toFloat(),
                            (x + maxDrag).toFloat(), (y + maxDrag / 2).toFloat()
                        )
                        normalizeRect(); mode = 4
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = x.toFloat(); val ny = y.toFloat()
                    when (mode) {
                        1 -> { rect.left = nx; rect.top = ny }
                        2 -> { rect.right = nx; rect.top = ny }
                        3 -> { rect.left = nx; rect.bottom = ny }
                        4 -> { rect.right = nx; rect.bottom = ny }
                        5 -> { rect.offset(nx - downX, ny - downY); downX = nx; downY = ny }
                    }
                    normalizeRect()
                    clamp()
                }
                MotionEvent.ACTION_UP -> { mode = 0 }
            }
        } catch (t: Throwable) {
            android.util.Log.e("AJ", "cal touch", t)
        }
        return true
    }

    private fun normalizeRect() {
        val l = minOf(rect.left, rect.right); val r = maxOf(rect.left, rect.right)
        val t = minOf(rect.top, rect.bottom); val b = maxOf(rect.top, rect.bottom)
        rect.set(l, t, r, b)
    }

    private fun clamp() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return   // not measured yet — skip, never crash
        val minW = 60f; val minH = 30f
        if (rect.width() < minW) rect.right = rect.left + minW
        if (rect.height() < minH) rect.bottom = rect.top + minH
        if (rect.left < 0) { rect.right -= rect.left; rect.left = 0f }
        if (rect.top < 0) { rect.bottom -= rect.top; rect.top = 0f }
        if (rect.right > w) { rect.left -= (rect.right - w); rect.right = w }
        if (rect.bottom > h) { rect.top -= (rect.bottom - h); rect.bottom = h }
        normalizeRect()
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
