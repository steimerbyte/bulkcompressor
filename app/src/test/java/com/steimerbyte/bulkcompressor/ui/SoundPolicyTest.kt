package com.steimerbyte.bulkcompressor.ui

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Entscheidung "klingelt es" ohne Geraet. Der Anlass war ein echter
 * Fehler: die App schwieg, weil sie zusaetzlich an der Systemeinstellung
 * "Beruehrungsklaenge" hing - auf vielen Geraeten ab Werk aus. Der eigene
 * Schalter stand sichtbar auf an, und niemand konnte sehen, warum kein
 * Ton kam.
 */
class SoundPolicyTest {

    private val normal = AudioManager.RINGER_MODE_NORMAL
    private val silent = AudioManager.RINGER_MODE_SILENT
    private val vibrate = AudioManager.RINGER_MODE_VIBRATE

    @Test
    fun `bei normalem Modus und Lautstaerke klingelt es`() {
        val decision = SoundPolicy.decide(appEnabled = true, ringerMode = normal, streamVolume = 5)

        assertTrue(decision.play)
        assertEquals(SoundPolicy.Reason.ALLOWED, decision.reason)
    }

    @Test
    fun `der app schalter schlaegt alles`() {
        val decision = SoundPolicy.decide(appEnabled = false, ringerMode = normal, streamVolume = 15)

        assertFalse(decision.play)
        assertEquals(SoundPolicy.Reason.APP_OFF, decision.reason)
    }

    @Test
    fun `lautlos und vibration unterdruecken den klick`() {
        assertEquals(
            SoundPolicy.Reason.SILENT_MODE,
            SoundPolicy.decide(true, silent, 5).reason
        )
        assertEquals(
            SoundPolicy.Reason.SILENT_MODE,
            SoundPolicy.decide(true, vibrate, 5).reason
        )
        assertFalse(SoundPolicy.decide(true, silent, 5).play)
    }

    @Test
    fun `ohne medienlautstaerke kein ton aber ein grund`() {
        val decision = SoundPolicy.decide(appEnabled = true, ringerMode = normal, streamVolume = 0)

        assertFalse(decision.play)
        assertEquals(SoundPolicy.Reason.NO_STREAM_VOLUME, decision.reason)
    }

    @Test
    fun `jeder grund hat einen lesbaren text`() {
        SoundPolicy.Reason.entries.forEach { reason ->
            assertTrue("Grund ohne Text: $reason", reason.text.isNotBlank())
        }
    }
}
