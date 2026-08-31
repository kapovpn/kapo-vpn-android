package org.amnezia.awg.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Route Atlas hop segment: the three segments share one glass capsule
 * (bg_hop_track on the container); the active one tints its own cell green
 * and turns its labels green. Thin separators divide the cells.
 */
class KapoHopButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var hopNumber: Int = 1
    var hopLabel: String = "FAST"
    var hopSelected: Boolean = false
        set(value) { field = value; invalidate() }
    var isEnabled2: Boolean = true
        set(value) { field = value; invalidate() }

    private val green = Color.parseColor("#00E5FF")

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f

        if (hopSelected) {
            paintFill.color = Color.argb(41, 0, 229, 255)
            canvas.drawRoundRect(2f, 2f, w - 2f, h - 2f, r, r, paintFill)
        }

        // Separator on the left edge of the middle and last segments
        if (hopNumber > 1) {
            paintFill.color = Color.argb(26, 255, 255, 255)
            canvas.drawRect(0f, h * 0.22f, 1.5f, h * 0.78f, paintFill)
        }

        paintText.textSize = h * 0.24f
        paintText.letterSpacing = 0.04f
        paintText.color = if (hopSelected) green else Color.argb(115, 255, 255, 255)
        canvas.drawText("$hopNumber HOP", w / 2f, h * 0.46f, paintText)

        paintText.textSize = h * 0.16f
        paintText.letterSpacing = 0.14f
        paintText.color = if (hopSelected) Color.argb(210, 0, 229, 255)
                          else Color.argb(77, 255, 255, 255)
        canvas.drawText(hopLabel, w / 2f, h * 0.72f, paintText)
    }
}
