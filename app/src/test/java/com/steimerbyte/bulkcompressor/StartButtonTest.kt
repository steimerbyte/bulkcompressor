package com.steimerbyte.bulkcompressor

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.widget.Button
import com.steimerbyte.bulkcompressor.job.CompressionService
import com.steimerbyte.bulkcompressor.job.JobBus
import com.steimerbyte.bulkcompressor.model.MediaItem
import com.steimerbyte.bulkcompressor.model.MediaKind
import com.steimerbyte.bulkcompressor.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Der Startknopf ist Start und Stopp in einem. Zwei Dinge muessen stimmen:
 * die Beschriftung folgt dem Zustand (nicht dem letzten Klick), und der
 * Knopf ist nach einem Lauf wieder bedienbar - auch wenn dessen Endmeldung
 * eintraf, waehrend die App im Hintergrund war.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StartButtonTest {

    private val tree: Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")

    @Before
    fun resetBus() {
        JobBus.reset()
    }

    private fun controller() = Robolectric.buildActivity(MainActivity::class.java).setup()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun item(name: String, kind: MediaKind) = MediaItem(
        uri = Uri.parse("content://x/document/primary%3ADCIM%2F$name"),
        docId = "primary:DCIM/$name",
        parentId = "primary:DCIM",
        displayName = name,
        mime = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
        sizeBytes = 1_000_000,
        lastModified = 1_700_000_000_000,
        kind = kind
    )

    private fun scan(vararg items: MediaItem) =
        ScanResult(items.toList(), folderCount = 1, truncated = false)

    private fun button(activity: MainActivity): Button = activity.findViewById(R.id.button_start)

    private fun startedService() =
        shadowOf(RuntimeEnvironment.getApplication() as Application).nextStartedService

    @Test
    fun `ohne ordner und ohne auswahl ist der knopf gesperrt und sagt warum`() {
        val activity = controller().get()
        idle()

        val start = button(activity)

        assertFalse("Ohne Auswahl darf der Knopf nicht bedienbar sein", start.isEnabled)
        assertEquals("Keine Auswahl", start.text.toString())
    }

    @Test
    fun `nach dem scan ist der knopf startbereit`() {
        val activity = controller().get()
        activity.applyScanForTest(tree, scan(item("a.jpg", MediaKind.IMAGE)))
        idle()

        val start = button(activity)

        assertTrue("Mit Auswahl muss der Knopf bedienbar sein", start.isEnabled)
        assertEquals("Start", start.text.toString())
    }

    @Test
    fun `waehrend eines laufs zeigt der knopf stopp und bricht ab`() {
        val activity = controller().get()
        activity.applyScanForTest(tree, scan(item("a.mp4", MediaKind.VIDEO)))
        JobBus.publish(JobBus.State(running = true, total = 4, done = 1, currentName = "a.mp4"))
        idle()

        val start = button(activity)
        assertEquals("Stopp", start.text.toString())
        assertTrue(start.isEnabled)

        start.performClick()
        idle()

        assertEquals("Bricht ab ...", start.text.toString())
        val intent = startedService()
        assertNotNull("Der Klick auf Stopp muss den Abbruch ausloesen", intent)
        assertEquals(CompressionService.ACTION_CANCEL, intent?.action)
    }

    @Test
    fun `nach einem abgeschlossenen lauf ist der knopf fuer einen neuen ordner bedienbar`() {
        val activity = controller().get()
        // Lauf 1 laeuft und endet mit Erfolg.
        activity.applyScanForTest(tree, scan(item("a.mp4", MediaKind.VIDEO)))
        JobBus.publish(JobBus.State(running = true, total = 1, done = 0))
        JobBus.publish(
            JobBus.State(
                running = false,
                finished = true,
                total = 1,
                done = 1,
                beforeBytes = 2_000_000,
                afterBytes = 500_000
            )
        )
        idle()

        // Neuer Ordner: frischer Scan, andere Dateien.
        activity.applyScanForTest(
            tree,
            scan(item("b.jpg", MediaKind.IMAGE), item("c.jpg", MediaKind.IMAGE))
        )
        idle()

        val start = button(activity)
        assertTrue(
            "Nach einem fertigen Lauf muss der Knopf wieder bedienbar sein",
            start.isEnabled
        )
        assertEquals("Start", start.text.toString())

        start.performClick()
        idle()

        val intent = startedService()
        assertNotNull("Der Klick muss einen Auftrag abgeben", intent)
        assertEquals(CompressionService.ACTION_START, intent?.action)
        assertEquals(
            2,
            intent?.getStringArrayListExtra(CompressionService.EXTRA_DOC_IDS)?.size
        )
    }

    @Test
    fun `ein klick zeigt sofort rueckmeldung statt eines toten knopfes`() {
        val activity = controller().get()
        activity.applyScanForTest(tree, scan(item("a.mp4", MediaKind.VIDEO)))
        idle()

        val start = button(activity)
        start.performClick()
        idle()

        // Der Dienst hat noch nicht geantwortet: der Knopf sagt das, statt
        // wie frueher still "Start" anzuzeigen und nicht mehr zu reagieren.
        assertEquals("Startet ...", start.text.toString())
        assertFalse(start.isEnabled)

        // Sobald der Dienst den Auftrag angenommen hat, wird gestoppt.
        JobBus.publish(JobBus.State(running = true, total = 1, done = 0))
        idle()
        assertEquals("Stopp", start.text.toString())
        assertTrue(start.isEnabled)
    }

    @Test
    fun `ein lauf der im hintergrund endet laesst den knopf nicht gesperrt`() {
        val lifecycle = controller()
        val activity = lifecycle.get()
        activity.applyScanForTest(tree, scan(item("a.mp4", MediaKind.VIDEO)))
        JobBus.publish(JobBus.State(running = true, total = 1, done = 0))
        idle()
        assertFalse("Waehrend des Laufs ist Start gesperrt", button(activity).text == "Start")

        // Die App ist im Hintergrund, als der Lauf endet - die Endmeldung
        // erreicht die Oberflaeche nicht.
        lifecycle.pause().stop()
        JobBus.publish(JobBus.State(running = false, finished = true, total = 1, done = 1))
        lifecycle.start().resume()
        idle()

        val start = button(activity)
        assertTrue(
            "Nach der Rueckkehr muss der Knopf den Zustand kennen, nicht den alten Klick",
            start.isEnabled
        )
        assertEquals("Start", start.text.toString())
        assertNull("Kein Auftrag ohne Klick", startedService())
    }
}
