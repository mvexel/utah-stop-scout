package org.osmutah.utahbusstop

import android.content.Context
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Colors shared by every screen. */
internal object Palette {
    const val PAPER = 0xFFF5F7F1.toInt()
    const val INK = 0xFF1D2B26.toInt()
    const val MUTED = 0xFF4A5A53.toInt()
    const val GREEN = 0xFF2A7A4B.toInt()
    const val GREEN_SOFT = 0xFFDCEEDF.toInt()
    const val BORDER = 0xFFDDE5DC.toInt()
    const val AMBER = 0xFFF4B942.toInt()
    const val WARN_BG = 0xFFFFF3D6.toInt()
    const val WARN_INK = 0xFF4A3300.toInt()
    const val DISABLED = 0xFFCFD8CE.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
}

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun Context.dpf(value: Float) = value * resources.displayMetrics.density

internal fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dpf(radiusDp.toFloat())
    if (strokeColor != null) setStroke(dp(1), strokeColor)
}

/** Keep custom controls as responsive as native Android buttons, including keyboard focus. */
internal fun Context.touchSurface(fill: Int, radiusDp: Int, stroke: Int? = null, lightRipple: Boolean = false) =
    RippleDrawable(
        ColorStateList.valueOf(if (lightRipple) 0x33FFFFFF else 0x222A7A4B),
        rounded(fill, radiusDp, stroke),
        rounded(Palette.WHITE, radiusDp),
    )

internal fun Context.label(value: String, sizeSp: Int, color: Int = Palette.INK, bold: Boolean = false) = TextView(this).apply {
    text = value
    textSize = sizeSp.toFloat()
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setLineSpacing(0f, 1.15f)
}

internal fun Context.card(radiusDp: Int = 24, fill: Int = Palette.WHITE, stroke: Int? = Palette.BORDER, content: LinearLayout.() -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(fill, radiusDp, stroke)
    setPadding(dp(18), dp(16), dp(18), dp(16))
    content()
}

private fun Context.pill(text: String, fill: Int, ink: Int, stroke: Int?, enabled: Boolean, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    textSize = 18f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(ink)
    stateListAnimator = null
    background = touchSurface(fill, 28, stroke, lightRipple = fill == Palette.GREEN)
    setPadding(dp(20), dp(12), dp(20), dp(12))
    minimumHeight = dp(56)
    minHeight = dp(56)
    isEnabled = enabled
    setOnClickListener { onClick() }
}

internal fun Context.primaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    pill(text, if (enabled) Palette.GREEN else Palette.DISABLED, if (enabled) Palette.WHITE else 0xFF3F4C46.toInt(), null, enabled, onClick)

internal fun Context.secondaryButton(text: String, onClick: () -> Unit) =
    pill(text, Palette.WHITE, Palette.INK, Palette.BORDER, true, onClick)

internal fun Context.textLink(text: String, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    textSize = 16f
    setTextColor(Palette.MUTED)
    setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER
    minHeight = dp(48)
    background = touchSurface(android.graphics.Color.TRANSPARENT, 16)
    setPadding(dp(12), 0, dp(12), 0)
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

/** A little bus-stop sign gives the masthead the same character as the map pins. */
internal class BusBadge(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val u = width / 28f
        paint.color = Palette.GREEN
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 8f * u, 8f * u, paint)
        paint.color = Palette.WHITE
        canvas.drawRoundRect(RectF(6f * u, 6f * u, 22f * u, 21f * u), 3f * u, 3f * u, paint)
        paint.color = Palette.GREEN
        canvas.drawRoundRect(RectF(8f * u, 8f * u, 20f * u, 14f * u), u, u, paint)
        canvas.drawCircle(10f * u, 19f * u, 1.5f * u, paint)
        canvas.drawCircle(18f * u, 19f * u, 1.5f * u, paint)
    }
}

/** A round badge whose arrow points toward a stop. [pointAt] turns it to match which way the phone is facing. */
internal class DirectionArrow(
    context: Context,
    private val bearing: Float,
    ringColor: Int = Palette.GREEN_SOFT,
    arrowColor: Int = Palette.GREEN,
) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ringColor }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = arrowColor; style = Paint.Style.STROKE; strokeWidth = context.dpf(2.6f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    init { pointAt(null) }

    /** [heading] is the compass direction the phone faces; without one the arrow shows the compass bearing, north up. */
    fun pointAt(heading: Float?) { rotation = bearing - (heading ?: 0f) }

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        canvas.drawCircle(c, c, c, ring)
        val r = width * 0.27f
        val path = Path().apply {
            moveTo(c, c + r); lineTo(c, c - r)
            moveTo(c - r * 0.7f, c - r * 0.3f); lineTo(c, c - r); lineTo(c + r * 0.7f, c - r * 0.3f)
        }
        canvas.drawPath(path, ink)
    }
}

/** The friendly bus-stop picture on the welcome screen. */
internal class BusStopArt(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val s = width / 390f
        fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
            fill.color = color
            canvas.drawRoundRect(RectF(l * s, t * s, r * s, b * s), radius * s, radius * s, fill)
        }
        fun circle(x: Float, y: Float, r: Float, color: Int) { fill.color = color; canvas.drawCircle(x * s, y * s, r * s, fill) }
        val top = (height - 400 * s) / 2f
        canvas.save()
        canvas.translate(0f, top)
        circle(70f, 90f, 60f, 0xFFC9E4CF.toInt())
        circle(340f, 320f, 80f, 0xFFC9E4CF.toInt())
        rect(150f, 150f, 164f, 340f, 7f, Palette.INK)
        rect(92f, 110f, 222f, 196f, 18f, Palette.GREEN)
        rect(108f, 126f, 206f, 180f, 10f, Palette.PAPER)
        rect(124f, 159f, 190f, 167f, 4f, Palette.GREEN)
        rect(130f, 138f, 184f, 146f, 4f, Palette.GREEN)
        rect(232f, 206f, 336f, 264f, 16f, Palette.WHITE)
        rect(244f, 218f, 324f, 244f, 8f, Palette.AMBER)
        circle(256f, 262f, 9f, Palette.INK)
        circle(318f, 262f, 9f, Palette.INK)
        circle(300f, 82f, 30f, Palette.AMBER)
        circle(300f, 80f, 12f, Palette.INK)
        circle(300f, 80f, 5f, Palette.AMBER)
        canvas.restore()
    }
}

/** A small round-cornered sign used for stops on the map. */
internal fun stopMarkerBitmap(context: Context, color: Int, sizeDp: Int): Bitmap {
    val px = context.dp(sizeDp)
    val bitmap = Bitmap.createBitmap(px, (px * 1.18f).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = px * 0.07f
    val body = RectF(stroke, stroke, px - stroke, px - stroke)
    val tip = Path().apply {
        moveTo(px * 0.34f, px - stroke * 2); lineTo(px * 0.5f, px * 1.16f); lineTo(px * 0.66f, px - stroke * 2); close()
    }
    paint.color = Palette.WHITE
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = stroke * 2
    paint.strokeJoin = Paint.Join.ROUND
    canvas.drawRoundRect(body, px * 0.28f, px * 0.28f, paint)
    canvas.drawPath(tip, paint)
    paint.style = Paint.Style.FILL
    paint.color = color
    canvas.drawRoundRect(body, px * 0.28f, px * 0.28f, paint)
    canvas.drawPath(tip, paint)
    // A tiny bus: window band, body, two wheels.
    paint.color = Palette.WHITE
    canvas.drawRoundRect(RectF(px * 0.24f, px * 0.24f, px * 0.76f, px * 0.64f), px * 0.08f, px * 0.08f, paint)
    paint.color = color
    canvas.drawRect(px * 0.3f, px * 0.31f, px * 0.7f, px * 0.45f, paint)
    canvas.drawCircle(px * 0.36f, px * 0.62f, px * 0.055f, paint)
    canvas.drawCircle(px * 0.64f, px * 0.62f, px * 0.055f, paint)
    paint.color = Palette.WHITE
    canvas.drawCircle(px * 0.36f, px * 0.62f, px * 0.03f, paint)
    canvas.drawCircle(px * 0.64f, px * 0.62f, px * 0.03f, paint)
    return bitmap
}

/** A small bus that drives along a road: scrolling road dashes, spinning wheels and a gentle bounce. */
internal class RidingBus(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val u = width / 300f
        fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
            paint.color = color
            canvas.drawRoundRect(RectF(l * u, t * u, r * u, b * u), radius * u, radius * u, paint)
        }
        // Road, with dashes sliding backward so the bus seems to move forward.
        rect(-10f, 108f, 310f, 134f, 0f, 0xFF3A4A44.toInt())
        for (i in -1..6) {
            val x = i * 50f - phase * 50f
            rect(x, 119f, x + 24f, 123f, 2f, Palette.WHITE)
        }
        // Speed lines behind the bus.
        for (i in 0..2) {
            val shift = ((phase + i / 3f) % 1f)
            paint.color = Palette.GREEN_SOFT
            paint.alpha = ((1f - shift) * 255).toInt()
            canvas.drawRoundRect(RectF((70f - shift * 50f) * u, (58f + i * 14f) * u, (92f - shift * 50f) * u, (61f + i * 14f) * u), 2f * u, 2f * u, paint)
            paint.alpha = 255
        }
        val bob = (Math.sin(phase * 2 * Math.PI * 2) * 1.6f).toFloat()
        rect(90f, 40f + bob, 214f, 102f + bob, 14f, Palette.GREEN)
        rect(100f, 50f + bob, 204f, 72f + bob, 7f, Palette.PAPER)
        for (i in 1..3) rect(100f + i * 26f - 1.5f, 50f + bob, 100f + i * 26f + 1.5f, 72f + bob, 0f, Palette.GREEN)
        paint.color = Palette.AMBER
        canvas.drawCircle(207f * u, 88f * u + bob * u, 4f * u, paint)
        for (cx in floatArrayOf(120f, 184f)) {
            paint.color = Palette.INK
            canvas.drawCircle(cx * u, 104f * u, 11f * u, paint)
            paint.color = Palette.WHITE
            canvas.drawCircle(cx * u, 104f * u, 5.5f * u, paint)
            canvas.save()
            canvas.rotate(phase * 360f, cx * u, 104f * u)
            paint.color = Palette.INK
            canvas.drawRect((cx - 0.9f) * u, 99f * u, (cx + 0.9f) * u, 109f * u, paint)
            canvas.restore()
        }
    }
}
