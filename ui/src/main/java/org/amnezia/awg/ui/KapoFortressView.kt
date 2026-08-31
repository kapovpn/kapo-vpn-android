package org.amnezia.awg.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * KapoFortressView - the home hero.
 *
 * A dark metallic fortress cluster - a keep flanked by two corner towers,
 * joined by a thick rampart - sitting off-centre under a low-poly faceted
 * shield dome. The stonework never changes colour; only the glow does.
 *
 *   NOT PROTECTED : the dome is torn open near the top, the rampart shows a
 *                   punched-through breach with fracture cracks and dead
 *                   sensor ports, and the whole scene reads dim and orange.
 *   CONNECTING    : the dome facets sweep round the rim as the shield
 *                   reseals.
 *   PROTECTED     : dome and rampart whole and green, every seam and port
 *                   lit evenly.
 *
 * [hopCount] adds concentric shield layers: 1 hop = one dome, 2 = two,
 * 3 = three.
 *
 * Drawn in a 300 x 300 design space, scaled uniformly and centred. The
 * fortress cluster itself sits left of the dome's own centre (STRUCT_CX),
 * deliberately off-axis rather than dead-centre under the shield.
 */
class KapoFortressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private companion object {
        const val D = 300f
        const val CX = 150f
        const val BASE_Y = 240f

        // shield radii - outer is fixed so the composition never jumps
        const val R_OUTER = 128f
        const val R_MID = 104f
        const val R_INNER = 88f

        const val PLINTH_RX = 126f
        const val PLINTH_RY = 22f

        // the fortress cluster sits left of the dome's own centre - deliberately
        // off-axis rather than dead-centre under the shield
        const val STRUCT_CX = 134f

        // curtain wall - a thick rampart, not a thin band
        const val WALL_HALF = 64f
        const val WALL_L = STRUCT_CX - WALL_HALF
        const val WALL_R = STRUCT_CX + WALL_HALF
        const val WALL_TOP = 190f
        const val WALL_TOOTH_H = 11f
        const val N_TEETH = 6
        const val N_PORTS = 5
        const val BREACH_TOOTH_IDX = 2

        // corner turrets
        const val SIDE_BASE_HALF = 13f
        const val SIDE_TOP_HALF = 8f
        const val SIDE_HEIGHT = 52f
        const val LT_CX = WALL_L + 16f
        const val RT_CX = WALL_R - 16f

        // central keep
        const val KEEP_BASE_HALF = 24f
        const val KEEP_TOP_HALF = 13f
        const val KEEP_HEIGHT = 80f

        // isometric depth vectors - towers vs the (thicker) wall
        const val ISO_DX = 16f
        const val ISO_DY = -11f
        const val WISO_DX = 24f
        const val WISO_DY = -16f

        const val N_FACETS = 7

        val G_CORE  = Color.parseColor("#3CE68C")
        val G_LIGHT = Color.parseColor("#A8FFD0")
        val G_DEEP  = Color.parseColor("#0FA862")
        val O_CORE  = Color.parseColor("#FF8A5C")
        val O_LIGHT = Color.parseColor("#FFCB9B")
        val O_DEEP  = Color.parseColor("#E04F3C")
        val WARM    = Color.parseColor("#FFB765")

        // fixed metallic body - does not change colour with state, only the glow does
        val BODY      = Color.parseColor("#2B3255")
        val BODY_TOP  = Color.parseColor("#3B4470")
        val BODY_SIDE = Color.parseColor("#171B2E")
        val CAVITY    = Color.parseColor("#0B0E1C")

        val PLINTH_TOP = Color.parseColor("#243056")
        val PLINTH_LIP = Color.parseColor("#38477A")
        val BG_TOP     = Color.parseColor("#1B2445")
        val BG_BOT     = Color.parseColor("#111938")

        const val BREATH = 3.2f
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    var state: State = State.DISCONNECTED
        set(value) { if (field != value) { field = value; animateSeal() } }

    /** 1, 2 or 3 protective shield layers. */
    var hopCount: Int = 1
        set(value) {
            val v = value.coerceIn(1, 3)
            if (field != v) { field = v; animateHops() }
        }

    private var blend = 0f          // 0 = breached, 1 = sealed
    private var hops = 1f           // animated shell count
    private var sealAnim: ValueAnimator? = null
    private var hopAnim: ValueAnimator? = null
    private val t0 = System.currentTimeMillis()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    /** Incoming threats: angle (deg, 180 = left, 270 = up), speed, phase. */
    private val dots = arrayOf(
        floatArrayOf(200f, 0.40f, 0.00f),
        floatArrayOf(226f, 0.33f, 0.31f),
        floatArrayOf(250f, 0.45f, 0.58f),
        floatArrayOf(274f, 0.36f, 0.16f),
        floatArrayOf(300f, 0.42f, 0.74f),
        floatArrayOf(332f, 0.31f, 0.44f)
    )

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { postInvalidateOnAnimation() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ticker.isStarted) ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel(); sealAnim?.cancel(); hopAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun animateSeal() {
        sealAnim?.cancel()
        val target = if (state == State.CONNECTED) 1f else 0f
        sealAnim = ValueAnimator.ofFloat(blend, target).apply {
            duration = (1000f * abs(target - blend)).toLong().coerceAtLeast(160L)
            addUpdateListener { blend = it.animatedValue as Float; postInvalidateOnAnimation() }
            start()
        }
    }

    private fun animateHops() {
        hopAnim?.cancel()
        hopAnim = ValueAnimator.ofFloat(hops, hopCount.toFloat()).apply {
            duration = 520
            addUpdateListener { hops = it.animatedValue as Float; postInvalidateOnAnimation() }
            start()
        }
    }

    // -- helpers --
    private fun clock() = (System.currentTimeMillis() - t0) / 1000f
    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun smooth(x: Float) = x * x * (3f - 2f * x)
    private fun breathe(t: Float) = 1f + 0.045f * sin((2 * PI * t / BREATH).toDouble()).toFloat()

    private fun mix(c1: Int, c2: Int, f: Float) = Color.rgb(
        lerp(Color.red(c1).toFloat(), Color.red(c2).toFloat(), f).toInt(),
        lerp(Color.green(c1).toFloat(), Color.green(c2).toFloat(), f).toInt(),
        lerp(Color.blue(c1).toFloat(), Color.blue(c2).toFloat(), f).toInt()
    )

    private fun a(c: Int, v: Float) =
        Color.argb((v.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    private fun fill(c: Int) { paint.style = Paint.Style.FILL; paint.shader = null; paint.color = c }
    private fun line(c: Int, w: Float) {
        paint.style = Paint.Style.STROKE; paint.shader = null
        paint.strokeWidth = w; paint.color = c; paint.strokeCap = Paint.Cap.ROUND
    }

    private fun domeRect(r: Float) = rect.apply { set(CX - r, BASE_Y - r, CX + r, BASE_Y + r) }

    /** Radius and opacity of each shield layer for the current animated hop count. */
    private fun shell(i: Int): Pair<Float, Float> = when (i) {
        0 -> R_OUTER to 1f
        1 -> {
            val p = (hops - 1f).coerceIn(0f, 1f)
            lerp(R_OUTER, R_MID, p) to p
        }
        else -> {
            val p = (hops - 2f).coerceIn(0f, 1f)
            lerp(R_MID, R_INNER, p) to p
        }
    }

    /** Points around the upper half of a dome of radius [r], left to right. */
    private fun domeFacetPoints(r: Float): Pair<FloatArray, FloatArray> {
        val n = N_FACETS
        val xs = FloatArray(n + 1)
        val ys = FloatArray(n + 1)
        for (i in 0..n) {
            val ang = PI - PI * i / n
            xs[i] = (CX + r * cos(ang)).toFloat()
            ys[i] = (BASE_Y - r * sin(ang)).toFloat()
        }
        return xs to ys
    }

    // -- draw --
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = clock()
        val s = smooth(blend)
        val br = breathe(t)

        val core = mix(O_CORE, G_CORE, s)
        val light = mix(O_LIGHT, G_LIGHT, s)
        val deep = mix(O_DEEP, G_DEEP, s)

        drawBackdrop(canvas, core, s, br)

        val k = minOf(width / D, height / D)
        canvas.save()
        canvas.translate((width - D * k) / 2f, (height - D * k) / 2f)
        canvas.scale(k, k)

        drawGroundRipples(canvas, t, s, core)
        drawPlinthShadow(canvas)
        // glass fill for every live shell, behind the fortress
        for (i in 0..2) {
            val (r, op) = shell(i)
            if (op > 0.01f) drawDomeFill(canvas, r * lerp(1f, br, s), s, core, op, i)
        }
        drawThreats(canvas, t, s, core, light)
        drawFortress(canvas, s, core, br)
        drawPlinth(canvas, s, core, light)
        // rims in front, outermost last so it sits on top
        for (i in 2 downTo 0) {
            val (r, op) = shell(i)
            if (op > 0.01f) drawDomeRim(canvas, t, r * lerp(1f, br, s), s, core, light, deep, op, i)
        }
        canvas.restore()
    }

    private fun drawBackdrop(canvas: Canvas, core: Int, s: Float, br: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, h, BG_TOP, BG_BOT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // brighter wash the more shield layers are up
        val boost = 1f + (hops - 1f) * 0.16f
        paint.shader = RadialGradient(
            w / 2f, h * 0.52f, maxOf(w, h) * 0.52f * br,
            intArrayOf(a(core, (0.18f + 0.07f * s) * boost), a(core, 0.05f * boost), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawGroundRipples(canvas: Canvas, t: Float, s: Float, core: Int) {
        if (s < 0.35f) return
        val amt = (s - 0.35f) / 0.65f
        for (i in 0..1) {
            val p = ((t / 2.8f) + i * 0.5f) % 1f
            val rx = lerp(90f, 172f, p)
            val ry = rx * 0.19f
            line(a(core, (1f - p) * (1f - p) * 0.28f * amt), lerp(2.6f, 0.7f, p))
            rect.set(CX - rx, BASE_Y - ry, CX + rx, BASE_Y + ry)
            canvas.drawOval(rect, paint)
        }
    }

    private fun drawPlinthShadow(canvas: Canvas) {
        fill(a(Color.BLACK, 0.35f))
        rect.set(CX - PLINTH_RX - 6f, BASE_Y + 2f, CX + PLINTH_RX + 6f, BASE_Y + 36f)
        canvas.drawOval(rect, paint)
    }

    private fun drawDomeFill(canvas: Canvas, r: Float, s: Float, core: Int, op: Float, idx: Int) {
        val (xs, ys) = domeFacetPoints(r)
        path.reset()
        path.moveTo(CX - r, BASE_Y)
        for (i in xs.indices) path.lineTo(xs[i], ys[i])
        path.lineTo(CX + r, BASE_Y)
        path.close()
        val baseAlpha = if (idx == 0) 0.06f else 0.035f
        fill(a(core, (baseAlpha + 0.05f * s) * op))
        canvas.drawPath(path, paint)
    }

    private fun drawThreats(canvas: Canvas, t: Float, s: Float, core: Int, light: Int) {
        val stopAt = lerp(30f, R_OUTER - 3f, s)

        dots.forEach { d ->
            val ang = Math.toRadians(d[0].toDouble())
            val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
            val p = (t * d[1] + d[2]) % 1f
            val r = lerp(210f, stopAt, p)
            val x = CX + ca * r
            val y = BASE_Y + sa * r

            val fade = when {
                p < 0.10f -> p / 0.10f
                p > 0.84f -> (1f - p) / 0.16f
                else -> 1f
            }

            line(a(mix(O_CORE, core, s * 0.35f), 0.26f * fade), 2.2f)
            canvas.drawLine(CX + ca * (r + 14f), BASE_Y + sa * (r + 14f), x, y, paint)
            fill(a(mix(O_LIGHT, light, s * 0.45f), 0.8f * fade))
            canvas.drawCircle(x, y, 3.4f, paint)

            if (s > 0.5f && p > 0.80f) {
                val f = (p - 0.80f) / 0.20f
                fill(a(light, (1f - f) * 0.75f * s))
                canvas.drawCircle(x, y, 3.5f + f * 15f, paint)
            }
        }
    }

    /**
     * The fortress cluster itself: a keep flanked by two corner towers, joined
     * by a thick rampart. Every block is a real extruded volume (front, top and
     * side faces), but the stonework colour never changes - only the glow
     * seams, sensor ports and dome facets carry the state colour.
     */
    private fun drawFortress(canvas: Canvas, s: Float, core: Int, br: Float) {
        canvas.save()
        canvas.translate(STRUCT_CX, BASE_Y)
        val sc = lerp(1f, br, s)
        canvas.scale(sc, sc)
        canvas.translate(-STRUCT_CX, -BASE_Y)

        drawWallBlock(canvas, s, core)
        drawTower(canvas, LT_CX, SIDE_BASE_HALF, SIDE_TOP_HALF, SIDE_HEIGHT, s, core, 2)
        drawTower(canvas, RT_CX, SIDE_BASE_HALF, SIDE_TOP_HALF, SIDE_HEIGHT, s, core, 2)
        drawTower(canvas, STRUCT_CX, KEEP_BASE_HALF, KEEP_TOP_HALF, KEEP_HEIGHT, s, core, 3)

        canvas.restore()
    }

    /** A single extruded tower frustum with a glow seam and status notches. */
    private fun drawTower(
        canvas: Canvas, cx: Float, baseHalf: Float, topHalf: Float, height: Float,
        s: Float, core: Int, notches: Int
    ) {
        val baseY = BASE_Y
        val topY = BASE_Y - height
        val bl = cx - baseHalf; val br = cx + baseHalf
        val tl = cx - topHalf; val tr = cx + topHalf
        val dim = lerp(0.55f, 1f, s)

        // side face (right return)
        path.reset()
        path.moveTo(br, baseY)
        path.lineTo(br + ISO_DX, baseY + ISO_DY)
        path.lineTo(tr + ISO_DX, topY + ISO_DY)
        path.lineTo(tr, topY)
        path.close()
        fill(BODY_SIDE)
        canvas.drawPath(path, paint)

        // front face (trapezoid) with an ambient-occlusion gradient
        path.reset()
        path.moveTo(bl, baseY)
        path.lineTo(br, baseY)
        path.lineTo(tr, topY)
        path.lineTo(tl, topY)
        path.close()
        fill(BODY)
        canvas.drawPath(path, paint)
        paint.shader = LinearGradient(
            0f, topY, 0f, baseY,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, a(Color.BLACK, 0.45f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, paint)
        paint.shader = null

        // top cap
        path.reset()
        path.moveTo(tl, topY)
        path.lineTo(tr, topY)
        path.lineTo(tr + ISO_DX, topY + ISO_DY)
        path.lineTo(tl + ISO_DX, topY + ISO_DY)
        path.close()
        fill(BODY_TOP)
        canvas.drawPath(path, paint)

        // crisp edges
        line(a(mix(BODY, Color.WHITE, 0.35f), 0.6f), 1.1f)
        canvas.drawLine(bl, baseY, tl, topY, paint)
        line(a(mix(BODY_TOP, Color.WHITE, 0.3f), 0.7f), 0.9f)
        canvas.drawLine(tl, topY, tr, topY, paint)
        canvas.drawLine(tr, topY, tr + ISO_DX, topY + ISO_DY, paint)
        line(a(BODY_SIDE, 0.6f), 0.6f)
        canvas.drawLine(br, baseY, tr, topY, paint)

        // glow seam up the centre front face - the one element carrying state colour
        val seamTop = topY + (baseY - topY) * 0.12f
        val seamBot = baseY - (baseY - topY) * 0.08f
        line(a(core, 0.20f * dim), 7f)
        canvas.drawLine(cx, seamBot, cx, seamTop, paint)
        line(a(core, 0.9f * dim), 2.2f)
        canvas.drawLine(cx, seamBot, cx, seamTop, paint)
        fill(a(core, 0.9f * dim))
        for (i in 0 until notches) {
            val frac = 0.3f + i * 0.32f
            val ny = seamBot - (seamBot - seamTop) * frac
            canvas.drawCircle(cx, ny, 1.6f, paint)
        }

        // small angular cap teeth
        fill(BODY_TOP)
        for (side in intArrayOf(-1, 1)) {
            val tx = cx + side * topHalf
            path.reset()
            path.moveTo(tx - 3f, topY)
            path.lineTo(tx, topY - 7f)
            path.lineTo(tx + 3f, topY)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    /**
     * The connecting rampart: heavy foundation lip, extruded wall block,
     * angular chevron battlements, sensor-port glow slits, and - when the
     * connection is down - a punched-through breach with fracture cracks
     * and drifting embers.
     */
    private fun drawWallBlock(canvas: Canvas, s: Float, core: Int) {
        val l = WALL_L; val r = WALL_R; val t = WALL_TOP; val b = BASE_Y
        val breach = 1f - s

        // heavy foundation lip - reads as the thick base the rampart sits on
        fill(BODY_SIDE)
        rect.set(l - 6f, b - 10f, r + 6f, b + 4f)
        canvas.drawRect(rect, paint)
        fill(a(BODY_TOP, 0.6f))
        rect.set(l - 6f, b - 10f, r + 6f, b - 5f)
        canvas.drawRect(rect, paint)

        // side face
        path.reset()
        path.moveTo(r, t)
        path.lineTo(r + WISO_DX, t + WISO_DY)
        path.lineTo(r + WISO_DX, b + WISO_DY)
        path.lineTo(r, b)
        path.close()
        fill(BODY_SIDE)
        canvas.drawPath(path, paint)

        // front face
        fill(BODY)
        rect.set(l, t, r, b)
        canvas.drawRect(rect, paint)
        paint.shader = LinearGradient(
            0f, t, 0f, b,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, a(Color.BLACK, 0.45f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        // top cap
        path.reset()
        path.moveTo(l, t)
        path.lineTo(r, t)
        path.lineTo(r + WISO_DX, t + WISO_DY)
        path.lineTo(l + WISO_DX, t + WISO_DY)
        path.close()
        fill(BODY_TOP)
        canvas.drawPath(path, paint)

        line(a(mix(BODY, Color.WHITE, 0.35f), 0.6f), 1.3f)
        canvas.drawLine(l, t, l, b, paint)
        line(a(mix(BODY_TOP, Color.WHITE, 0.3f), 0.7f), 1.0f)
        canvas.drawLine(l, t, r, t, paint)
        canvas.drawLine(r, t, r + WISO_DX, t + WISO_DY, paint)
        line(a(BODY_SIDE, 0.6f), 0.7f)
        canvas.drawLine(r, t, r, b, paint)

        // angular chevron teeth - one snaps off and crossfades in as the wall breaches
        val span = r - l
        val tw = span / (N_TEETH * 2 - 1)
        var x = l
        for (i in 0 until N_TEETH) {
            val apexY = t - WALL_TOOTH_H

            fill(a(BODY_TOP, s))
            path.reset()
            path.moveTo(x, t); path.lineTo(x + tw / 2f, apexY); path.lineTo(x + tw, t)
            path.close()
            canvas.drawPath(path, paint)

            fill(a(BODY_SIDE, s))
            path.reset()
            path.moveTo(x + tw, t)
            path.lineTo(x + tw / 2f, apexY)
            path.lineTo(x + tw / 2f + ISO_DX * 0.35f, apexY + ISO_DY * 0.35f)
            path.lineTo(x + tw + ISO_DX * 0.35f, t + ISO_DY * 0.35f)
            path.close()
            canvas.drawPath(path, paint)

            if (i == BREACH_TOOTH_IDX && breach > 0.03f) {
                val snapY = apexY + 6f
                fill(a(CAVITY, breach))
                path.reset()
                path.moveTo(x, t)
                path.lineTo(x + tw * 0.4f, snapY)
                path.lineTo(x + tw * 0.7f, t - 3f)
                path.lineTo(x + tw, t)
                path.close()
                canvas.drawPath(path, paint)
                line(a(core, 0.4f * breach), 0.8f)
                canvas.drawLine(x + tw * 0.4f, snapY, x + tw * 0.7f, t - 3f, paint)
            }
            x += tw * 2f
        }

        // breach cavity punched through the wall face itself
        if (breach > 0.04f) {
            val bx = l + span * 0.40f
            path.reset()
            path.moveTo(bx, t + 6f)
            path.lineTo(bx + 9f, t + 2f)
            path.lineTo(bx + 18f, t + 10f)
            path.lineTo(bx + 30f, t + 4f)
            path.lineTo(bx + 34f, t + 20f)
            path.lineTo(bx + 24f, t + 30f)
            path.lineTo(bx + 28f, b - 6f)
            path.lineTo(bx + 6f, b - 4f)
            path.lineTo(bx - 2f, t + 22f)
            path.lineTo(bx + 4f, t + 12f)
            path.close()
            fill(a(CAVITY, breach))
            canvas.drawPath(path, paint)
            line(a(core, 0.35f * breach), 1f)
            canvas.drawPath(path, paint)

            line(a(CAVITY, 0.8f * breach), 1.3f)
            canvas.drawLine(bx + 16f, t + 16f, bx - 14f, t + 34f, paint)
            canvas.drawLine(bx + 16f, t + 16f, bx + 42f, t + 8f, paint)
            canvas.drawLine(bx + 16f, t + 16f, bx + 10f, b - 2f, paint)
            canvas.drawLine(bx + 16f, t + 16f, bx + 40f, b - 8f, paint)

            fill(a(core, 0.75f * breach))
            canvas.drawCircle(bx + 8f, t - 4f, 1.3f, paint)
            canvas.drawCircle(bx + 26f, t + 0f, 1.0f, paint)
            canvas.drawCircle(bx + 16f, t - 10f, 0.8f, paint)
        }

        // sensor ports - two dead, one weakly flickering, when breached
        for (i in 0 until N_PORTS) {
            val px = l + (r - l) * (i + 0.5f) / N_PORTS
            val py = t + (b - t) * 0.55f
            val deadAlpha = when (i) {
                1, 3 -> 0f
                2 -> 0.35f
                else -> 0.85f
            }
            val op = lerp(deadAlpha, 0.85f, s)
            fill(a(core, op))
            rect.set(px - 4f, py - 2f, px + 4f, py + 2f)
            canvas.drawRoundRect(rect, 1f, 1f, paint)
            fill(a(core, lerp(0.06f, 0.18f, s)))
            rect.set(px - 7f, py - 4f, px + 7f, py + 4f)
            canvas.drawRoundRect(rect, 2f, 2f, paint)
        }
    }

    /**
     * [idx] 0 = outermost. Inner shells are drawn slightly thinner and dimmer,
     * and only the outermost shell carries the breach gap, apex node and
     * gloss highlight - keeping nested shells from turning muddy.
     */
    private fun drawDomeRim(
        canvas: Canvas, t: Float, r: Float, s: Float,
        core: Int, light: Int, deep: Int, op: Float, idx: Int
    ) {
        val depthFactor = 1f - idx * 0.22f

        if (state == State.CONNECTING) {
            line(a(mix(O_CORE, G_CORE, 0.55f), 0.22f * op), 3f)
            canvas.drawArc(domeRect(r), 180f, 180f, false, paint)
            val start = 180f + ((t * 150f + idx * 40f) % 180f)
            val sweep = minOf(70f, 360f - start)
            line(a(G_CORE, 0.9f * op * depthFactor), 4f)
            canvas.drawArc(domeRect(r), start, sweep, false, paint)
            return
        }

        val (xs, ys) = domeFacetPoints(r)
        val breach = if (idx == 0) (1f - s) else 0f
        val gapLo = 3
        val gapHi = 5

        fun drawRimPiece(fromI: Int, toI: Int) {
            path.reset()
            path.moveTo(xs[fromI], ys[fromI])
            for (i in (fromI + 1)..toI) path.lineTo(xs[i], ys[i])
            line(a(deep, 0.25f * op), 5f)
            canvas.drawPath(path, paint)
            line(a(core, (0.5f + 0.35f * s) * op * depthFactor), 1.6f)
            canvas.drawPath(path, paint)
        }

        if (idx == 0 && breach > 0.04f) {
            drawRimPiece(0, gapLo)
            drawRimPiece(gapHi, xs.size - 1)

            line(a(core, 0.5f * breach), 1.4f)
            canvas.drawLine(xs[gapLo], ys[gapLo], xs[gapLo] + 6f, ys[gapLo] + 10f, paint)
            canvas.drawLine(xs[gapHi], ys[gapHi], xs[gapHi] - 6f, ys[gapHi] + 9f, paint)

            fill(a(core, 0.7f * breach))
            canvas.drawCircle(CX - 10f, BASE_Y - r * 0.72f, 1.6f, paint)
            canvas.drawCircle(CX + 8f, BASE_Y - r * 0.76f, 1.2f, paint)
            canvas.drawCircle(CX, BASE_Y - r * 0.62f, 1.0f, paint)
        } else {
            drawRimPiece(0, xs.size - 1)
            if (idx == 0) {
                val apexX = xs[N_FACETS / 2]
                val apexY = ys[N_FACETS / 2]
                fill(a(core, 0.85f))
                canvas.drawCircle(apexX, apexY, 2.4f, paint)
                fill(a(light, 0.25f))
                canvas.drawCircle(apexX, apexY, 7f, paint)
            }
        }

        if (idx == 0) {
            line(a(Color.WHITE, 0.30f), 2f)
            canvas.drawLine(xs[1], ys[1], xs[2], ys[2], paint)
        }

        // bright equator collar - where the dome meets the plinth
        line(a(light, (0.55f + 0.35f * s) * op), 1.6f)
        rect.set(CX - r, BASE_Y - r * 0.15f, CX + r, BASE_Y + r * 0.15f)
        canvas.drawOval(rect, paint)
        if (idx == 0) {
            line(a(light, 0.18f), 5f)
            canvas.drawOval(rect, paint)
        }
    }

    private fun drawPlinth(canvas: Canvas, s: Float, core: Int, light: Int) {
        fill(a(mix(PLINTH_TOP, Color.BLACK, 0.45f), 1f))
        rect.set(CX - PLINTH_RX, BASE_Y - PLINTH_RY, CX + PLINTH_RX, BASE_Y + PLINTH_RY + 12f)
        canvas.drawRoundRect(rect, PLINTH_RX, PLINTH_RY, paint)

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, BASE_Y - PLINTH_RY, 0f, BASE_Y + PLINTH_RY,
            mix(PLINTH_LIP, PLINTH_TOP, 0.4f), PLINTH_TOP, Shader.TileMode.CLAMP
        )
        rect.set(CX - PLINTH_RX, BASE_Y - PLINTH_RY, CX + PLINTH_RX, BASE_Y + PLINTH_RY)
        canvas.drawOval(rect, paint)
        paint.shader = null

        // warm spill under the fortress - the cool/warm contrast
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            STRUCT_CX, BASE_Y - 2f, 70f,
            a(WARM, 0.20f + 0.16f * s), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        rect.set(CX - PLINTH_RX, BASE_Y - PLINTH_RY, CX + PLINTH_RX, BASE_Y + PLINTH_RY)
        canvas.drawOval(rect, paint)
        paint.shader = null

        line(a(core, 0.32f + 0.45f * s), 2.4f)
        rect.set(CX - PLINTH_RX, BASE_Y - PLINTH_RY, CX + PLINTH_RX, BASE_Y + PLINTH_RY)
        canvas.drawOval(rect, paint)
        line(a(light, 0.28f * s), 1.2f)
        rect.set(CX - PLINTH_RX + 7f, BASE_Y - PLINTH_RY + 4f, CX + PLINTH_RX - 7f, BASE_Y + PLINTH_RY - 4f)
        canvas.drawOval(rect, paint)
    }
}
