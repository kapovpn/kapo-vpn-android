package org.amnezia.awg.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * KAPO connect orb: a dark 3D glass sphere with an inner cyan glow, a rotating
 * cyan→violet→magenta energy rim, and orbiting satellites. Disconnected it
 * shows a power glyph + TAP TO CONNECT; connected it breathes and the timer is
 * drawn below the orb (see fragment_home).
 *
 * The motion is driven by Choreographer frame callbacks off the real clock, NOT
 * ValueAnimator - so it keeps spinning even on devices where the animator
 * duration scale is turned off (a common Samsung / developer-options setting
 * that was making the orb look completely static).
 */
class KapoOrbButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    var state = State.DISCONNECTED
        set(value) { field = value; invalidate() }

    var timerText: String = "00:00:00"
        set(value) { field = value; if (state == State.CONNECTED) invalidate() }

    private val cyan = Color.parseColor("#00E5FF")
    private val violet = Color.parseColor("#9B6BFF")
    private val magenta = Color.parseColor("#FF3D95")

    // Time-based animation state.
    private var startNanos = 0L
    private var spinDeg = 0f
    private var breathe = 1f
    private var pressScale = 1f
    private var running = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = System.nanoTime()
            if (startNanos == 0L) startNanos = now
            val secs = (now - startNanos) / 1_000_000_000.0
            spinDeg = ((secs / 7.0) * 360.0 % 360.0).toFloat()        // one revolution / 7s
            breathe = if (state == State.CONNECTED)
                (1f + 0.02f * sin(secs * 2.0 * PI / 4.0).toFloat())    // gentle 4s breathe
            else 1f
            invalidate()
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        startNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    init { isClickable = true; isFocusable = true }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressScale = 0.94f; invalidate() }
            MotionEvent.ACTION_UP -> {
                pressScale = 1f; invalidate()
                if (event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) performClick()
            }
            MotionEvent.ACTION_CANCEL -> { pressScale = 1f; invalidate() }
        }
        return true   // always consume so we reliably get UP -> snappy, dependable taps
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
    }
    private val ringOval = RectF()

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val edge = minOf(width, height) / 2f
        val r = edge - 16f * density
        val connected = state == State.CONNECTED
        val connecting = state == State.CONNECTING
        val energised = connected || connecting

        // ---- outer glow bloom (sits behind the sphere) ----
        // Bounded to the VIEW radius (edge) and fully transparent there, so it can
        // never paint into the corners -> no square/rectangle artifact behind the orb.
        paintFill.shader = RadialGradient(cx, cy, edge,
            intArrayOf(
                Color.argb(if (connected) 110 else 60, 0, 229, 255),
                Color.argb(24, 155, 107, 255),
                Color.argb(0, 0, 0, 0)),
            floatArrayOf(0.5f, 0.8f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, edge, paintFill)
        paintFill.shader = null

        // (No ground shadow — the sphere floats freely in 3D.)

        val scale = breathe * pressScale
        canvas.save()
        canvas.scale(scale, scale, cx, cy)

        // ---- sphere body: dark radial dome, highlight offset up-left (34% 30%) ----
        paintFill.shader = RadialGradient(cx - r * 0.30f, cy - r * 0.34f, r * 1.5f,
            intArrayOf(
                if (energised) Color.rgb(20, 66, 80) else Color.rgb(24, 44, 58),
                if (energised) Color.rgb(11, 40, 52) else Color.rgb(15, 28, 40),
                Color.rgb(5, 12, 20)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paintFill)
        paintFill.shader = null

        // inset bottom shadow (inner depth) -> darker toward the bottom edge
        paintFill.shader = RadialGradient(cx, cy + r * 0.9f, r * 1.15f,
            intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0f, 0.85f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paintFill)
        paintFill.shader = null

        // inset top cyan glow (inner light) -> matches the mockup's inset highlight
        paintFill.shader = RadialGradient(cx, cy - r * 0.55f, r * 1.05f,
            intArrayOf(
                Color.argb(if (energised) 120 else 70, 0, 229, 255),
                Color.argb(0, 0, 229, 255)),
            floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paintFill)
        paintFill.shader = null

        // glossy specular highlight, upper-left
        paintFill.shader = RadialGradient(cx - r * 0.34f, cy - r * 0.4f, r * 0.6f,
            intArrayOf(Color.argb(if (energised) 150 else 110, 235, 252, 255),
                       Color.argb(0, 235, 252, 255)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paintFill)
        paintFill.shader = null

        // hairline rim
        paintEdge.strokeWidth = 1f * density
        paintEdge.color = Color.argb(if (energised) 80 else 55, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, paintEdge)

        // ---- rotating energy rim (cyan -> violet -> magenta) ----
        val m = Matrix(); m.setRotate(spinDeg, cx, cy)
        val sweep = SweepGradient(cx, cy, intArrayOf(cyan, violet, magenta, cyan), null)
        sweep.setLocalMatrix(m)
        paintEdge.shader = sweep
        paintEdge.strokeWidth = r * 0.06f
        paintEdge.alpha = if (energised) 210 else 120
        canvas.drawCircle(cx, cy, r * 0.9f, paintEdge)
        paintEdge.shader = null
        paintEdge.alpha = 255

        // ---- orbiting satellites, riding exactly on the energy rim (r*0.9) ----
        val orbit = r * 0.9f
        val ang = ((spinDeg - 90f) * PI / 180.0)
        val ox = cx + orbit * cos(ang).toFloat()
        val oy = cy + orbit * sin(ang).toFloat()
        paintFill.color = Color.argb(130, 255, 61, 149)   // magenta halo
        canvas.drawCircle(ox, oy, r * 0.11f, paintFill)
        paintFill.color = Color.WHITE
        canvas.drawCircle(ox, oy, r * 0.045f, paintFill)

        // a second, faster cyan satellite the other way for a real "orbit" feel
        val ang2 = ((-spinDeg * 1.6f - 20f) * PI / 180.0)
        val o2x = cx + orbit * cos(ang2).toFloat()
        val o2y = cy + orbit * sin(ang2).toFloat()
        paintFill.color = Color.argb(110, 0, 229, 255)
        canvas.drawCircle(o2x, o2y, r * 0.08f, paintFill)
        paintFill.color = Color.WHITE
        canvas.drawCircle(o2x, o2y, r * 0.032f, paintFill)

        // ---- center glyph + label ----
        val glyphColor = when {
            connected  -> Color.argb(235, 235, 252, 255)
            connecting -> cyan
            else       -> Color.argb(215, 255, 255, 255)
        }
        drawPower(canvas, cx, cy - 14f * density, 13f * density, glyphColor)
        if (connected) {
            // small "CONNECTED" status, then the action hint under it
            paintText.textSize = 8.5f * density
            paintText.letterSpacing = 0.24f
            paintText.color = Color.argb(220, 0, 229, 255)
            canvas.drawText("CONNECTED", cx, cy + 16f * density, paintText)

            paintText.textSize = 10.5f * density
            paintText.letterSpacing = 0.16f
            paintText.color = Color.argb(165, 255, 255, 255)
            canvas.drawText("TAP TO STOP", cx, cy + 32f * density, paintText)
        } else {
            paintText.textSize = 10.5f * density
            paintText.letterSpacing = 0.18f
            paintText.color = Color.argb(190, 255, 255, 255)
            canvas.drawText(if (connecting) "CONNECTING..." else "TAP TO CONNECT",
                cx, cy + 20f * density, paintText)
        }

        canvas.restore()
    }

    private fun drawPower(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val density = resources.displayMetrics.density
        paintEdge.strokeWidth = 1f * density
        paintEdge.color = Color.argb(45, 255, 255, 255)
        canvas.drawCircle(cx, cy, r + 6f * density, paintEdge)
        paintEdge.strokeWidth = r * 0.24f
        paintEdge.color = color
        ringOval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(ringOval, -62f, 304f, false, paintEdge)
        canvas.drawLine(cx, cy - r * 1.32f, cx, cy - r * 0.12f, paintEdge)
    }
}
