package com.steimerbyte.bulkcompressor.ui

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import com.steimerbyte.bulkcompressor.R
import com.steimerbyte.bulkcompressor.model.CompressSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSoundPool

/**
 * Prueft, ob ein Klick tatsaechlich beim Audiosystem ankommt - und ob die
 * Abschaltgruende greifen.
 *
 * Zwei Fehler hat dieser Test aufgedeckt, beide ohne Geraet sichtbar:
 *
 * 1. Die Sperre gegen zu schnelle Wiederholung verglich gegen
 *    `Long.MIN_VALUE`; `now - Long.MIN_VALUE` laeuft im Long-Bereich ueber
 *    und wurde negativ, also war die Bedingung immer wahr. Der Zeitstempel
 *    wurde erst danach gesetzt - der Ton ging nie los, nicht nur beim
 *    ersten Mal.
 * 2. Zusaetzlich hing die App an der Systemeinstellung
 *    "Beruehrungsklaenge". Die ist auf vielen Geraeten ab Werk aus; der
 *    Schalter in der App stand dann sichtbar auf an, ohne Ton und ohne
 *    Grund.
 *
 * Alle Einstellungen werden ueber **dasselbe** AudioManager-Objekt
 * gesetzt, das [SoundKit] benutzt. In Robolectric ist
 * `getSystemService(AUDIO_SERVICE)` nicht zwingend ein Einzelstueck; ein
 * zweites Objekt haette einen eigenen Schattenzustand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SoundKitTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private val audio: AudioManager get() = requireNotNull(SoundKit.audioForTest())

    private fun systemSoundEffects(on: Boolean) {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SOUND_EFFECTS_ENABLED,
            if (on) 1 else 0
        )
    }

    private fun playedTick(): Boolean {
        val pool = SoundKit.poolForTest() ?: return false
        return Shadow.extract<ShadowSoundPool>(pool).wasResourcePlayed(R.raw.click_tick)
    }

    private fun tickPlaybacks(): Int {
        val pool = SoundKit.poolForTest() ?: return 0
        return Shadow.extract<ShadowSoundPool>(pool).getResourcePlaybacks(R.raw.click_tick).size
    }

    @Before
    fun setUp() {
        // SoundKit ist ein Objekt: ohne Zuruecksetzen traegt es Sperrzeiten
        // und geladene Puffer aus einem vorherigen Test in diesen hinein.
        SoundKit.release()
        SoundKit.init(context, enabled = true)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        // Fester Wert satt Maximum: die Vorgabe des Schattens ist 0, und
        // damit waere "kein Ton" aus dem falschen Grund richtig gewesen.
        audio.setStreamVolume(AudioManager.STREAM_SYSTEM, 7, 0)
    }

    @After
    fun tearDown() {
        SoundKit.setEnabled(true)
        systemSoundEffects(true)
        SoundKit.release()
    }

    @Test
    fun `klick erreicht das audiosystem`() {
        assertNotNull("SoundPool muss aufgebaut sein", SoundKit.poolForTest())

        SoundKit.play(SoundKit.Click.TICK)

        assertTrue("Der Klick muss an SoundPool.play gehen", playedTick())
    }

    @Test
    fun `systemeinstellung beruehrungsklaenge schaltet die app nicht mehr ab`() {
        // Genau der zweite Fehler: diese Einstellung ist auf vielen
        // Geraeten ab Werk aus, und die App schwieg dazu ohne Grund.
        systemSoundEffects(false)

        SoundKit.play(SoundKit.Click.TICK)

        assertTrue("Die Systemeinstellung darf den App-Klick nicht schlucken", playedTick())
    }

    @Test
    fun `lautloser modus unterdrueckt den klick`() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT

        SoundKit.play(SoundKit.Click.TICK)

        assertFalse("Im Lautlos-Modus darf nicht geklickt werden", playedTick())
    }

    @Test
    fun `vibration unterdrueckt den klick`() {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        SoundKit.play(SoundKit.Click.TICK)

        assertFalse("Auf Vibration darf nicht geklickt werden", playedTick())
    }

    @Test
    fun `systemlautstaerke null unterdrueckt den klick`() {
        audio.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)

        SoundKit.play(SoundKit.Click.TICK)

        assertFalse("Ohne Systemlautstaerke kein Ton", playedTick())
    }

    @Test
    fun `abgeschalteter app schalter unterdrueckt den klick`() {
        SoundKit.setEnabled(false)

        SoundKit.play(SoundKit.Click.TICK)

        assertFalse("App-Schalter aus muss den Ton abschalten", playedTick())

        SoundKit.setEnabled(true)
        SoundKit.play(SoundKit.Click.TICK)
        assertTrue("App-Schalter an muss den Ton wieder freigeben", playedTick())
    }

    @Test
    fun `zu schnelle wiederholung desselben klicks wird verschluckt`() {
        SoundKit.play(SoundKit.Click.TICK)
        val afterFirst = tickPlaybacks()
        assertEquals("Der erste Klick muss spielen", 1, afterFirst)

        SoundKit.play(SoundKit.Click.TICK)

        assertEquals(
            "Der zweite Klick im Mindestabstand darf nicht spielen",
            afterFirst,
            tickPlaybacks()
        )
    }

    @Test
    fun `alle drei klickdateien sind vorhanden und dekodierbare wav`() {
        listOf(R.raw.click_tick, R.raw.click_toggle, R.raw.click_thunk).forEach { id ->
            val bytes = context.resources.openRawResource(id).use { it.readBytes() }

            assertTrue("Ressource $id ist zu kurz", bytes.size > 1000)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            // Abtastrate steht little-endian bei Byte 24.
            val rate = (bytes[24].toInt() and 0xFF) or
                ((bytes[25].toInt() and 0xFF) shl 8) or
                ((bytes[26].toInt() and 0xFF) shl 16) or
                ((bytes[27].toInt() and 0xFF) shl 24)
            assertEquals("Abtastrate von $id", 44100, rate)
        }
    }

    @Test
    fun `klickeinstellung ueberlebt speichern und laden`() {
        val prefs = context.getSharedPreferences(CompressSettings.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        assertEquals("Vorgabe muss an sein", true, CompressSettings().clickSounds)

        CompressSettings(clickSounds = false).save(prefs)
        assertFalse(CompressSettings.load(prefs).clickSounds)

        CompressSettings(clickSounds = true).save(prefs)
        assertTrue(CompressSettings.load(prefs).clickSounds)
    }

    @Test
    fun `standardwerte lassen alle anderen einstellungen in ruhe`() {
        val prefs = context.getSharedPreferences(CompressSettings.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val expected = CompressSettings()
        val loaded = CompressSettings.load(prefs)

        assertEquals(expected, loaded)
    }
}
