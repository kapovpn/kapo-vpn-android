package org.amnezia.awg.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

class KapoMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Connection state
    var isConnected = false
    var hopCount = 1

    // Scale factors (set in onSizeChanged)
    private var scaleX2 = 1f
    private var scaleY2 = 1f

    // Animators
    private var pulseAnimator: ValueAnimator? = null
    private var tunnelAnimator: ValueAnimator? = null
    private var pulseRadius = 0f
    private var pulseAlpha = 0f
    private var tunnelPhase = 0f

    // ── Paints ────────────────────────────────────────────────────────────────

    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0e1220")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181e35")
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0d1225")
        style = Paint.Style.STROKE
        strokeWidth = 0.4f
    }

    private val youDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#f05a4a")
        style = Paint.Style.FILL
    }

    private val youDotConnectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22d47a")
        style = Paint.Style.FILL
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }

    private val serverDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#252d48")
        style = Paint.Style.FILL
    }

    private val serverDotActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8b72fa")
        style = Paint.Style.FILL
    }

    private val tunnelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        letterSpacing = 0.08f
    }

    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f
        typeface = Typeface.DEFAULT
    }

    // ── Map coordinates (in 380×320 design space) ────────────────────────────
    // These will be scaled to actual view size

    // Location coordinates in design space (380w × 320h)
    private val youX = 207f   // Albania/Tirana
    private val youY = 195f

    private val icelandX = 74f
    private val icelandY = 72f

    private val swissX = 172f
    private val swissY = 158f

    private val nethX = 165f
    private val nethY = 118f

    // ── Map paths (simplified Europe in 380×320 space) ───────────────────────

    private fun buildCountryPath(pts: List<Float>): Path {
        val p = Path()
        p.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { p.lineTo(pts[i], pts[i+1]); i += 2 }
        p.close()
        return p
    }

    private val countries: List<List<Float>> = listOf(
        // Iceland
        listOf(58f,62f, 76f,55f, 91f,57f, 95f,66f, 90f,76f, 74f,80f, 60f,77f, 54f,70f),
        // Norway/Sweden
        listOf(168f,30f, 210f,25f, 218f,35f, 214f,55f, 210f,75f, 205f,95f, 195f,100f, 182f,92f, 170f,82f, 162f,65f, 160f,45f),
        // Finland
        listOf(210f,28f, 232f,22f, 240f,38f, 242f,62f, 236f,85f, 220f,95f, 208f,92f, 210f,72f, 214f,52f),
        // UK
        listOf(140f,98f, 155f,92f, 162f,100f, 158f,118f, 148f,125f, 136f,120f, 130f,108f, 134f,99f),
        // Ireland
        listOf(122f,105f, 133f,100f, 138f,112f, 132f,120f, 120f,118f, 115f,110f),
        // France
        listOf(145f,122f, 168f,118f, 178f,130f, 174f,150f, 158f,158f, 140f,154f, 132f,140f, 136f,125f),
        // Spain
        listOf(125f,152f, 162f,148f, 170f,162f, 164f,180f, 144f,188f, 118f,184f, 105f,168f, 110f,153f),
        // Portugal
        listOf(105f,155f, 118f,153f, 118f,184f, 105f,186f, 98f,168f),
        // Germany/Benelux
        listOf(160f,110f, 198f,106f, 205f,120f, 200f,142f, 178f,148f, 158f,142f, 154f,128f, 158f,112f),
        // Italy
        listOf(165f,152f, 185f,145f, 200f,155f, 205f,172f, 202f,190f, 192f,202f, 182f,200f, 172f,184f, 165f,165f),
        // Switzerland/Austria
        listOf(165f,142f, 200f,138f, 205f,152f, 185f,158f, 162f,155f),
        // Poland/Czechia
        listOf(198f,106f, 238f,102f, 245f,118f, 238f,135f, 200f,140f, 196f,122f),
        // Balkans/Albania
        listOf(192f,168f, 218f,160f, 228f,172f, 224f,190f, 208f,198f, 190f,190f, 186f,176f),
        // Ukraine/Belarus
        listOf(230f,110f, 278f,108f, 288f,130f, 280f,155f, 252f,160f, 228f,152f, 222f,135f),
        // Russia (simplified)
        listOf(222f,40f, 320f,28f, 348f,55f, 342f,88f, 318f,100f, 285f,105f, 252f,98f, 238f,82f, 228f,58f),
        // Baltic states
        listOf(208f,90f, 232f,86f, 240f,98f, 232f,112f, 210f,112f, 202f,102f),
        // Turkey
        listOf(228f,175f, 282f,168f, 295f,182f, 288f,198f, 255f,204f, 225f,195f, 220f,182f),
        // Greece
        listOf(205f,194f, 228f,188f, 235f,204f, 222f,218f, 206f,214f, 200f,200f),
        // Scandinavia tip
        listOf(155f,85f, 172f,82f, 180f,92f, 170f,100f, 155f,98f, 148f,90f),
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scaleX2 = w / 380f
        scaleY2 = h / 320f
        startPulse()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        tunnelAnimator?.cancel()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setConnected(connected: Boolean, hops: Int) {
        isConnected = connected
        hopCount = hops
        if (connected) startTunnelAnimation() else tunnelAnimator?.cancel()
        startPulse()
        invalidate()
    }

    fun setHops(hops: Int) {
        hopCount = hops
        invalidate()
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                pulseRadius = t * 28f
                pulseAlpha = ((1f - t) * 180).toInt().toFloat()
                invalidate()
            }
            start()
        }
    }

    private fun startTunnelAnimation() {
        tunnelAnimator?.cancel()
        tunnelAnimator = ValueAnimator.ofFloat(0f, 100f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                tunnelPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.scale(scaleX2, scaleY2)

        drawGrid(canvas)
        drawCountries(canvas)
        drawServerDots(canvas)
        if (isConnected) drawTunnelLines(canvas)
        drawYouDot(canvas)
        drawLabels(canvas)

        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        val steps = listOf(80f, 160f, 240f, 320f)
        steps.forEach { x -> canvas.drawLine(x, 0f, x, 320f, gridPaint) }
        val ySteps = listOf(80f, 160f, 240f)
        ySteps.forEach { y -> canvas.drawLine(0f, y, 380f, y, gridPaint) }
    }

    private fun drawCountries(canvas: Canvas) {
        countries.forEach { pts ->
            val path = buildCountryPath(pts)
            canvas.drawPath(path, landPaint)
            canvas.drawPath(path, borderPaint)
        }
    }

    private fun drawServerDots(canvas: Canvas) {
        // Iceland always shown
        val icePaint = if (isConnected) serverDotActivePaint else serverDotPaint
        canvas.drawCircle(icelandX, icelandY, 5f, icePaint)

        if (hopCount >= 2) {
            val swPaint = if (isConnected) serverDotActivePaint else serverDotPaint
            canvas.drawCircle(swissX, swissY, 5f, swPaint)
        }
        if (hopCount >= 3) {
            val nlPaint = if (isConnected) serverDotActivePaint else serverDotPaint
            canvas.drawCircle(nethX, nethY, 5f, nlPaint)
        }
    }

    private fun drawTunnelLines(canvas: Canvas) {
        tunnelPaint.pathEffect = DashPathEffect(floatArrayOf(8f, 5f), tunnelPhase)
        tunnelPaint.color = Color.parseColor("#22d47a")

        when (hopCount) {
            1 -> drawCurvedLine(canvas, youX, youY, icelandX, icelandY)
            2 -> {
                drawCurvedLine(canvas, youX, youY, icelandX, icelandY)
                tunnelPaint.color = Color.parseColor("#8b72fa")
                drawCurvedLine(canvas, icelandX, icelandY, swissX, swissY)
            }
            3 -> {
                drawCurvedLine(canvas, youX, youY, icelandX, icelandY)
                tunnelPaint.color = Color.parseColor("#8b72fa")
                drawCurvedLine(canvas, icelandX, icelandY, swissX, swissY)
                tunnelPaint.color = Color.parseColor("#29d4e8")
                drawCurvedLine(canvas, swissX, swissY, nethX, nethY)
            }
        }
    }

    private fun drawCurvedLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path()
        path.moveTo(x1, y1)
        val ctrlX = (x1 + x2) / 2f
        val ctrlY = minOf(y1, y2) - 40f
        path.quadTo(ctrlX, ctrlY, x2, y2)
        canvas.drawPath(path, tunnelPaint)
    }

    private fun drawYouDot(canvas: Canvas) {
        val paint = if (isConnected) youDotConnectedPaint else youDotPaint

        // Pulse ring
        pulsePaint.color = paint.color
        pulsePaint.alpha = pulseAlpha.toInt().coerceIn(0, 255)
        canvas.drawCircle(youX, youY, 5f + pulseRadius, pulsePaint)

        // Glow bg
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paint.color
            alpha = 30
            style = Paint.Style.FILL
        }
        canvas.drawCircle(youX, youY, 14f, glowPaint)

        // Main dot
        canvas.drawCircle(youX, youY, 5.5f, paint)
    }

    private fun drawLabels(canvas: Canvas) {
        // YOU label
        labelPaint.color = if (isConnected) Color.parseColor("#22d47a") else Color.parseColor("#f05a4a")
        canvas.drawText(if (isConnected) "SECURED" else "YOU", youX + 10f, youY - 4f, labelPaint)

        subLabelPaint.color = if (isConnected) Color.parseColor("#1a4a2a") else Color.parseColor("#5a3030")
        canvas.drawText(if (isConnected) "via Iceland" else "Tirana, AL", youX + 10f, youY + 12f, subLabelPaint)

        // Server labels
        labelPaint.color = if (isConnected) Color.parseColor("#8b72fa") else Color.parseColor("#252d48")
        canvas.drawText("🇮🇸", icelandX - 8f, icelandY - 10f, labelPaint)

        if (hopCount >= 2) {
            labelPaint.color = if (isConnected) Color.parseColor("#8b72fa") else Color.parseColor("#252d48")
            canvas.drawText("🇨🇭", swissX - 8f, swissY - 10f, labelPaint)
        }
        if (hopCount >= 3) {
            labelPaint.color = if (isConnected) Color.parseColor("#29d4e8") else Color.parseColor("#252d48")
            canvas.drawText("🇳🇱", nethX - 8f, nethY - 10f, labelPaint)
        }
    }
}
