package com.steimerbyte.bulkcompressor.engine

import android.content.ComponentCallbacks2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Die Einordnung von Speicherdruck. Sie entscheidet, ob der Lauf bremst -
 * deshalb muss sie ohne Geraet pruefbar sein: Heap-Anteile und die
 * Meldungen des Systems sind Werte, die man nicht "mal eben" einstellt.
 */
class MemoryGuardTest {

    @After
    fun cleanUp() {
        MemoryGuard.reset()
    }

    @Test
    fun `viel freier Heap bleibt ohne Drosselung`() {
        assertEquals(MemoryGuard.Level.OK, MemoryGuard.classify(0.10f, 0))
        assertEquals(MemoryGuard.Level.OK, MemoryGuard.classify(0.71f, 0))
    }

    @Test
    fun `Heap ueber der ersten Schwelle drosselt`() {
        assertEquals(MemoryGuard.Level.THROTTLE, MemoryGuard.classify(0.72f, 0))
        assertEquals(MemoryGuard.Level.THROTTLE, MemoryGuard.classify(0.84f, 0))
    }

    @Test
    fun `Heap ueber der harten Schwelle drosselt stark`() {
        assertEquals(MemoryGuard.Level.HARD, MemoryGuard.classify(0.85f, 0))
        assertEquals(MemoryGuard.Level.HARD, MemoryGuard.classify(1.0f, 0))
    }

    @Test
    fun `Systemmeldung schlaegt die Heapschaetzung`() {
        // Der eigene Heap ist entspannt, das System meldet trotzdem knapp:
        // die Meldung gilt, sie kommt vom Geraet und nicht von einer
        // Momentaufnahme des eigenen Prozesses.
        assertEquals(
            MemoryGuard.Level.THROTTLE,
            MemoryGuard.classify(0.05f, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        )
        assertEquals(
            MemoryGuard.Level.HARD,
            MemoryGuard.classify(0.05f, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        )
        assertEquals(
            MemoryGuard.Level.HARD,
            MemoryGuard.classify(0.05f, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        )
    }

    @Test
    fun `App im Hintergrund ist kein Speicherdruck`() {
        // Wuerde UI_HIDDEN als Druck gelten, bremste jeder Wechsel in den
        // Hintergrund den Lauf - genau das Gegenteil des Zwecks.
        assertEquals(
            MemoryGuard.Level.OK,
            MemoryGuard.classify(0.10f, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        )
        assertEquals(
            MemoryGuard.Level.OK,
            MemoryGuard.classify(0.10f, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        )
        // Auch das Niveau "Prozess wird weggeraeumt" ist kein Druck, dem
        // Warten etwas entgegensetzt.
        assertEquals(
            MemoryGuard.Level.OK,
            MemoryGuard.classify(0.10f, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        )
    }

    @Test
    fun `decode-Budget sinkt mit dem Speicherdruck`() {
        MemoryGuard.reset()
        val relaxed = MemoryGuard.decodeBudgetDivisor()

        MemoryGuard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        val throttled = MemoryGuard.decodeBudgetDivisor()

        MemoryGuard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        val hard = MemoryGuard.decodeBudgetDivisor()

        assertEquals(6, relaxed)
        assertEquals(10, throttled)
        assertEquals(16, hard)
        // Ein groesserer Nenner heisst kleineres Budget - sonst waere die
        // Drosselung eine Beschleunigung.
        assert(hard > throttled && throttled > relaxed)
    }

    @Test
    fun `relax nimmt die Systemmeldung zurueck`() {
        MemoryGuard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(MemoryGuard.Level.HARD, MemoryGuard.level())

        MemoryGuard.relax()
        // Der eigene Heap im Test ist frei; nach relax bleibt nur noch er.
        assertEquals(MemoryGuard.Level.OK, MemoryGuard.level())
    }

    @Test
    fun `ohne Speicherdruck wird nicht gewartet`() {
        MemoryGuard.reset()
        if (MemoryGuard.level() == MemoryGuard.Level.OK) {
            assertEquals(0L, MemoryGuard.pauseBefore())
        }
    }
}
