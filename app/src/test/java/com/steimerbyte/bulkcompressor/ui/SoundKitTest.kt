package com.steimerbyte.bulkcompressor.ui

import android.content.Context
import android.provider.Settings
import com.steimerbyte.bulkcompressor.R
import com.steimerbyte.bulkcompressor.model.CompressSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Prueft die Entscheidung "klingelt es oder nicht" und die Klickdateien
 * selbst.
 *
 * Der Ton haengt an zwei Schaltern: dem in der App und der
 * Systemeinstellung fuer Beruehrungsklaenge. Wer die Systemeinstellung
 * abgeschaltet hat, darf von der App keine Klicks bekommen - genau das
 * ist die Regel, die man beim Selbsttest auf dem Geraet nur schwer
 * merkt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SoundKitTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun systemSoundEffects(on: Boolean) {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SOUND_EFFECTS_ENABLED,
            if (on) 1 else 0
        )
    }

    @Before
    fun setUp() {
        SoundKit.init(context, enabled = true)
        systemSoundEffects(true)
        SoundKit.refreshSystemSetting(context)
    }

    @After
    fun tearDown() {
        SoundKit.setEnabled(true)
        systemSoundEffects(true)
        SoundKit.release()
    }

    @Test
    fun `systemeinstellung schaltet die klicks ab und wieder an`() {
        assertTrue("Klicks sollten an sein", SoundKit.enabled)

        systemSoundEffects(false)
        SoundKit.refreshSystemSetting(context)
        assertFalse("Systemeinstellung aus muss die Klicks abschalten", SoundKit.enabled)

        systemSoundEffects(true)
        SoundKit.refreshSystemSetting(context)
        assertTrue("Systemeinstellung an muss die Klicks wieder freigeben", SoundKit.enabled)
    }

    @Test
    fun `abgeschalteter app schalter bleibt aus auch wenn das system klingelt`() {
        SoundKit.setEnabled(false)
        assertFalse(SoundKit.enabled)

        SoundKit.setEnabled(true)
        assertTrue(SoundKit.enabled)
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
