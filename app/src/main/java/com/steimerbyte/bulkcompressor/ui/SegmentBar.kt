package com.steimerbyte.bulkcompressor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.steimerbyte.bulkcompressor.engine.MemoryGuard

/**
 * Fortschritt als Reihe von Segmenten - die Nothing-Entsprechung zu
 * einem Balken. Ein Segment ist entweder gesetzt oder es steht als
 * duenner Umriss da.
 */
class SegmentBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /**
     * Farbe der gesetzten Segmente. Beim Fortschritt bleibt sie weiss;
     * die Speichertafel faerbt den Balken nach Speicherdruck um, damit
     * man die Grenze sieht, bevor die App sie spuert.
     */
    fun setLevel(level: MemoryGuard.Level) {
        filledPaint.color = when (level) {
            MemoryGuard.Level.OK -> Color.WHITE
            MemoryGuard.Level.THROTTLE -> Color.parseColor("#9A9A9A")
            MemoryGuard.Level.HARD -> Color.parseColor("#D71921")
        }
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private val segmentCount = 28
    private val gap = 3f * density
    private val radius = 1.5f * density

    private val filledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val totalGap = gap * (segmentCount - 1)
        val segmentWidth = (width - totalGap) / segmentCount
        if (segmentWidth <= 0f) return

        val height = height.toFloat()
        val active = kotlin.math.floor(progress * segmentCount).toInt()
        for (index in 0 until segmentCount) {
            val left = index * (segmentWidth + gap)
            val right = left + segmentWidth
            val paint = if (index < active) filledPaint else emptyPaint
            canvas.drawRoundRect(left, 1f, right, height - 1f, radius, radius, paint)
        }
    }
}
