package org.amnezia.awg.ui

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class KapoGradientText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    enum class GradientStyle { TEAL, MAGENTA, WHITE_TEAL, WHITE_BLUE, AMBER, NONE }

    var gradientStyle: GradientStyle = GradientStyle.NONE
        set(value) { field = value; applyGradient() }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) applyGradient()
    }

    private fun applyGradient() {
        if (width == 0) return
        val w = width.toFloat()
        // Dark theme: gradients run white-to-accent so they stay readable on
        // both the graphite and the teal background.
        paint.shader = when (gradientStyle) {
            GradientStyle.TEAL       -> LinearGradient(0f, 0f, w, 0f, intArrayOf(0xFFCFFBEC.toInt(), 0xFF5FF5C8.toInt()), null, Shader.TileMode.CLAMP)
            GradientStyle.MAGENTA    -> LinearGradient(0f, 0f, w, 0f, intArrayOf(0xFFFFD9E6.toInt(), 0xFFF58FB1.toInt()), null, Shader.TileMode.CLAMP)
            GradientStyle.WHITE_TEAL -> LinearGradient(0f, 0f, w, 0f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFBFFAE6.toInt(), 0xFF5FF5C8.toInt()), null, Shader.TileMode.CLAMP)
            GradientStyle.WHITE_BLUE -> LinearGradient(0f, 0f, w, 0f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFC9CEDA.toInt()), null, Shader.TileMode.CLAMP)
            GradientStyle.AMBER      -> LinearGradient(0f, 0f, w, 0f, intArrayOf(0xFFFFC96B.toInt(), 0xFFF0B24A.toInt()), null, Shader.TileMode.CLAMP)
            GradientStyle.NONE       -> null
        }
        invalidate()
    }
}
