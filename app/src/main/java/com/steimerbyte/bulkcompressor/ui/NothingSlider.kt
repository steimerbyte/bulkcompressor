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
 * Regler in der Bildsprache der App: eine durchgehende Schiene, darauf
 * die Punktreihe als Skala und ein runder Knopf an der aktuellen Stelle.
 *
 * Warum ueberhaupt ein eigener Regler: der Systembalken ist ein duenner
 * Strich mit rundem Knopf - die Formensprache jeder zweiten Android-App,
 * und mit den Tints aus dem Theme blieb davon nur ein weisser Strich auf
 * Schwarz uebrig.
 *
 * Warum eine Schiene und nicht nur Punkte: eine Reihe einzelner Punkte
 * ist eine Skala, kein Regler. Man sieht nicht, dass sie von einem
 * Anschlag zum anderen reicht, und eine Laenge liest man daran auch
 * nicht ab. Die 1,5 dp hohe Schiene traegt die Spanne, die Punkte liegen
 * als Skala darauf, die gefuellten Punkte sind weiss - der Wert ist
 * damit an zwei Merkmalen ablesbar (Punkte und Knopfstellung), ohne dass
 * eines davon die Form des Reglers verdraengt.
 *
 * Die Tippflaeche bleibt die des [AppCompatSeekBar]: die Systemklasse
 * rechnet die Fingerposition auf `[paddingLeft, width - paddingRight]`
 * um, und genau diese Spanne fuellt die Schiene. Deshalb stimmt der
 * gezeichnete Knopf mit dem Wert ueberein, den ein Tipp setzt. TalkBack
 * und die Lautstaerke-Tasten behalten ihre gewohnte Funktion.
 */
class NothingSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val activeColor = context.getColor(R.color.nothing_white)

    private val coreColor = Color.BLACK

    /**
     * Die leere Spur: eine Stufe heller als die Rahmenfarbe der Karten.
     * Mit `nothing_outline` verschwanden Schiene und Punkte vor dem
     * schwarzen Grund fast, und dann sah der Regler aus, als ende er am
     * Knopf.
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
        // Schiene und Knopf bleiben innerhalb der Ansicht: der Abstand
        // muss mindestens den Knopfradius fassen, sonst steht der Knopf
        // bei 0 % und 100 % zur Haelfte ausserhalb und wird am Kartenrand
        // beschnitten. Der Abstand gehoert zum Regler, nicht ins Layout -
        // die Tippflaeche rechnet mit denselben Raendern, also bleibt
        // Knopf und Finger deckungsgleich.
        val inset = dp(INSET_DP).toInt()
        setPadding(inset, paddingTop, inset, paddingBottom)
    }

    /**
     * Der Systemregler rechnet seine Hoehe aus der Spur-Grafik; die ist
     * hier leer, und ohne eigene Rechnung waere der Regler 24 dp hoch -
     * der 18-dp-Knopf waere knapp drin, die Tippflaeche zu klein.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val needed = (paddingTop + paddingBottom + dp(TOUCH_HEIGHT_DP)).toInt()
        if (measuredHeight < needed && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            setMeasuredDimension(measuredWidth, needed)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Bewusst ohne super.onDraw(): die Systemspur wuerde unter der
        // eigenen Zeichnung liegen.
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

        // Die Schiene traegt die Spanne von Anschlag zu Anschlag. Sie ist
        // absichtlich duenn: sie soll die Strecke zeigen, nicht den Wert.
        paint.color = idle
        canvas.drawRect(
            left,
            centerY - dp(RAIL_HALF_HEIGHT_DP),
            left + span,
            centerY + dp(RAIL_HALF_HEIGHT_DP),
            paint
        )

        val radius = dp(DOT_RADIUS_DP)
        for (index in 0 until DOTS) {
            // Gefuellt wird nach Wert, nicht nach Anzahl: der Punkt an
            // der aktuellen Stelle gehoert schon zum Wert.
            val position = index / (DOTS - 1f)
            paint.color = if (position <= ratio + 0.0001f) lit else idle
            canvas.drawCircle(left + index * pitch, centerY, radius, paint)
        }

        // Knopf: weisser Ring mit dunklem Kern, auf demselben Bruchteil
        // wie der Wert. Bezug ist die volle Tippflaeche - genau die
        // Spanne, die Tippen und Bedienhilfe benutzen. Der Knopf bleibt
        // ganz innerhalb der Ansicht (Radius <= INSET_DP), damit er
        // weder am Kartenrand abgeschnitten wird noch in die Zeile mit
        // der Prozentzahl darueber ragt.
        val headX = left + ratio * span
        paint.color = lit
        canvas.drawCircle(headX, centerY, dp(KNOB_RADIUS_DP), paint)
        paint.color = coreColor
        canvas.drawCircle(headX, centerY, dp(KNOB_RING_OUTER_DP), paint)
        paint.color = lit
        canvas.drawCircle(headX, centerY, dp(KNOB_CORE_DP), paint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** So viele Punkte hat die Skala - feste Zahl, nicht je Wert einer. */
        const val DOTS = 14

        /**
         * Tippflaeche: 18 dp Knopf plus 11 dp Rand oben und unten. Die
         * Flaeche ist kleiner als die 48 dp eines Knopfes, weil der
         * Regler in einer beschrifteten Zeile sitzt und ein Wisch ueber
         * die ganze Breite das Ziel ohnehin sicher trifft.
         */
        const val TOUCH_HEIGHT_DP = 40f

        /** Knopfradius; zugleich der Mindestabstand zum Kartenrand. */
        const val KNOB_RADIUS_DP = 9f

        /** Innenkante des dunklen Rings im Knopf. */
        const val KNOB_RING_OUTER_DP = 6f

        /** Heller Kern in der Mitte des Knopfes. */
        const val KNOB_CORE_DP = 4.5f

        /** Rand je Seite: Knopfradius plus 2 dp Luft zum Kartenrand. */
        const val INSET_DP = KNOB_RADIUS_DP + 2f

        /** Halbe Hoehe der Schiene: 1,5 dp hoch, absichtlich duenn. */
        const val RAIL_HALF_HEIGHT_DP = 0.75f

        /** Radius der Skalenpunkte. */
        const val DOT_RADIUS_DP = 2.1f
    }
}
