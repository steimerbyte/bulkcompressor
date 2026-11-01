package com.steimerbyte.bulkcompressor.ui

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * Der Regler zeichnet seine Spur selbst und laesst die Flaeche der
 * Systemklasse zum Tippen stehen. Genau daran haengt die eine Falle
 * dieses Aufbaus: zeichnet der Griff auf einem anderen Bruchteil als dem,
 * den ein Tipp setzt, steht der Griff nach dem Tippen woanders als der
 * Finger. Auf einem Geraet faellt das sofort auf, im Test nur mit einer
 * Zusicherung gegen die Rechnung der Systemklasse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NothingSliderTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun slider(progress: Int): NothingSlider {
        val view = NothingSlider(context)
        view.max = 90
        view.progress = progress
        view.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, 720, view.measuredHeight)
        return view
    }

    private fun tapAt(view: NothingSlider, fraction: Float) {
        val x = (view.width - view.paddingLeft - view.paddingRight) * fraction + view.paddingLeft
        val y = view.height / 2f
        val now = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test
    fun `ein tipp auf drei viertel setzt drei viertel`() {
        val view = slider(0)

        tapAt(view, 0.75f)

        assertEquals(
            "Der Griff steht auf dem Bruchteil, den der Finger trifft",
            (view.max * 0.75f).roundToInt().toFloat(),
            view.progress.toFloat(),
            1.5f
        )
    }

    @Test
    fun `der regler ist hoch genug fuer griff und finger`() {
        val view = slider(50)

        // 18 dp Griff + 11 dp Rand je Seite. Robolectric rechnet bei
        // mdpi mit 1 px je dp.
        assertTrue(
            "Regler ist nur ${view.measuredHeight} px hoch - der Griff wuerde beschnitten",
            view.measuredHeight >= 40
        )
    }
}
