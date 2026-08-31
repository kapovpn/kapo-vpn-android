package org.amnezia.awg.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The home hero: a dot-matrix world map with the active route drawn over it
 * as flight-path arcs. The route starts at the connect sphere (bottom
 * center), runs to Iceland, optionally on to Zurich and Amsterdam, then out
 * across the Atlantic to a NET node over North America.
 *
 * Design space is 320 x 192, matching the approved prototype; everything is
 * scaled from those units. Dots are cached into a bitmap so the per-frame
 * work while connected is only the route strokes and pulse dots.
 */
class KapoWorldView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---- palette ----
    private val segColors = intArrayOf(
        Color.parseColor("#FFB86B"),
        Color.parseColor("#3CE68C"),
        Color.parseColor("#B18CFF"),
        Color.parseColor("#FF6FA5"))
    private val pulseColors = intArrayOf(
        Color.parseColor("#FFE0A8"),
        Color.parseColor("#C8FFEE"),
        Color.parseColor("#E8DCFF"),
        Color.parseColor("#FFD3E0"))
    private val nodeFillOff = Color.parseColor("#3A3F4C")
    private val nodeFillOn  = Color.parseColor("#0E4A40")
    private val mint        = Color.parseColor("#3CE68C")

    // ---- state ----
    var hopCount: Int = 1
        set(value) { field = value.coerceIn(1, 3); invalidate() }

    private var connAmount = 0f
    private var connAnimator: ValueAnimator? = null
    private var dashPhase = 0f
    private var pulseT = 0f
    private var motionAnimator: ValueAnimator? = null

    fun setConnected(connected: Boolean) {
        connAnimator?.cancel()
        val target = if (connected) 1f else 0f
        connAnimator = ValueAnimator.ofFloat(connAmount, target).apply {
            duration = 900
            addUpdateListener { connAmount = animatedValue as Float; invalidate() }
            start()
        }
        if (connected) startMotion() else stopMotion()
    }

    private fun startMotion() {
        if (motionAnimator != null) return
        motionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = animatedFraction
                pulseT = t
                dashPhase -= 1.2f
                invalidate()
            }
            start()
        }
    }

    private fun stopMotion() {
        motionAnimator?.cancel()
        motionAnimator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopMotion()
        connAnimator?.cancel()
    }

    // ---- geometry: design space 320 x 192 ----

    private class Seg(val x0: Float, val y0: Float, val x1: Float, val y1: Float,
                      val x2: Float, val y2: Float, val x3: Float, val y3: Float,
                      val bx: Float, val by: Float)
    private class Node(val x: Float, val y: Float, val label: String)

    // First leg (sphere to Iceland) is shared by every hop count. It starts
    // just above the sphere's top edge so the line never runs underneath it.
    private val legSphereIS = Seg(160f, 118f, 192f, 96f, 172f, 50f, 137f, 22f, 174f, 72f)

    private val routes: Array<Pair<Array<Seg>, Array<Node>>> = arrayOf(
        Pair(arrayOf(
            legSphereIS,
            Seg(137f, 22f, 108f, 6f, 78f, 20f, 58f, 45f, 96f, 17f)),
            arrayOf(Node(137f, 22f, "IS"), Node(58f, 45f, "NET"))),
        Pair(arrayOf(
            legSphereIS,
            Seg(137f, 22f, 190f, 16f, 196f, 36f, 164f, 48f, 178f, 27f),
            Seg(164f, 48f, 122f, 80f, 82f, 70f, 58f, 45f, 105f, 70f)),
            arrayOf(Node(137f, 22f, "IS"), Node(164f, 48f, "CH"), Node(58f, 45f, "NET"))),
        Pair(arrayOf(
            legSphereIS,
            Seg(137f, 22f, 190f, 16f, 196f, 36f, 166f, 50f, 180f, 28f),
            Seg(166f, 50f, 136f, 64f, 128f, 48f, 152f, 35f, 138f, 55f),
            Seg(152f, 35f, 115f, 8f, 78f, 18f, 58f, 45f, 99f, 18f)),
            arrayOf(Node(137f, 22f, "IS"), Node(166f, 50f, "CH"),
                    Node(152f, 35f, "NL"), Node(58f, 45f, "NET"))))

    // Dot-matrix world, 64 x 32 cells; x marks land.
    private val worldRows = arrayOf(
        "................................................................",
        "......xx..............xxx......................................",
        "....xxxx.............xxxx....xx....xxxxxxxxxxxxxxxxxxxx........",
        "..xxxxxxxxxxxx.......xxxx..x.xxx..xxxxxxxxxxxxxxxxxxxxxxxx....",
        "...xxxxxxxxxxxxx......xx......xxx.xxxxxxxxxxxxxxxxxxxxxxxxxx..",
        "....xxxxxxxxxxxx..............xxx.xxxxxxxxxxxxxxxxxxxxxxxxxx..",
        ".....xxxxxxxxxxx.............xxxx.xxxxxxxxxxxxxxxxxxxxxxxxx...",
        ".....xxxxxxxxxx..............xxx..xxxxxxxxxxxxxxxxxxxxxxx..xx.",
        "......xxxxxxxxx.............xxxx.xxxxxxxxxxxxxxxxxxxxxxx...xx.",
        ".......xxxxxxx..............xxxxxxxxxxxxxxxxxxxxxxxxxxx....xx.",
        ".......xxxxx...............xxxxxxxxxxxxxxxxxxxxxxxxxxx........",
        "........xxxx..............xxxxxxxxxxxx..xxxxx..xxxxxx.........",
        ".........xxx..............xxxxxxxxxxxx..xxxxx...xxxx..........",
        ".........xx...............xxxxxxxxxxx...xxxx....xxx...........",
        "..........x...............xxxxxxxxxx....xxx....xx.xx..........",
        "...............xx.........xxxxxxxxxx....xx......xxxxx.........",
        "..............xxxx........xxxxxxxxx..............xx.xx........",
        ".............xxxxxx.......xxxxxxxxx...........................",
        ".............xxxxxxx......xxxxxxxx............................",
        "..............xxxxxxx.....xxxxxxxx............................",
        "..............xxxxxx.......xxxxxx.............................",
        "...............xxxxx.......xxxxx.....................x........",
        "...............xxxx.........xxxx..................xxxxxx......",
        "................xxx.........xxx..................xxxxxxxx.....",
        "................xxx..........x...................xxxxxxxx.....",
        "................xx...............................xxxxxxx......",
        "................xx.................................xxx........",
        ".................x.............................................",
        ".................x.............................................",
        "...............................................................",
        "..............................................xx..............",
        "...............................................................")

    // ---- paints ----
    private val paintDot   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBase  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val paintGlow  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val paintLit   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val paintFill  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintRing  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val paintText  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
    }

    private var mapBitmap: Bitmap? = null
    private val tmpPath = Path()
    private val tmpMeasure = PathMeasure()
    private val tmpPos = FloatArray(2)

    private fun sx(v: Float) = v / 320f * width
    private fun sy(v: Float) = v / 192f * height

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        paintDot.color = Color.argb(46, 255, 255, 255)
        val r = w / 320f * 1.7f
        for (row in worldRows.indices) {
            val line = worldRows[row]
            for (col in line.indices) {
                if (line[col] == 'x') {
                    c.drawCircle((col * 5f + 3f) / 320f * w, (row * 5.6f + 6f) / 192f * h, r, paintDot)
                }
            }
        }
        mapBitmap = bmp
    }

    private fun buildSeg(seg: Seg): Path {
        tmpPath.reset()
        tmpPath.moveTo(sx(seg.x0), sy(seg.y0))
        tmpPath.cubicTo(sx(seg.x1), sy(seg.y1), sx(seg.x2), sy(seg.y2), sx(seg.x3), sy(seg.y3))
        return tmpPath
    }

    override fun onDraw(canvas: Canvas) {
        val s = width / 320f
        mapBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        val (segs, nodes) = routes[hopCount - 1]

        // Route legs
        for (i in segs.indices) {
            val path = buildSeg(segs[i])
            val col = segColors[i % segColors.size]

            if (connAmount > 0.01f) {
                // Two-layer glow, no blur filter needed
                paintGlow.color = Color.argb((40 * connAmount).toInt(), Color.red(col), Color.green(col), Color.blue(col))
                paintGlow.strokeWidth = 9f * s
                canvas.drawPath(path, paintGlow)
                paintGlow.color = Color.argb((70 * connAmount).toInt(), Color.red(col), Color.green(col), Color.blue(col))
                paintGlow.strokeWidth = 5f * s
                canvas.drawPath(path, paintGlow)
            }

            // Base dotted line, always visible
            paintBase.color = Color.argb((115 - 40 * connAmount).toInt(), 255, 255, 255)
            paintBase.strokeWidth = 2.2f * s
            paintBase.pathEffect = DashPathEffect(floatArrayOf(1.5f * s, 6f * s), 0f)
            canvas.drawPath(path, paintBase)

            // Marching lit dashes
            if (connAmount > 0.01f) {
                paintLit.color = Color.argb((255 * connAmount).toInt(), Color.red(col), Color.green(col), Color.blue(col))
                paintLit.strokeWidth = 2.8f * s
                paintLit.pathEffect = DashPathEffect(floatArrayOf(6f * s, 6f * s), dashPhase * s)
                canvas.drawPath(path, paintLit)

                // Pulse dot traveling this leg
                tmpMeasure.setPath(path, false)
                val frac = (pulseT + i * 0.5f) % 1f
                tmpMeasure.getPosTan(tmpMeasure.length * frac, tmpPos, null)
                val pc = pulseColors[i % pulseColors.size]
                paintFill.color = Color.argb((60 * connAmount).toInt(), Color.red(col), Color.green(col), Color.blue(col))
                canvas.drawCircle(tmpPos[0], tmpPos[1], 7f * s, paintFill)
                paintFill.color = Color.argb((255 * connAmount).toInt(), Color.red(pc), Color.green(pc), Color.blue(pc))
                canvas.drawCircle(tmpPos[0], tmpPos[1], 3.5f * s, paintFill)
            }
        }

        // Segment number badges
        paintText.textSize = 6.5f * s
        for (i in segs.indices) {
            val col = segColors[i % segColors.size]
            val bx = sx(segs[i].bx); val by = sy(segs[i].by)
            paintFill.color = Color.argb(200, 15, 17, 24)
            canvas.drawCircle(bx, by, 5.5f * s, paintFill)
            paintRing.color = col
            paintRing.strokeWidth = 1.2f * s
            canvas.drawCircle(bx, by, 5.5f * s, paintRing)
            paintText.color = Color.WHITE
            canvas.drawText((i + 1).toString(), bx, by + 2.2f * s, paintText)
        }

        // Nodes: small spheres with a specular dot and a label
        paintText.textSize = 7f * s
        for (n in nodes) {
            val nx = sx(n.x); val ny = sy(n.y)
            // ground shadow
            paintFill.color = Color.argb(90, 0, 0, 0)
            canvas.drawOval(nx - 7f * s, ny + 8f * s, nx + 7f * s, ny + 12f * s, paintFill)
            // body
            paintFill.color = lerpColor(nodeFillOff, nodeFillOn, connAmount)
            canvas.drawCircle(nx, ny, 8.5f * s, paintFill)
            paintRing.strokeWidth = 1.8f * s
            paintRing.color = lerpColor(Color.argb(128, 255, 255, 255), mint, connAmount)
            canvas.drawCircle(nx, ny, 8.5f * s, paintRing)
            // specular
            paintFill.color = Color.argb(110, 255, 255, 255)
            canvas.drawOval(nx - 5.5f * s, ny - 5.5f * s, nx - 0.5f * s, ny - 2.5f * s, paintFill)
            // label
            paintText.color = lerpColor(Color.parseColor("#E5E8EF"), Color.parseColor("#EFFFF8"), connAmount)
            canvas.drawText(n.label, nx, ny + 2.5f * s, paintText)
        }
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt())
    }
}
