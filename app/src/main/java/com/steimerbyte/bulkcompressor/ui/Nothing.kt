package com.steimerbyte.bulkcompressor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Tokens und Bausteine des NothingOS-Layoutsystems (Skill nothingos-app).
 * Kopierbar und identisch in allen Apps der Familie: hier aendert man
 * nichts, ohne es in den anderen Apps genauso zu aendern.
 */
internal val Accent = Color(0xFFD71921)
internal val Dim = Color(0xFF7A7A7A)
internal val Panel = Color(0xFF0D0D0D)
internal val Line = Color(0xFF242424)
internal val Good = Color(0xFF39D98A)
internal val Warn = Color(0xFFE0A32E)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Color.Black,
            surface = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

// ---- Typografie ----

@Composable
fun Micro(text: String, color: Color = Dim) {
    Text(
        text, color = color, fontSize = 9.sp,
        fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp
    )
}

@Composable
fun Mono(text: String, color: Color = Dim) {
    Text(
        text, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        letterSpacing = 0.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}

/** Kompakter Absatz in der Groesse des App-Namens. */
@Composable
fun Body(text: String, color: Color = Color.White.copy(alpha = 0.85f)) {
    Text(
        text, color = color, fontSize = 12.sp, letterSpacing = 0.3.sp,
        fontFamily = FontFamily.Monospace
    )
}

// ---- 6.1 OSW ----

/** Kicker, Titel, Aktion oben rechts. Der Kicker oeffnet die About-Sektion. */
@Composable
fun Osw(
    kicker: String,
    title: String,
    action: (@Composable () -> Unit)? = null,
    onKickerClick: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                kicker, color = Dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                letterSpacing = 1.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (onKickerClick == null) Modifier
                else Modifier.clickable { onKickerClick() }.padding(vertical = 4.dp)
            )
            Text(
                title.uppercase(Locale.GERMANY), color = Color.White, fontSize = 28.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}

// ---- 6.2 Buttons ----

@Composable
fun MiniButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(36.dp)
            .border(1.dp, if (enabled) Line else Line.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text.uppercase(Locale.GERMANY), color = if (enabled) Color.White else Dim,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp, maxLines = 1
        )
    }
}

@Composable
fun WideButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (enabled) Accent else Line, RoundedCornerShape(2.dp))
            .background(
                if (pressed && enabled) Accent.copy(alpha = 0.18f) else Color.Transparent,
                RoundedCornerShape(2.dp)
            )
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) {
                onClick()
            }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text.uppercase(Locale.GERMANY), color = if (enabled) Color.White else Dim,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Bold, maxLines = 1
        )
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    // 32 dp Optik, 44 dp Touch-Ziel.
    Box(Modifier.height(44.dp).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .height(32.dp)
                .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(2.dp))
                .background(
                    if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent,
                    RoundedCornerShape(2.dp)
                )
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text.uppercase(Locale.GERMANY), color = if (selected) Accent else Dim,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp,
                maxLines = 1
            )
        }
    }
}

/** Nur Anzeige: der Status einer Datei oder eines Abschnitts. */
@Composable
fun Chip(text: String, tone: Color = Dim) {
    Box(
        Modifier
            .height(24.dp)
            .border(1.dp, tone.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text.uppercase(Locale.GERMANY), color = tone, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp, maxLines = 1
        )
    }
}

/** AN in Dim, AUS in Warn - wie im Skill vorgegeben. */
@Composable
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label.uppercase(Locale.GERMANY), color = Color.White, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (on) "AN" else "AUS", color = if (on) Dim else Warn,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp
        )
    }
}

// ---- 6.3 Panels ----

@Composable
fun OutlineBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Line, RoundedCornerShape(2.dp))
            .padding(10.dp),
        content = content
    )
}

/** Kennzahlen, zwei Spalten pro Zeile: 22-sp-Werte passen nicht in vier. */
@Composable
fun SummaryPanel(items: List<Pair<String, String>>) {
    OutlineBox {
        items.chunked(2).forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { i, (label, value) ->
                    if (i > 0) Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Micro(label)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            value, color = Color.White, fontSize = 22.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * [cpuLabel] nennt, was der CPU-Wert misst. Ohne Shizuku ist /proc/stat
 * gesperrt; dann zeigt die Zeile den eigenen Prozess (`CPU · PROZESS`).
 */
@Composable
fun LivePanel(
    cpu: List<Float>,
    ram: List<Float>,
    cpuText: String = "",
    ramText: String = "",
    cpuLabel: String = "CPU"
) {
    OutlineBox {
        HistRow(cpuLabel, cpu, cpuText)
        Spacer(Modifier.height(6.dp))
        HistRow("RAM", ram, ramText, unit = "GIB")
    }
}

@Composable
private fun HistRow(label: String, history: List<Float>, value: String, unit: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Micro(label)
        Spacer(Modifier.width(10.dp))
        Histogram(history, Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        if (value.isEmpty()) {
            Micro("KEINE DATEN")
        } else {
            Text(
                value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, maxLines = 1
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Micro(unit)
            }
        }
    }
}

/** 48 Slots, 46 dp hoch, Balken 62 % Slotbreite; belegt Accent, leer Line. */
@Composable
private fun Histogram(history: List<Float>, modifier: Modifier = Modifier) {
    val slots = 48
    Canvas(modifier.fillMaxWidth().height(46.dp)) {
        val slotWidth = size.width / slots
        val barWidth = slotWidth * 0.62f
        val shift = slots - history.size
        for (i in 0 until slots) {
            val index = i - shift
            val value = if (index >= 0) history[index].coerceIn(0f, 1f) else 0f
            val barHeight = if (value > 0f) (size.height * value).coerceAtLeast(2f) else 1f
            val x = i * slotWidth + (slotWidth - barWidth) / 2f
            drawRect(
                color = if (value > 0f) Accent else Line,
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight)
            )
        }
    }
}

/** Anteil als 2-dp-Balken: Rest in Line, ab 100 % in Good. */
@Composable
fun Bar(ratio: Float) {
    val share = ratio.coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(2.dp).background(Line)) {
        Box(
            Modifier
                .fillMaxWidth(share)
                .fillMaxHeight()
                .background(if (share >= 1f) Good else Accent)
        )
    }
}

// ---- 6.4 Eingaben ----

@Composable
fun SearchField(text: String, onChange: (String) -> Unit, hits: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .border(1.dp, Line, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Micro("SUCHE")
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Micro("DATEINAME")
            BasicTextField(
                value = text,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (text.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text("×", color = Dim, fontSize = 17.sp, modifier = Modifier.clickable { onChange("") })
        }
        Spacer(Modifier.width(8.dp))
        Micro("$hits TREFFER", if (hits == 0) Warn else Dim)
    }
}

@Composable
fun Hint(text: String, color: Color = Dim, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable { onClick() })
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            letterSpacing = 1.4.sp, maxLines = 2, textAlign = TextAlign.Center
        )
    }
}

/**
 * About nach Skill, Abschnitt 4: Name, Version + Geraet, Leitspruch allein
 * in Accent, Zweck, echte Kernkommandos. Keine Links, keine Lizenztexte.
 */
@Composable
fun AboutPanel(name: String, version: String, purpose: String, commands: List<String>) {
    Column {
        Text(
            name.uppercase(Locale.GERMANY), color = Color.White, fontSize = 28.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(6.dp))
        Micro("Version $version")
        Spacer(Modifier.height(14.dp))
        Text(
            "Function over form.", color = Accent, fontSize = 22.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(12.dp))
        Text(
            purpose, color = Color.White, fontSize = 12.sp, letterSpacing = 0.3.sp,
            fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(14.dp))
        Micro("KERNKOMMANDOS")
        Spacer(Modifier.height(4.dp))
        commands.forEach { Mono(it) }
    }
}
