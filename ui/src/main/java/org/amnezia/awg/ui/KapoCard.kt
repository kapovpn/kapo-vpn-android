package org.amnezia.awg.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Dark glass card: a translucent white wash over whichever state gradient is
 * behind it, a hairline border, an inner top rim catching light, and a soft
 * black drop underneath. Accent styles tint the wash slightly.
 */
class KapoCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    enum class Style { TEAL, MAGENTA, NEUTRAL, SERVER }

    var cardStyle: Style = Style.NEUTRAL
        set(value) { field = value; invalidate() }

    private val paintFill   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintSheen  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintRim    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; strokeCap = Paint.Cap.ROUND }

    init {
        setWillNotDraw(false)
        elevation = 0f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 16f

        // Soft black drop under the card
        paintShadow.color = Color.argb(70, 0, 0, 0)
        canvas.drawRoundRect(0f, 4f, w, h + 6f, r, r, paintShadow)

        // Glass wash, faintly tinted per style
        val (fillColor, borderColor) = when (cardStyle) {
            Style.TEAL    -> Pair(Color.argb(30, 95, 245, 200), Color.argb(80, 95, 245, 200))
            Style.MAGENTA -> Pair(Color.argb(30, 245, 143, 177), Color.argb(80, 245, 143, 177))
            Style.SERVER  -> Pair(Color.argb(28, 255, 255, 255), Color.argb(48, 255, 255, 255))
            Style.NEUTRAL -> Pair(Color.argb(28, 255, 255, 255), Color.argb(48, 255, 255, 255))
        }
        paintFill.shader = null
        paintFill.style = Paint.Style.FILL
        paintFill.color = fillColor
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paintFill)

        // Vertical sheen: slightly brighter near the top
        paintSheen.shader = LinearGradient(0f, 0f, 0f, h * 0.6f,
            intArrayOf(Color.argb(20, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paintSheen)

        // Border
        paintFill.style = Paint.Style.STROKE
        paintFill.strokeWidth = 1f
        paintFill.color = borderColor
        canvas.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, r, r, paintFill)
        paintFill.style = Paint.Style.FILL

        // Bright rim on the top edge
        paintRim.color = Color.argb(60, 255, 255, 255)
        canvas.drawLine(r * 0.6f, 1f, w - r * 0.6f, 1f, paintRim)
    }
}
