package com.steimerbyte.bulkcompressor

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.time.Duration
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Die Live-Zeile im Ordner-Bereich: waehrend eines Scans steht dort der
 * Fortschritt, danach verschwindet die Zeile wieder.
 *
 * Der Anbieter oeffnet keine Datei - das ist der Fall "Marker nicht
 * lesbar", also die harte Probe: der Grund muss **in der Zeile** stehen und
 * nicht nur in einer Logdatei, die niemand ohne Kabel liest (genau das war
 * der Fehler in 1.9.2).
 *
 * Der Vorlauf-Scan beim Aufbau der Activity laeuft hier immer zu Ende -
 * Robolectric pumpt die Oberflaeche, bis der Hintergrund-Thread fertig ist.
 * Beobachtet wird deshalb der zweite Lauf: "Neu scannen" setzt die Zeile
 * synchron im Klick-Handler, und das Tor haelt den Scan an, bis der Test
 * diesen Zustand geprueft hat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScanLiveLineTest {

    /** Anbieter mit 40 Ordnern, je einer Datei, und einem Tor vor dem Lesen. */
    class SlowProvider : ContentProvider() {

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
        ): Cursor? {
            // Nur der Hintergrund-Scan wartet. Der Haupt-Thread liest
            // denselben Baum beim Aufbau der Oberflaeche; wuerde er hier
            // stehen, haenge der Start und der Test koennte das Tor nie
            // oeffnen.
            if (blocked.get() && Looper.myLooper() != Looper.getMainLooper()) gate.acquire()
            log.add("query ${Thread.currentThread().name}")
            val segments = uri.pathSegments
            val documentIndex = segments.indexOf("document")
            if (documentIndex < 0 || documentIndex + 1 >= segments.size) return null
            val documentId = segments[documentIndex + 1]
            val rows = children[documentId] ?: return null
            val columns = projection ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            val cursor = MatrixCursor(columns)
            for (row in rows) {
                cursor.addRow(
                    columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.first
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.second
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> row.third
                            DocumentsContract.Document.COLUMN_SIZE -> 2048L
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1_700_000_000_000L
                            else -> null
                        }
                    }
                )
            }
            return cursor
        }

        override fun getType(uri: Uri) = null
        override fun insert(uri: Uri, values: ContentValues?) = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?
        ) = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            throw FileNotFoundException("kein Deskriptor: $uri")
        }

        companion object {
            private const val AUTHORITY = "com.android.externalstorage.documents"

            internal val log: MutableList<String> = Collections.synchronizedList(ArrayList())
            /** Ein Durchlass je Abfrage: der Test treibt den Scan Schritt fuer Schritt. */
            internal val gate = Semaphore(0)
            internal val blocked = AtomicBoolean(false)
            internal var children: Map<String, List<Triple<String, String, String>>> = emptyMap()

            internal fun install(): ContentResolver {
                val root = "primary:DCIM"
                val map = LinkedHashMap<String, List<Triple<String, String, String>>>()
                map[root] = (1..40).map {
                    Triple("$root/F$it", "F$it", DocumentsContract.Document.MIME_TYPE_DIR)
                }
                for (i in 1..40) {
                    map["$root/F$i"] = listOf(Triple("$root/F$i/IMG_$i.jpg", "IMG_$i.jpg", "image/jpeg"))
                }
                children = map
                Robolectric.setupContentProvider(SlowProvider::class.java, AUTHORITY)
                return RuntimeEnvironment.getApplication().contentResolver
            }
        }
    }

    @Before
    fun freigegebenerOrdner() {
        SlowProvider.install()
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary:DCIM")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        RuntimeEnvironment.getApplication().contentResolver.takePersistableUriPermission(uri, flags)
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("bulk_compressor_settings", 0)
            .edit().putString("tree_uri", uri.toString()).apply()
    }

    @Test
    fun `live zeile zeigt fortschritt und verschwindet nach dem scan`() {
        // Der Anbieter haelt schon den ersten Zugriff an: der Scan steht,
        // bevor er ein Ergebnis haben kann. Damit ist jeder Schritt eine
        // Entscheidung des Tests, kein Zeitrennen.
        SlowProvider.blocked.set(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val state = activity.home()
        // Derselbe Pfad wie "Neu scannen"; der Start ueber die Composition
        // haengt an einem Frame und ist hier nicht der Pruefgegenstand.
        state.folder = activity.contentResolver.persistedUriPermissions
            .firstOrNull()?.uri
            ?: Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ATest")
        activity.startScanForTest()

        waitUntil(10_000) { state.scanning }
        assertTrue("Scan laeuft nicht", state.scanning)

        // Je Abfrage ein Durchlass: der Scan laeuft Schritt fuer Schritt,
        // und zwischen zwei Schritten steht das, was die Oberflaeche zeigt.
        val seen = ArrayList<String>()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            SlowProvider.gate.release()
            pump(20)
            val text = state.scanLive
            if (text.isNotEmpty() && seen.lastOrNull() != text) seen.add(text)
            if (!state.scanning && state.scan != null) break
            Thread.sleep(1)
        }
        SlowProvider.blocked.set(false)
        pump(20)

        assertTrue("Live-Zeile blieb leer: $seen", seen.isNotEmpty())
        assertTrue("Keine Zeile mit Dateizahl: $seen", seen.any { it.contains("Dateien") })
        // Jede Datei ist hier "Marker unlesbar" - an der Zahl erkennt der
        // Benutzer, dass Dateien fehlen, ohne die Datei-Zeile zu lesen.
        assertTrue(
            "Kein Lesefehler-Zaehler in der Live-Zeile: $seen",
            seen.any { it.contains("Marker unlesbar") }
        )

        // Nach dem Scan steht die Zeile nicht mehr: an ihrer Stelle steht
        // die Zusammenfassung in der Karte.
        assertEquals("Live-Zeile blieb nach dem Scan stehen", "", state.scanLive)
        assertTrue("Scan fehlt", state.scan != null)
        assertTrue(
            "Marker-Zaehler fehlt: ${state.scan?.markersUnreadable}",
            (state.scan?.markersUnreadable ?: 0) > 0
        )
        // Der Grund gehoert in die Karte, nicht nur in die Logdatei.
        assertTrue(
            "Grund fehlt: ${state.scan?.markerReason}",
            state.scan?.markerReason?.contains("FileNotFoundException") == true
        )
    }

    /**
     * Ein Bild weiterspulen. `idle()` allein reicht nicht: die
     * Neuzusammensetzung haengt an einem Frame, und Robolectric laesst die
     * Uhr nur mit `idleFor` laufen.
     */
    private fun pump(frameMs: Long = 20) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(frameMs))
    }

    /** Pumpt den Haupt-Looper, bis die Bedingung stimmt oder die Zeit um ist. */
    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            pump(20)
            Thread.sleep(2)
        }
    }
}
