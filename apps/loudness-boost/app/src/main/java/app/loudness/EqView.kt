package app.loudness

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The 15-band EQ as one curve: a grid from -[range] to +[range] dB, a smooth line through the band gains with a soft
 * fill to 0 dB, and a handle per band. Drag anywhere in a band's column to set it (0.5 dB steps); the value shows above
 * the handle while dragging.
 */
class EqView(context: Context, private val colors: Palette) : View(context) {

    var bands = FloatArray(Boost.CENTERS.size)
        set(value) { field = value.copyOf(); invalidate() }

    /** Called with the band and its new gain while dragging. */
    var onBandChanged: ((Int, Float) -> Unit)? = null

    private val range = Boost.BAND_RANGE_DB
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var active = -1
    private val plot = RectF()

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f); color = colors.outline }
    private val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1.5f); color = colors.outlineStrong }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colors.accent
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.accent }
    private val dotCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.card }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.accent; alpha = 50 }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f)
        color = colors.textSecondary
        textAlign = Paint.Align.CENTER
    }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.accent }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f)
        color = colors.onAccent
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Room on the left for the dB scale, above for the value bubble, below for the frequencies
        plot.set(dp(38f), dp(30f), w - dp(10f), h - dp(26f))
        fill.shader = LinearGradient(
            0f, plot.top, 0f, plot.bottom,
            intArrayOf(colors.accent and 0x00FFFFFF or 0x55000000, colors.accent and 0x00FFFFFF),
            null, Shader.TileMode.CLAMP
        )
    }

    private fun x(i: Int) = plot.left + plot.width() * i / (bands.size - 1)
    private fun y(db: Float) = plot.centerY() - db / range * plot.height() / 2

    override fun onDraw(canvas: Canvas) {
        // Grid every 6 dB, the 0 dB line stronger
        label.textAlign = Paint.Align.RIGHT
        for (db in floatArrayOf(range, range / 2, 0f, -range / 2, -range)) {
            canvas.drawLine(plot.left, y(db), plot.right, y(db), if (db == 0f) zero else grid)
            val text = if (db > 0) "+${db.toInt()}" else "${db.toInt()}"
            canvas.drawText(text, plot.left - dp(14f), y(db) + dp(4f), label)
        }

        // The curve (Catmull-Rom through the band points), filled down to 0 dB
        val curve = Path()
        curve.moveTo(x(0), y(bands[0]))
        for (i in 0 until bands.size - 1) {
            val p0 = maxOf(i - 1, 0)
            val p3 = minOf(i + 2, bands.size - 1)
            curve.cubicTo(
                x(i) + (x(i + 1) - x(p0)) / 6, y(bands[i]) + (y(bands[i + 1]) - y(bands[p0])) / 6,
                x(i + 1) - (x(p3) - x(i)) / 6, y(bands[i + 1]) - (y(bands[p3]) - y(bands[i])) / 6,
                x(i + 1), y(bands[i + 1])
            )
        }
        val area = Path(curve).apply {
            lineTo(x(bands.size - 1), y(0f))
            lineTo(x(0), y(0f))
            close()
        }
        canvas.drawPath(area, fill)
        canvas.drawPath(curve, line)

        // Handles and frequencies
        label.textAlign = Paint.Align.CENTER
        for (i in bands.indices) {
            val cx = x(i)
            val cy = y(bands[i])
            if (i == active) canvas.drawCircle(cx, cy, dp(16f), halo)
            canvas.drawCircle(cx, cy, dp(if (i == active) 8f else 6f), dot)
            canvas.drawCircle(cx, cy, dp(if (i == active) 3.5f else 2.5f), dotCenter)
            // Every other frequency label, so they never crowd on a narrow screen
            if (i % 2 == 0) canvas.drawText(hz(Boost.CENTERS[i]), cx, plot.bottom + dp(20f), label)
        }

        if (active >= 0) {
            val cx = x(active).coerceIn(plot.left + dp(22f), plot.right - dp(22f))
            val top = (y(bands[active]) - dp(40f)).coerceAtLeast(0f)
            canvas.drawRoundRect(cx - dp(24f), top, cx + dp(24f), top + dp(22f), dp(11f), dp(11f), bubble)
            canvas.drawText("%+.1f".format(bands[active]), cx, top + dp(15.5f), bubbleText)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = nearestBand(event.x)
                // Keep the scroll view from taking a vertical drag
                parent.requestDisallowInterceptTouchEvent(true)
                drag(event.y)
            }
            MotionEvent.ACTION_MOVE -> drag(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                parent.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    private fun nearestBand(px: Float): Int =
        bands.indices.minBy { abs(x(it) - px) }

    private fun drag(py: Float) {
        if (active < 0) return
        val db = ((plot.centerY() - py) / (plot.height() / 2) * range).coerceIn(-range, range)
        val snapped = (db * 2).roundToInt() / 2f
        if (snapped != bands[active]) {
            bands[active] = snapped
            onBandChanged?.invoke(active, snapped)
        }
        invalidate()
    }

    private fun hz(f: Float) = if (f >= 1000f) "%.1fk".format(f / 1000f).replace(".0k", "k") else "${f.toInt()}"
}
