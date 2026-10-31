package com.steimerbyte.bulkcompressor.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * Behaelter fuer Chip-Reihen, der umbricht statt abzuschneiden.
 *
 * Eine waagerechte [LinearLayout] schneidet alles jenseits der Breite
 * einfach ab: auf einem 360dp breiten Geraet war "720p" in der
 * Aufloesungsreihe halb zu sehen und nicht antippbar - eine Auswahl, die es
 * gibt, aber nicht erreicht. Hier werden die Chips zeilenweise gesetzt, und
 * was nicht mehr passt, landet in der naechsten Zeile.
 *
 * Erbt von [LinearLayout], damit die Chips direkte Kinder bleiben und
 * `getChildAt(i)` weiter der i-te Chip ist - sowohl fuer den Code als auch
 * fuer Zusicherungen in Tests.
 */
class WrappingRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Abstand zwischen zwei Zeilen. */
    private val lineGap: Int = (6 * resources.displayMetrics.density + 0.5f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val horizontalPadding = paddingLeft + paddingRight
        val available = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (widthSize - horizontalPadding).coerceAtLeast(0)
        }

        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = 0
        var widestLine = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChild(child, available)
            val width = child.measuredWidth + gapAfter(child)
            val height = child.measuredHeight
            if (lineWidth > 0 && lineWidth + width > available) {
                totalHeight += lineHeight + lineGap
                widestLine = maxOf(widestLine, lineWidth)
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += width
            lineHeight = maxOf(lineHeight, height)
        }
        totalHeight += lineHeight
        widestLine = maxOf(widestLine, lineWidth)

        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(widthSize, widestLine + horizontalPadding)
            else -> widestLine + horizontalPadding
        }
        val measuredHeight = totalHeight + paddingTop + paddingBottom
        setMeasuredDimension(
            measuredWidth,
            resolveSize(measuredHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val width = child.measuredWidth
            if (x > paddingLeft && x + width > paddingLeft + available) {
                x = paddingLeft
                y += lineHeight + lineGap
                lineHeight = 0
            }
            child.layout(x, y, x + width, y + child.measuredHeight)
            x += width + gapAfter(child)
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }

    /**
     * Laesst das Kind hoechstens so breit werden wie eine Zeile. Ein Chip,
     * der allein schon zu breit ist, wird nicht abgeschnitten, sondern darf
     * die Zeile ueberragen - besser sichtbar zu breit als unsichtbar.
     */
    private fun measureChild(child: View, available: Int) {
        val lp = child.layoutParams
        val widthSpec = if (lp.width > 0) {
            MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY)
        } else {
            MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST)
        }
        val heightSpec = if (lp.height > 0) {
            MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
        } else {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        }
        child.measure(widthSpec, heightSpec)
    }

    /** Chips tragen ihren Abstand selbst als marginEnd mit. */
    private fun gapAfter(child: View): Int {
        val lp = child.layoutParams
        return if (lp is MarginLayoutParams) lp.marginEnd else 0
    }
}
