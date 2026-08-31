package org.amnezia.awg.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * Cyber connect halo: concentric rings around the orb plus outward radar pulses
 * (cyan) while connecting / connected. Driven by Choreographer off the real
 * clock (not ValueAnimator), so the pulses animate even when the device's
 * animator duration scale is disabled.
 */
class KapoRouteRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { OFF, CONNECTING, ON }

    /** Kept for API compatibility with HomeFragment (no longer drawn as nodes). */
    var hopCount: Int = 1
        set(value) { field = value.coerceIn(1, 3); invalidate() }

    private var mode = Mode.OFF
    private var connAmount = 0f
    private var phase = 0f
    private var running = false
    private var lastNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 0.0 else (now - lastNanos) / 1_000_000_000.0
            lastNanos = now

            val period = when (mode) { Mode.ON -> 2.6; Mode.CONNECTING -> 1.4; Mode.OFF -> 2.6 }
            phase = ((phase + (dt / period)) % 1.0).toFloat()

            val target = if (mode == Mode.ON) 1f else 0f
            connAmount += (target - connAmount) * minOf(1.0, dt * 6.0).toFloat()

            invalidate()
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setMode(newMode: Mode) {
        mode = newMode
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(width, height) / 2f - 3f * density

        // soft center glow when connected
        if (connAmount > 0.01f) {
            paintFill.shader = RadialGradient(cx, cy, maxR,
                intArrayOf(Color.argb((70 * connAmount).toInt(), 0, 229, 255), Color.argb(0, 0, 229, 255)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, maxR, paintFill)
            paintFill.shader = null
        }

        // faint static rings framing the orb
        paintRing.strokeWidth = 1.4f * density
        val baseAlpha = if (mode == Mode.OFF) 26 else 46
        for (rr in floatArrayOf(0.66f, 0.84f, 1.0f)) {
            paintRing.color = Color.argb(baseAlpha, 0, 229, 255)
            canvas.drawCircle(cx, cy, maxR * rr, paintRing)
        }

        // outward radar pulses (connecting / connected)
        val intensity = when (mode) { Mode.ON -> 1f; Mode.CONNECTING -> 0.75f; Mode.OFF -> 0f }
        if (intensity > 0f) {
            paintRing.strokeWidth = 2f * density
            for (off in floatArrayOf(0f, 0.5f)) {
                val p = (phase + off) % 1f
                val r = maxR * (0.5f + 0.5f * p)
                val a = ((1f - p) * 150f * intensity).toInt().coerceIn(0, 255)
                paintRing.color = Color.argb(a, 0, 229, 255)
                canvas.drawCircle(cx, cy, r, paintRing)
            }
        }
    }
}
