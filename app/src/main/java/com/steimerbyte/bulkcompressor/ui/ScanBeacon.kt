package com.steimerbyte.bulkcompressor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import com.steimerbyte.bulkcompressor.R

/**
 * Kleine Punktmatrix, die laeuft, solange ein Ordner gelesen wird.
 *
 * Drei mal drei Punkte, ein heller Kopf mit kurzem Nachlauf - dieselbe
 * Bildsprache wie der Rest der App (Punktmatrix statt Fortschrittsrad).
 * Die Ansicht zeichnet im Ruhezustand gar nichts, behaelt aber ihren
 * Platz, damit beim Start des Scans nichts im Layout springt.
 */
class ScanBeacon @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.nothing_white)
    }

    private var running = false
    private var animated = true
    private var phase = 0f
    private var lastFrame = 0L

    // Der Typ steht ausgeschrieben da: ein selbstbezueglicher Lambda-Wert
    // laesst den Kotlin-Typpruefer reihum laufen.
    private val frame: Runnable = Runnable {
        if (!running) return@Runnable
        val now = SystemClock.uptimeMillis()
        val delta = if (lastFrame == 0L) 0L else now - lastFrame
        lastFrame = now
        phase = (phase + delta / CYCLE_MS) % 1f
        invalidate()
        postOnAnimation(frame)
    }

    /** Startet den Lauf; mehrfacher Aufruf ist harmlos. */
    fun start() {
        if (running) return
        running = true
        phase = 0f
        lastFrame = 0L
        animated = animationsEnabled()
        invalidate()
        if (animated) postOnAnimation(frame)
    }

    fun stop() {
        if (!running) return
        running = false
        removeCallbacks(frame)
        invalidate()
    }

    /**
     * Ist im System „Animationen aus" gewaehlt, bleibt es bei einem
     * stehenden Muster: der Scan laeuft weiter, das Bild zappelt nur
     * nicht. Wer Animationen abschaltet, meint das ernst.
     */
    private fun animationsEnabled(): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }.getOrDefault(true)

    override fun onDetachedFromWindow() {
        // Ohne diesen Stopp liefe der Frame-Callback nach dem Abbau weiter
        // und hielte die Ansicht am Leben.
        running = false
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val dot = minOf(width, height) / (CELLS * 2f + 1f)
        val pitch = dot * 2f
        val offsetX = (width - pitch * (CELLS - 1)) / 2f
        val offsetY = (height - pitch * (CELLS - 1)) / 2f
        // Der Kopf wandert zeilenweise; zwei Punkte Nachlauf geben dem
        // Bild eine Richtung, ohne wie ein Ladekreis zu wirken.
        val head = if (animated) phase * CELLS * CELLS else CELLS * CELLS.toFloat()
        for (index in 0 until CELLS * CELLS) {
            val column = index % CELLS
            val row = index / CELLS
            val distance = (head - index + CELLS * CELLS) % (CELLS * CELLS).toFloat()
            val glow = (1f - distance / TRAIL).coerceIn(0f, 1f)
            paint.alpha = (DIM_ALPHA + (255 - DIM_ALPHA) * glow).toInt()
            canvas.drawCircle(
                offsetX + column * pitch,
                offsetY + row * pitch,
                dot * (0.75f + 0.25f * glow),
                paint
            )
        }
    }

    private companion object {
        const val CELLS = 3
        const val CYCLE_MS = 900f
        const val TRAIL = 3.2f
        const val DIM_ALPHA = 46
    }
}
