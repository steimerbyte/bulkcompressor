package com.steimerbyte.bulkcompressor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar
import com.steimerbyte.bulkcompressor.R

/**
 * Regler in der Bildsprache der App: eine Reihe von Punkten statt eines
 * Balkens, dazu ein senkrechter Griff an der aktuellen Stelle.
 *
 * Warum ueberhaupt ein eigener Regler: der Systembalken ist ein duenner
 * Strich mit rundem Knopf - die Formensprache jeder zweiten Android-App,
 * und mit den Tints aus dem Theme blieb davon nur ein weisser Strich auf
 * Schwarz uebrig. Die Punktreihe ist dieselbe Sprache wie die Ladeanzeige
 * und die Abschnittsmarken; der Balken-Griff macht klar, wo der Wert
 * steht, ohne dass man den Punkt zaehlen muss.
 *
 * Die Tippflaeche bleibt die des [AppCompatSeekBar]: die Systemklasse
 * rechnet die Fingerposition auf `[paddingLeft, width - paddingRight]` um,
 * und genau diese Spanne fuellt die Punktreihe. Deshalb stimmt der
 * gezeichnete Griff mit dem Wert ueberein, den ein Tipp setzt. TalkBack
 * und die Lautstaerke-Tasten behalten ihre gewohnte Funktion.
 */
class NothingSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val activeColor = context.getColor(R.color.nothing_white)
    /**
     * Die leere Spur: eine Stufe heller als die Rahmenfarbe der Karten.
     * Mit `nothing_outline` verschwanden die Punkte vor dem schwarzen
     * Grund fast, und dann sah der Regler aus, als ende er am Griff.
     */
    private val idleColor = context.getColor(R.color.nothing_track)

    init {
        // Nichts vom System zeichnen lassen: Spur und Knopf werden
        // durchsichtig gesetzt, damit die Plattform ihre Geometrie-
        // rechnungen weiter ausfuehren kann (Tippen, Bedienhilfe,
        // Lautstaerketasten) und nur das Bild aus [onDraw] sichtbar ist.
        progressDrawable = ColorDrawable(Color.TRANSPARENT)
        splitTrack = false
        val handle = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setSize(dp(2f).toInt(), dp(2f).toInt())
        }
        thumb = handle
        thumbOffset = 0
        minimumHeight = dp(TOUCH_HEIGHT_DP).toInt()
        // Die Spur beginnt und endet um eine Punktbreite eingerueckt:
        // sonst wird der erste Punkt halb abgeschnitten und der Griff bei
        // 0 steht mit der Haelfte ausserhalb. Der Abstand gehoert zum
        // Regler, nicht ins Layout - die Tippflaeche rechnet mit
        // denselben Raendern, also bleibt Griff und Finger deckungsgleich.
        val inset = dp(DOT_RADIUS_DP + HEAD_HALF_WIDTH_DP).toInt()
        setPadding(inset, paddingTop, inset, paddingBottom)
    }

    /**
     * Der Systemregler rechnet seine Hoehe aus der Spur-Grafik; die ist
     * hier leer, und ohne eigene Rechnung waere der Regler 26 dp hoch -
     * der 18-dp-Griff waere knapp drin, die Tippflaeche zu klein.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val needed = (paddingTop + paddingBottom + dp(TOUCH_HEIGHT_DP)).toInt()
        if (measuredHeight < needed && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            setMeasuredDimension(measuredWidth, needed)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Bewusst ohne super.onDraw(): die Systemspur wuerde unter den
        // Punkten liegen und sie zu einem Strich mit Zacken machen.
        val span = width - paddingLeft - paddingRight
        if (span <= 0 || max <= 0) return

        val left = paddingLeft.toFloat()
        val centerY = height / 2f
        val ratio = (progress.toFloat() / max).coerceIn(0f, 1f)
        val pitch = span / (DOTS - 1f)
        // Beide Farben bleiben in beiden Zustaenden dieselben. Ein
        // gesperrter Regler wird vom Abschnitt mitgeblendet (die Gruppe
        // traegt die Deckkraft); eine zweite Abschwaechung hier halbierte
        // den Unterschied zwischen gefuellt und leer und machte den Wert
        // im gesperrten Abschnitt unlesbar.
        val lit = activeColor
        val idle = idleColor

        val radius = dp(DOT_RADIUS_DP)
        for (index in 0 until DOTS) {
            // Gefuellt wird nach Wert, nicht nach Anzahl: der Punkt an
            // der aktuellen Stelle gehoert schon zum Wert.
            val position = index / (DOTS - 1f)
            paint.color = if (position <= ratio + 0.0001f) lit else idle
            canvas.drawCircle(left + index * pitch, centerY, radius, paint)
        }

        // Griff: ein senkrechter Balken auf demselben Bruchteil wie der
        // Wert. Bezug ist die volle Tippflaeche - genau die Spanne, die
        // Tippen und Bedienhilfe benutzen.
        val headX = left + ratio * span
        paint.color = lit
        val halfWidth = dp(HEAD_HALF_WIDTH_DP)
        val halfHeight = dp(HEAD_HALF_HEIGHT_DP)
        canvas.drawRoundRect(
            headX - halfWidth,
            centerY - halfHeight,
            headX + halfWidth,
            centerY + halfHeight,
            halfWidth,
            halfWidth,
            paint
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** So viele Punkte hat die Spur - feste Zahl, nicht je Wert einer. */
        const val DOTS = 14

        /**
         * Tippflaeche: 18 dp Griff plus 11 dp Rand oben und unten. Die
         * Flaeche ist kleiner als die 48 dp eines Knopfes, weil der
         * Regler in einer beschrifteten Zeile sitzt und ein Wisch ueber
         * die ganze Breite das Ziel ohnehin sicher trifft.
         */
        const val TOUCH_HEIGHT_DP = 40f
        const val DOT_RADIUS_DP = 2.1f
        const val HEAD_HALF_WIDTH_DP = 1.8f
        const val HEAD_HALF_HEIGHT_DP = 9f

    }
}
