package com.steimerbyte.bulkcompressor.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.steimerbyte.bulkcompressor.R

/**
 * Eine Reihe Auswahl-Chips. Gefuellt = gewaehlt, Umriss = nicht
 * gewaehlt; die Umschaltung laeuft ueber state_selected, damit das
 * Aussehen im Drawable bleibt und nicht im Code.
 */
class ChipRow(
    private val container: LinearLayout,
    private val labels: List<String>,
    private val onSelect: (Int) -> Unit
) {

    var selectedIndex: Int = 0
        set(value) {
            field = if (labels.isEmpty()) 0 else value.coerceIn(0, labels.size - 1)
            syncSelection()
        }

    private val chips = ArrayList<TextView>(labels.size)

    init {
        val context = container.context
        val density = context.resources.displayMetrics.density
        val textColors = ContextCompat.getColorStateList(context, R.color.chip_text)

        labels.forEachIndexed { index, label ->
            val chip = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.06f
                setTextColor(textColors)
                background = ContextCompat.getDrawable(context, R.drawable.chip_background)
                gravity = Gravity.CENTER
                val horizontal = (14 * density).toInt()
                val vertical = (9 * density).toInt()
                setPadding(horizontal, vertical, horizontal, vertical)
                isClickable = true
                isFocusable = true
                isSelected = index == 0
                setOnClickListener { setSelected(index, notify = true) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
            container.addView(chip, params)
            chips.add(chip)
        }
    }

    fun setSelected(index: Int, notify: Boolean) {
        selectedIndex = index
        if (notify) onSelect(selectedIndex)
    }

    private fun syncSelection() {
        chips.forEachIndexed { index, chip -> chip.isSelected = index == selectedIndex }
    }
}
