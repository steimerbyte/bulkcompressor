package com.steimerbyte.bulkcompressor

import android.os.Looper
import com.steimerbyte.bulkcompressor.job.JobBus
import com.steimerbyte.bulkcompressor.ui.SoundKit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSoundPool

/**
 * Der Auftragston haengt an zwei Bedingungen: ein Lauf laeuft, und die
 * Oberflaeche ist sichtbar. Beide werden hier einzeln umgeschaltet - ein
 * Ton, der im Hintergrund weitersummt (oder nach dem Ende des Laufs),
 * waere schlimmer als gar keiner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class JobHumTest {

    @Before
    fun reset() {
        JobBus.reset()
        // SoundKit ist ein Objekt: ohne Freigeben traegt es Puffer, Rampen
        // und geladene Dateien aus dem vorherigen Test in diesen hinein.
        SoundKit.release()
        // enabled = true: der Test setzt den Schalter selbst, statt von
        // der Voreinstellung abzuhaengen.
        SoundKit.init(RuntimeEnvironment.getApplication(), enabled = true)
        // Die Vorgabe des Schattens fuer die Systemlautstaerke ist 0 - ohne
        // diesen Wert waere "kein Ton" aus dem falschen Grund richtig.
        val audio = requireNotNull(SoundKit.audioForTest())
        audio.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 7, 0)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Ohne Lade-Callback bleibt jeder Dauerton vorgemerkt statt zu laufen. */
    private fun markLoaded() {
        val pool = requireNotNull(SoundKit.poolForTest())
        val shadow = Shadow.extract<ShadowSoundPool>(pool)
        shadow.notifyResourceLoaded(R.raw.scan_hum, true)
        shadow.notifyResourceLoaded(R.raw.job_hum, true)
    }

    private fun controller() = Robolectric.buildActivity(MainActivity::class.java).setup()

    private fun running() = JobBus.State(running = true, total = 4, done = 1, overall = 0.25f)

    @Test
    fun `auftragston laeuft mit dem auftrag`() {
        controller()
        markLoaded()
        assertFalse(SoundKit.humPlayingForTest(SoundKit.Hum.JOB))

        JobBus.publish(running())
        idle()

        assertTrue(
            "Ein laufender Auftrag bei sichtbarer Oberflaeche muss summen",
            SoundKit.humPlayingForTest(SoundKit.Hum.JOB)
        )
        assertFalse(
            "Der Auftragston darf die Scan-Tonspur nicht mit anwerfen",
            SoundKit.humPlayingForTest(SoundKit.Hum.SCAN)
        )

        JobBus.publish(JobBus.State(finished = true, total = 4, done = 4))
        idle()

        assertFalse(
            "Nach dem Ende des Auftrags darf nichts weitersummen",
            SoundKit.humPlayingForTest(SoundKit.Hum.JOB)
        )
    }

    @Test
    fun `auftragston schweigt im hintergrund und kommt zurueck`() {
        val controller = controller()
        markLoaded()
        JobBus.publish(running())
        idle()
        assertTrue(SoundKit.humPlayingForTest(SoundKit.Hum.JOB))

        controller.pause().stop()
        idle()
        assertFalse(
            "Im Hintergrund waere der Ton ein Summen aus der Hosentasche",
            SoundKit.humPlayingForTest(SoundKit.Hum.JOB)
        )

        // Der Auftrag laeuft waehrenddessen weiter.
        JobBus.publish(running().copy(done = 3, overall = 0.75f))
        controller.start().resume()
        idle()

        assertTrue(
            "Beim Zurueckkommen muss der Ton wieder laufen - der Zustand wird neu gelesen",
            SoundKit.humPlayingForTest(SoundKit.Hum.JOB)
        )
    }

    @Test
    fun `abgebrochener auftrag beendet den ton`() {
        controller()
        markLoaded()
        JobBus.publish(running())
        idle()
        assertTrue(SoundKit.humPlayingForTest(SoundKit.Hum.JOB))

        JobBus.publish(JobBus.State(cancelled = true, total = 4, done = 2))
        idle()

        assertFalse(SoundKit.humPlayingForTest(SoundKit.Hum.JOB))
    }
}
