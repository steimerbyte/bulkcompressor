package com.steimerbyte.bulkcompressor

import android.app.Application
import com.steimerbyte.bulkcompressor.engine.MemoryGuard
import com.steimerbyte.bulkcompressor.log.CrashGuard
import com.steimerbyte.bulkcompressor.log.Log

/**
 * Prozessweiter Einstiegspunkt.
 *
 * Hier und nur hier werden Log und Absturzschutz installiert: der Handler
 * muss vor der ersten Activity stehen, sonst fehlt der Bericht fuer Fehler
 * beim Start. Beide Aufrufe sind idempotent, ein zweiter Aufruf aus einem
 * Dienst oder Test stoert also nicht.
 */
class BulkCompressorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.init(this)
        CrashGuard.install(this)
        // Der Speicherwaechter haengt am Prozess, damit er auch waehrend
        // des Hintergrundlaufs Meldungen des Systems bekommt.
        MemoryGuard.attach(this)
        Log.i("App", "Prozess gestartet")
    }
}
