package com.steimerbyte.bulkcompressor.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.steimerbyte.bulkcompressor.R

/**
 * Eine Reihe Auswahl-Chips mit drei unterscheidbaren Zustaenden:
 *
 * 1. gewaehlt             - gefuellte weisse Flaeche, schwarze Schrift.
 * 2. waehlbar, nicht gewaehlt - Surface mit 1dp-Outline, graue Schrift.
 * 3. inaktiv (Zeile wirkt nicht) - nur eine sehr schwache 1dp-Outline,
 *    gedimmte Schrift, kein Klick, kein Fokus.
 *
 * Das Aussehen liegt vollstaendig in [R.drawable.chip_background] und
 * [R.color.chip_text] und wird ueber die View-States gezogen: gewaehlt laeuft
 * ueber state_selected, inaktiv ueber state_enabled=false. Der Code schaltet
 * deshalb nur States um und faerbt nichts selbst ein.
 *
 * Inaktiv ist bewusst NICHT "abwaehlen": [selectedIndex] und das
 * isSelected-Flag jedes Chips bleiben unangetastet, damit die Zeile beim
 * Reaktivieren exakt so aussieht wie vorher.
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

    /** true, solange die Reihe als wirkungslos dargestellt wird. */
    var isRowInactive: Boolean = false
        private set

    private val chips = ArrayList<TextView>(labels.size)

    /** Monospace regular - Zustand 2 und 3. */
    private val regularTypeface = Typeface.MONOSPACE

    /** "monospace-bold" - Zustand 1, damit die Auswahl auch ohne Farbe auffaellt. */
    private val selectedTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    init {
        val context = container.context
        val density = context.resources.displayMetrics.density
        val textColors = ContextCompat.getColorStateList(context, R.color.chip_text)
        // Ein Drawable je Chip: ein geteiltes Selector-Objekt wuerde den State
        // des zuletzt gezeichneten Chips an alle anderen weiterreichen.
        val backgroundTemplate = ContextCompat.getDrawable(context, R.drawable.chip_background)

        labels.forEachIndexed { index, label ->
            val chip = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = regularTypeface
                letterSpacing = LETTER_SPACING_NORMAL
                setTextColor(textColors)
                background = backgroundTemplate?.constantState?.newDrawable()?.mutate()
                    ?: backgroundTemplate
                gravity = Gravity.CENTER
                val horizontal = (14 * density).toInt()
                val vertical = (9 * density).toInt()
                setPadding(horizontal, vertical, horizontal, vertical)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    setSelected(index, notify = true)
                    // Der Kontrast allein reicht nicht: Screenreader bekommen
                    // die neue Auswahl als Ansage statt als reine Farbaenderung.
                    announceForAccessibility("$ANNOUNCE_PREFIX$label")
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
            container.addView(chip, params)
            chips.add(chip)
        }

        syncSelection()
    }

    fun setSelected(index: Int, notify: Boolean) {
        selectedIndex = index
        if (notify) onSelect(selectedIndex)
    }

    /**
     * Zustand 3: die ganze Zeile wirkt nicht mehr. Chips nehmen keinen Klick
     * und keinen Fokus an und treten optisch zurueck; die Auswahl selbst
     * bleibt gespeichert.
     */
    fun setRowInactive(inactive: Boolean) {
        isRowInactive = inactive
        chips.forEach { chip ->
            chip.isEnabled = !inactive
            chip.isClickable = !inactive
            chip.isFocusable = !inactive
            // Rein optisch: der Drawable-Zustand bleibt scharf, nur die ganze
            // Zeile tritt zurueck. Alpha aendert keine Groesse - kein Sprung.
            chip.alpha = if (inactive) INACTIVE_ALPHA else ACTIVE_ALPHA
        }
    }

    /** Gegenstueck zu [setRowInactive]: false = inaktiv, true = waehlbar. */
    fun setRowEnabled(enabled: Boolean) {
        setRowInactive(!enabled)
    }

    /**
     * Zieht Auswahl, Typografie und contentDescription auf [selectedIndex].
     * Typografie statt zusaetzlichem Praefix-Zeichen: ein "●" wuerde die
     * Chip-Breite aendern und die Reihe verschieben, breiterer Buchstaben-
     * abstand plus Fettung bleibt innerhalb der vorhandenen Flaeche.
     */
    private fun syncSelection() {
        chips.forEachIndexed { index, chip ->
            val selected = index == selectedIndex
            chip.isSelected = selected
            chip.letterSpacing = if (selected) LETTER_SPACING_SELECTED else LETTER_SPACING_NORMAL
            chip.typeface = if (selected) selectedTypeface else regularTypeface
            chip.contentDescription =
                if (selected) labels[index] + SELECTED_SUFFIX else labels[index]
        }
    }

    private companion object {
        const val LETTER_SPACING_NORMAL = 0.06f
        const val LETTER_SPACING_SELECTED = 0.10f
        const val INACTIVE_ALPHA = 0.45f
        const val ACTIVE_ALPHA = 1f
        const val SELECTED_SUFFIX = ", gewaehlt"
        const val ANNOUNCE_PREFIX = "Auswahl: "
    }
}
