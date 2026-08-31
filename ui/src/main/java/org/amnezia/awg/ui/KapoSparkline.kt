package org.amnezia.awg.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * A small live throughput graph, like the ones under the speed readouts in the
 * mockups.
 *
 * Self-wiring: it subscribes to KapoState directly, so no fragment has to feed
 * it. Set which series to plot with android:tag="up" or android:tag="down" in
 * the layout - that avoids needing a custom attrs.xml.
 */
class KapoSparkline @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private companion object {
        const val CAPACITY = 44
        val UP_COLOR = Color.parseColor("#FF3D95")
        val DOWN_COLOR = Color.parseColor("#00E5FF")
    }

    private val samples = ArrayDeque<Float>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val fillPath = Path()

    private val isUpload: Boolean get() = (tag as? String) != "down"
    private val accent: Int get() = if (isUpload) UP_COLOR else DOWN_COLOR

    private val onState: () -> Unit = {
        push(if (isUpload) KapoState.uploadRate else KapoState.downloadRate)
    }

    private fun push(v: Float) {
        samples.addLast(v)
        while (samples.size > CAPACITY) samples.removeFirst()
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        KapoState.addListener(onState)
    }

    override fun onDetachedFromWindow() {
        KapoState.removeListener(onState)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // baseline, always visible so the widget never looks broken when idle
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.shader = null
        paint.color = Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawLine(0f, h - 1f, w, h - 1f, paint)

        if (samples.size < 2) return

        // scale to the local peak, with a floor so idle noise doesn't fill the box
        val peak = maxOf(samples.max(), 0.05f)
        val step = w / (CAPACITY - 1).toFloat()
        val left = w - step * (samples.size - 1)

        path.reset()
        samples.forEachIndexed { i, v ->
            val x = left + step * i
            val y = h - 2f - (v / peak) * (h - 5f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // soft fill under the curve
        fillPath.set(path)
        fillPath.lineTo(w, h)
        fillPath.lineTo(left, h)
        fillPath.close()
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = accent
        canvas.drawPath(path, paint)
    }
}
