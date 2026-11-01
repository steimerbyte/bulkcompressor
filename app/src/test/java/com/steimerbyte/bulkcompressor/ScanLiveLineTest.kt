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
import android.view.View
import android.widget.Button
import android.widget.TextView
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
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
            if (blocked.get() && Looper.myLooper() != Looper.getMainLooper()) gate.await()
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
            internal val gate = CountDownLatch(1)
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
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val live = activity.findViewById<TextView>(R.id.text_scan_live)
        val summary = activity.findViewById<TextView>(R.id.text_summary)

        // Nach dem Vorlauf-Scan steht die Zusammenfassung, nicht die Live-Zeile.
        assertEquals("Live-Zeile blieb nach dem Scan stehen", View.GONE, live.visibility)
        assertEquals(View.VISIBLE, summary.visibility)

        // Zweiter Lauf, angehalten: die Zeile muss sichtbar und noch leer sein.
        SlowProvider.blocked.set(true)
        activity.findViewById<Button>(R.id.button_rescan).performClick()
        assertEquals("Live-Zeile wird beim Start nicht gezeigt", View.VISIBLE, live.visibility)
        assertEquals("Live-Zeile startet mit Inhalt statt leer", "", live.text.toString())

        SlowProvider.gate.countDown()
        SlowProvider.blocked.set(false)

        val seen = ArrayList<String>()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            // Der Text bleibt auch nach dem Verstecken stehen - genau so
            // ist die letzte Zeile noch lesbar, wenn der Lauf vorbei ist.
            val text = live.text.toString()
            if (text.isNotEmpty() && seen.lastOrNull() != text) seen.add(text)
            if (live.visibility == View.GONE) break
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("Live-Zeile blieb leer: $seen", seen.isNotEmpty())
        assertTrue("Keine Zeile mit Dateizahl: $seen", seen.any { it.contains("Dateien") })
        // Jede Datei ist hier "Marker unlesbar" - an der Zahl erkennt der
        // Benutzer, dass Dateien fehlen, und zwar ohne die Datei-Zeile zu
        // lesen. Der Grund steht danach in der Zusammenfassung darunter
        // (siehe unten): die Live-Zeile ist fuer den Lauf zustaendig, die
        // Zusammenfassung fuer den Befund.
        assertTrue(
            "Kein Lesefehler-Zaehler in der Live-Zeile: $seen",
            seen.any { it.contains("Marker unlesbar") }
        )

        assertEquals("Live-Zeile blieb nach dem Scan stehen", View.GONE, live.visibility)
        assertEquals(View.VISIBLE, summary.visibility)
        // Der Grund gehoert in die Karte, nicht nur in die Logdatei: der
        // Anbieter konnte keine Datei oeffnen, und das ist genau die Frage,
        // die der Benutzer sonst per Kabel beantworten muesste.
        assertTrue("Grund fehlt in der Zusammenfassung: ${summary.text}", summary.text.contains("FileNotFoundException"))
    }
}
