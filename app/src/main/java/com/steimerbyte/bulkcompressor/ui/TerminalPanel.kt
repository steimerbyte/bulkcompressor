package com.steimerbyte.bulkcompressor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.steimerbyte.bulkcompressor.R
import com.steimerbyte.bulkcompressor.log.LogLevel
import com.steimerbyte.bulkcompressor.log.LogLine
import kotlin.math.abs

/** Mehr Zeilen zeigt das Panel nicht; aeltere fallen beim Anhaengen heraus. */
private const val MAX_LINES = 400

/** Hoehe der zugeklappten Leiste (muss zu terminal_bar in view_terminal.xml passen). */
private const val BAR_HEIGHT_DP = 40

/** Bevorzugte Hoehe im ausgeklappten Zustand. */
private const val EXPANDED_HEIGHT_DP = 264

/** Untergrenze im ausgeklappten Zustand, auch auf kleinen Bildschirmen. */
private const val MIN_EXPANDED_DP = 220

/** Obergrenze relativ zur Hoehe des Parent (Bildschirm). */
private const val MAX_HEIGHT_FRACTION = 0.75f

/** Toleranz, um das Scroll-Ende als erreicht zu werten. */
private const val BOTTOM_SLACK_DP = 8

/**
 * Ausklappbares Live-Terminal am unteren Rand.
 *
 * Die Zeilen liegen in diesem Objekt (maximal [MAX_LINES]); gezeichnet wird
 * genau ein TextView mit einem [SpannableStringBuilder] und Farbspans je
 * Level. Dadurch bleibt das Anhaengen einer Zeile eine Textoperation statt
 * eines View-Aufbaus.
 *
 * Aufrufe von aussen laufen auf dem Main-Thread (Log.addListener feuert dort).
 *
 * @param activity Activity fuer Ressourcen, Zwischenablage und Bildschirmgroesse.
 * @param root der per <include> eingebundene Root von view_terminal.xml.
 * @param onCopy zusaetzlich zum Befuellen der Zwischenablage.
 * @param onShare teilt den Protokolltext (Ablage entscheidet der Aufrufer).
 * @param onClear folgt dem LEER-Knopf, nachdem die Anzeige geleert wurde.
 */
class TerminalPanel(
    private val activity: AppCompatActivity,
    root: View,
    private val onCopy: () -> Unit,
    private val onShare: () -> Unit,
    private val onClear: () -> Unit
) {

    private val panel: View = root
    private val bar: View = root.findViewById(R.id.terminal_bar)
    private val countView: TextView = root.findViewById(R.id.terminal_count)
    private val newView: TextView = root.findViewById(R.id.terminal_new)
    private val chevron: TextView = root.findViewById(R.id.terminal_chevron)
    private val scroll: ScrollView = root.findViewById(R.id.terminal_scroll)
    private val text: TextView = root.findViewById(R.id.terminal_text)
    private val toolbar: View = root.findViewById(R.id.terminal_toolbar)
    private val divider: View = root.findViewById(R.id.terminal_divider)

    private val colorDim = color(R.color.nothing_dim)
    private val colorWhite = color(R.color.nothing_white)
    private val colorGrey = color(R.color.nothing_grey)
    private val colorRed = color(R.color.nothing_red)

    private val density = root.resources.displayMetrics.density
    private val barHeightPx = dp(BAR_HEIGHT_DP)
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop

    /** Alle gehaltenen Zeilen, aelteste zuerst. */
    private val buffer = ArrayList<LogLine>(MAX_LINES)

    private var expanded = false

    /** true = die Ansicht folgt dem Ende; false = der Nutzer liest oben. */
    private var followTail = true

    private var downRawY = 0f
    private var dragStartHeight = 0
    private var dragging = false

    /** Ein Repaint pro Frame reicht; Zeilen treffen oft im Buendel ein. */
    private var renderPending = false

    /** Zugeklappt wurde nicht gezeichnet; der Text ist noch zu bauen. */
    private var textDirty = false

    /** Zugeklappt bleibt nur die Leiste stehen, samt 1dp-Outline darueber. */
    private val collapsedPx = barHeightPx + dp(1)

    val isExpanded: Boolean
        get() = expanded

    init {
        wireBar()
        wireToolbar()
        wireScroll()
        applyExpanded(false)
        renderNow()
    }

    // ------------------------------------------------------------------ API

    fun setLines(lines: List<LogLine>) {
        buffer.clear()
        val from = (lines.size - MAX_LINES).coerceAtLeast(0)
        for (index in from until lines.size) buffer.add(lines[index])
        if (buffer.isEmpty()) followTail = true
        renderNow()
    }

    fun append(line: LogLine) {
        if (buffer.size >= MAX_LINES) buffer.removeAt(0)
        buffer.add(line)
        scheduleRender()
    }

    fun clearView() {
        buffer.clear()
        followTail = true
        renderNow()
    }

    fun setExpanded(expanded: Boolean) {
        applyExpanded(expanded)
    }

    fun lineCount(): Int = buffer.size

    // ------------------------------------------------------------- Aufbau

    private fun wireBar() {
        // Tippen klappt um; ans Ende springt nur der "v NEU"-Hinweis.
        bar.setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            applyExpanded(!expanded)
        }
        newView.setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            jumpToEnd()
        }

        bar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    dragStartHeight = if (panel.height > 0) panel.height else collapsedPx
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = downRawY - event.rawY
                    if (!dragging && abs(delta) > touchSlop) {
                        dragging = true
                        // Inhalt einblenden, bevor die Hoehe waechst, sonst
                        // waere die Bewegung unsichtbar.
                        if (!expanded) {
                            expanded = true
                            setContentVisible(true)
                            if (followTail) scrollToEnd()
                        }
                    }
                    if (dragging) setPanelHeight(dragStartHeight + delta.toInt())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapTo(panel.height)
                    } else {
                        bar.performClick()
                    }
                    dragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapTo(panel.height)
                    dragging = false
                    true
                }
                else -> true
            }
        }
    }

    private fun wireToolbar() {
        panel.findViewById<View>(R.id.terminal_action_copy).setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            copyToClipboard()
            onCopy()
        }
        panel.findViewById<View>(R.id.terminal_action_share).setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            onShare()
        }
        panel.findViewById<View>(R.id.terminal_action_clear).setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            // Auch ohne Zutun des Aufrufers muss die Anzeige leer werden.
            clearView()
            onClear()
        }
        panel.findViewById<View>(R.id.terminal_action_close).setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            applyExpanded(false)
        }
    }

    private fun wireScroll() {
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val childHeight = scroll.getChildAt(0)?.height ?: 0
            val atBottom = scrollY + scroll.height >= childHeight - dp(BOTTOM_SLACK_DP)
            // Nur echte Nutzerbewegungen landen hier; programmatische Spruenge
            // ans Ende setzen followTail vorher schon auf true.
            followTail = atBottom
            updateBadge()
        }

        // Nachwachsender Text laesst die Hoehe wachsen, ohne dass sich scrollY
        // aendert; deshalb hier nachziehen statt nur im Scroll-Listener.
        text.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (expanded && followTail && bottom != oldBottom) scrollToEnd()
        }
    }

    // -------------------------------------------------------------- Zustaende

    private fun applyExpanded(state: Boolean, heightPx: Int = -1) {
        expanded = state
        setContentVisible(state)
        setPanelHeight(
            when {
                !state -> collapsedPx
                heightPx > 0 -> heightPx
                else -> expandedHeightPx()
            }
        )
        if (state && textDirty) renderNow()
        if (state && followTail) scrollToEnd()
        updateBadge()
    }

    /** Rasten auf die naechste Stufe: zugeklappt, 264dp oder 75 %. */
    private fun snapTo(currentPx: Int) {
        val stops = intArrayOf(collapsedPx, expandedHeightPx(), maxExpandedPx())
        var best = stops[0]
        for (stop in stops) {
            if (abs(stop - currentPx) < abs(best - currentPx)) best = stop
        }
        if (best <= collapsedPx) {
            applyExpanded(false)
        } else {
            applyExpanded(true, best)
        }
    }

    private fun setContentVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        scroll.visibility = visibility
        toolbar.visibility = visibility
        divider.visibility = visibility
        chevron.setText(if (visible) R.string.terminal_chevron_expanded else R.string.terminal_chevron)
    }

    private fun setPanelHeight(px: Int) {
        val params = panel.layoutParams ?: return
        val clamped = px.coerceIn(collapsedPx, maxExpandedPx())
        if (params.height == clamped) return
        params.height = clamped
        panel.layoutParams = params
    }

    private fun expandedHeightPx(): Int =
        dp(EXPANDED_HEIGHT_DP).coerceIn(dp(MIN_EXPANDED_DP), maxExpandedPx())

    /** 75 % der verfuegbaren Hoehe, aber nie weniger als die Mindesthoehe. */
    private fun maxExpandedPx(): Int {
        val parentHeight = (panel.parent as? View)?.height ?: 0
        val screenHeight =
            if (parentHeight > 0) parentHeight else activity.resources.displayMetrics.heightPixels
        val limit = (screenHeight * MAX_HEIGHT_FRACTION).toInt()
        return limit.coerceAtLeast(dp(MIN_EXPANDED_DP))
    }

    // --------------------------------------------------------------- Zeichnen

    /**
     * Baut den kompletten Text neu. Nur bei setLines, clearView und einmal je
     * Frame beim Streamen aufrufen, nicht pro Zeile.
     */
    private fun renderNow() {
        renderPending = false
        // Zahl und Abzeichen sind billig und auch zugeklappt sichtbar.
        countView.text = activity.resources
            .getQuantityString(R.plurals.terminal_line_count, buffer.size, buffer.size)
        updateBadge()

        // Zugeklappt ist der Text unsichtbar. Ihn trotzdem bei jeder Zeile
        // neu zu bauen (bis 400 Zeilen, je ein Span) hiess: waehrend eines
        // Laufs oder beim Ziehen eines Reglers wurde bis zu 60-mal pro
        // Sekunde die gesamte Bildschirmbreite neu gesetzt, ohne dass etwas
        // davon zu sehen war. Gemerkt wird es sich - beim Aufklappen wird
        // gebaut.
        if (!expanded) {
            textDirty = true
            return
        }
        textDirty = false

        val builder = SpannableStringBuilder()
        if (buffer.isEmpty()) {
            builder.append(activity.getString(R.string.terminal_empty))
            builder.setSpan(
                ForegroundColorSpan(colorDim), 0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            for (index in buffer.indices) {
                if (index > 0) builder.append('\n')
                val start = builder.length
                builder.append(buffer[index].format())
                builder.setSpan(
                    ForegroundColorSpan(colorFor(buffer[index].level)),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        text.setText(builder, TextView.BufferType.SPANNABLE)
        if (followTail) scrollToEnd()
    }

    /**
     * Fasst mehrere Anhaenge eines Frames zu einem Repaint zusammen. Der
     * Textview haengt am Main-Looper, deshalb genuegt ein post.
     */
    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        text.post { if (renderPending) renderNow() }
    }

    private fun colorFor(level: LogLevel): Int = when (level) {
        LogLevel.V, LogLevel.D -> colorDim
        LogLevel.I -> colorWhite
        LogLevel.W -> colorGrey
        LogLevel.E -> colorRed
    }

    // -------------------------------------------------------------- Scrollen

    private fun scrollToEnd() {
        if (!expanded) return
        scroll.post {
            val childHeight = scroll.getChildAt(0)?.height ?: 0
            val target = (childHeight - scroll.height).coerceAtLeast(0)
            if (scroll.scrollY != target) scroll.scrollTo(0, target)
        }
    }

    private fun jumpToEnd() {
        followTail = true
        updateBadge()
        scrollToEnd()
    }

    private fun updateBadge() {
        val show = !followTail && buffer.isNotEmpty()
        newView.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ Aktionen

    private fun copyToClipboard() {
        val clipboard =
            activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val content = text.text?.toString().orEmpty()
        clipboard.setPrimaryClip(
            ClipData.newPlainText(activity.getString(R.string.terminal_clip_label), content)
        )
    }

    private fun color(resource: Int): Int = ContextCompat.getColor(activity, resource)

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
