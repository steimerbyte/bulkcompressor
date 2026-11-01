package com.steimerbyte.bulkcompressor.ui

import android.content.Context
import android.media.AudioManager
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
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

    private fun playedResource(id: Int): Boolean {
        val pool = SoundKit.poolForTest() ?: return false
        return Shadow.extract<ShadowSoundPool>(pool).wasResourcePlayed(id)
    }

    private fun tickPlaybacks(): Int {
        val pool = SoundKit.poolForTest() ?: return 0
        return Shadow.extract<ShadowSoundPool>(pool).getResourcePlaybacks(R.raw.click_tick).size
    }

    /** Meldet dem Pool, dass die Dateien dekodiert sind - erst dann schleift der Ton. */
    private fun markLoaded() {
        val pool = requireNotNull(SoundKit.poolForTest())
        val shadow = Shadow.extract<ShadowSoundPool>(pool)
        allRawIds.forEach { shadow.notifyResourceLoaded(it, true) }
    }

    private fun humPlaybacks(id: Int = R.raw.scan_hum): List<ShadowSoundPool.Playback> {
        val pool = requireNotNull(SoundKit.poolForTest())
        return Shadow.extract<ShadowSoundPool>(pool).getResourcePlaybacks(id)
    }

    /** PCM-Proben einer WAV-Datei aus res/raw, ohne Fremdbibliothek. */
    private fun pcm(id: Int): ShortArray {
        val bytes = context.resources.openRawResource(id).use { it.readBytes() }
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunk = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = (bytes[offset + 4].toInt() and 0xFF) or
                ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            if (chunk == "data") {
                val count = size / 2
                return ShortArray(count) { index ->
                    val base = offset + 8 + 2 * index
                    (((bytes[base + 1].toInt() and 0xFF) shl 8) or
                        (bytes[base].toInt() and 0xFF)).toShort()
                }
            }
            offset += 8 + size + (size and 1)
        }
        throw AssertionError("Kein data-Chunk in Ressource $id")
    }

    private val allRawIds = listOf(
        R.raw.click_tick, R.raw.click_toggle, R.raw.click_thunk,
        R.raw.scan_start, R.raw.scan_done, R.raw.scan_hum, R.raw.job_hum
    )

    @Before
    fun setUp() {
        // SoundKit ist ein Objekt: ohne Zuruecksetzen traegt es Sperrzeiten
        // und geladene Puffer aus einem vorherigen Test in diesen hinein.
        SoundKit.release()
        SoundKit.init(context, enabled = true)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        // Fester Wert satt Maximum: die Vorgabe des Schattens ist 0, und
        // damit waere "kein Ton" aus dem falschen Grund richtig gewesen.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)
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
    fun `scan beginn und ende erreichen das audiosystem`() {
        assertNotNull("SoundPool muss aufgebaut sein", SoundKit.poolForTest())

        SoundKit.play(SoundKit.Click.SCAN_START)
        SoundKit.play(SoundKit.Click.SCAN_DONE)

        assertTrue(
            "Der Anlauf-Ton muss als scan_start an SoundPool gehen - eine " +
                "nicht geladene Datei faellt hier auf, nicht erst auf dem Geraet",
            playedResource(R.raw.scan_start)
        )
        assertTrue(
            "Der Abschluss-Ton muss als scan_done an SoundPool gehen",
            playedResource(R.raw.scan_done)
        )
    }

    @Test
    fun `scan toene folgen dem schalter der klickgeraeusche`() {
        // Ein zweiter Schalter fuer denselben Ton waere eine Falle: wer die
        // Klicks abschaltet, will auch den Scan nicht hoeren.
        SoundKit.setEnabled(false)

        SoundKit.play(SoundKit.Click.SCAN_START)

        assertFalse("Aus bleibt aus", playedResource(R.raw.scan_start))
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
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

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
    fun `dauerton laeuft als endlose schleife`() {
        markLoaded()

        SoundKit.startScanHum()

        val played = humPlaybacks()
        assertEquals("Der Dauerton muss genau einmal anlaufen", 1, played.size)
        assertEquals(
            "Der Dauerton muss schleifen (loop -1), sonst waere er nach 2 s wieder aus",
            -1,
            played.first().loop
        )
        assertTrue("Der Strom muss als laufend verbucht sein", SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))
    }

    @Test
    fun `dauerton startet nicht doppelt`() {
        markLoaded()

        SoundKit.startScanHum()
        SoundKit.startScanHum()

        assertEquals(
            "Ein zweiter Strom derselben Schleife waere doppelt so laut und liesse sich nicht sauber beenden",
            1,
            humPlaybacks().size
        )
    }

    @Test
    fun `dauerton endet mit dem stopp und laesst sich neu starten`() {
        markLoaded()
        SoundKit.startScanHum()

        SoundKit.stopScanHum()
        assertFalse("Nach dem Ende des Scans darf kein Strom laufen", SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))

        // Ausblendung auslaufen lassen: sie darf weder krachen noch den
        // Zustand verfaelschen.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertEquals("Die Ausblendung darf den Strom nicht neu starten", 1, humPlaybacks().size)
        assertFalse(SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))

        SoundKit.startScanHum()
        assertTrue("Ein neuer Scan muss den Ton wieder anlaufen lassen", SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))
        assertEquals(2, humPlaybacks().size)
    }

    @Test
    fun `dauerton blendet ein statt zu stossen`() {
        markLoaded()

        SoundKit.startScanHum()

        // Der Anlaufwert ist die einzige Lautstaerke, die der Test sehen
        // kann (der Shadow zeichnet nur den play-Aufruf auf) - und genau
        // darauf kommt es an: mit 0,32 Spitze und dem Start-Klick bei
        // 0,85 waere der erste Moment ueber der Begrenzung.
        assertEquals(
            "Der Ton muss bei 0 anlaufen - sonst liefe er mit dem Start-Klick in die Begrenzung",
            0f,
            humPlaybacks().single().leftVolume,
            0.001f
        )

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        val mid = SoundKit.humVolumeForTest(SoundKit.Hum.SCAN)
        assertTrue(
            "Nach 100 ms steht die Lautstaerke schon bei $mid - das ist ein Stoss, keine Einblendung",
            mid > 0f && mid < 0.75f
        )

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertEquals(
            "Nach der Einblendung muss die volle Lautstaerke stehen",
            1f,
            SoundKit.humVolumeForTest(SoundKit.Hum.SCAN),
            0.001f
        )
        assertTrue("Die Einblendung darf den Ton nicht beenden", SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))
    }

    @Test
    fun `abgebrochener scan blendet von der erreichten lautstaerke aus`() {
        markLoaded()
        SoundKit.startScanHum()
        // Nur der erste Rampenschritt ist gelaufen.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))

        SoundKit.stopScanHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))

        assertFalse(SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))
        assertEquals(
            "Nach der Ausblendung muss die Lautstaerke auf 0 stehen",
            0f,
            SoundKit.humVolumeForTest(SoundKit.Hum.SCAN),
            0.001f
        )

        // Und ein neuer Scan muss wieder sauber anlaufen (nicht bei der
        // alten, halben Lautstaerke stehenbleiben).
        SoundKit.startScanHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(1f, SoundKit.humVolumeForTest(SoundKit.Hum.SCAN), 0.001f)
    }

    @Test
    fun `dauerton folgt dem schalter der klickgeraeusche`() {
        markLoaded()
        SoundKit.setEnabled(false)

        SoundKit.startScanHum()

        assertTrue("Aus bleibt aus", humPlaybacks().isEmpty())
        assertFalse(SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))

        SoundKit.setEnabled(true)
        SoundKit.startScanHum()
        assertEquals("An muss der Dauerton wieder laufen", 1, humPlaybacks().size)
    }

    @Test
    fun `abschalten beendet einen laufenden dauerton`() {
        markLoaded()
        SoundKit.startScanHum()
        assertTrue(SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))

        SoundKit.setEnabled(false)

        assertFalse(
            "Wer den Ton mitten im Scan abschaltet, will ihn sofort nicht mehr hoeren",
            SoundKit.humPlayingForTest(SoundKit.Hum.SCAN)
        )
    }

    @Test
    fun `lautloser modus unterdrueckt den dauerton`() {
        markLoaded()
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT

        SoundKit.startScanHum()

        assertTrue("Im Lautlos-Modus darf nicht gesummt werden", humPlaybacks().isEmpty())
    }

    @Test
    fun `dauerton wartet auf die dekodierung`() {
        // Ohne markLoaded(): genau der Fall "Scan beginnt kurz nach dem
        // App-Start". Der Ton darf nicht verloren gehen.
        SoundKit.startScanHum()

        assertTrue("Der Wunsch muss vorgemerkt sein", SoundKit.humWantedForTest(SoundKit.Hum.SCAN))
        assertTrue("Ohne dekodierte Datei darf nichts anlaufen", humPlaybacks().isEmpty())

        markLoaded()

        assertTrue("Nach dem Laden muss die Schleife anlaufen", SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))
        assertEquals(1, humPlaybacks().size)
    }

    @Test
    fun `dauerton ist eine nahtlose schleife`() {
        val samples = pcm(R.raw.scan_hum)

        assertEquals("Der Dauerton muss 2 s lang sein", 88200, samples.size)

        val seam = kotlin.math.abs(samples[0] - samples[samples.size - 1])
        var maxStep = 0
        for (i in 0 until samples.size - 1) {
            maxStep = maxOf(maxStep, kotlin.math.abs(samples[i + 1] - samples[i]))
        }
        assertTrue(
            "Die Naht ($seam) ist groesser als der groesste Sprung im Ton ($maxStep) - " +
                "die Schleife klickt dann hoerbar",
            seam <= maxStep
        )

        // Kein Ein- und Ausblenden an den Enden: eine Blende wuerde die
        // Naht zerstueckeln. Geprueft wird die untere Grenze - der Ton
        // atmet (Huellkurve 0,5 Hz), ist in der Mitte also leiser als an
        // den Enden, aber nie leise.
        val all = Math.sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        fun ratioOf(block: List<Short>): Double =
            Math.sqrt(block.sumOf { it.toDouble() * it } / block.size) / all
        val head = ratioOf(samples.take(1000))
        val tail = ratioOf(samples.takeLast(1000))
        assertTrue(
            "Am Anfang sind nur ${"%.2f".format(head)} des Gesamtpegels - das ist eine Einblendung",
            head > 0.5
        )
        assertTrue(
            "Am Ende sind nur ${"%.2f".format(tail)} des Gesamtpegels - das ist eine Ausblendung",
            tail > 0.5
        )

        // Lautstaerke: normiert wird auf die **Spitze 0,78** (−2,2 dBFS),
        // nicht mehr auf den Effektivwert von 0,12. Der Effektivwert folgt
        // daraus (rund 0,26). Ueber Vollaussteuerung geht es nur
        // zusammen mit einem Klick - dafuer senkt SoundKit den Dauerton
        // fuer die Dauer des Klicks ab (siehe `klick senkt den dauerton ab`).
        val rms = all / 32767.0
        assertTrue("Effektivwert ${"%.3f".format(rms)} ist zu leise (Soll 0,24-0,29)", rms in 0.24..0.29)
        val peak = samples.maxOf { kotlin.math.abs(it.toInt()) } / 32767.0
        assertTrue(
            "Spitze $peak - ueber 0,80 bleibt zu wenig Luft zur Vollaussteuerung",
            peak in 0.74..0.80
        )
    }

    /**
     * "Dumpf" heisst: **keine Hoehen**, nicht "nur Tiefbass". Seit 1.9.0
     * liegt der Schwerpunkt bei rund 300 Hz, damit ein Telefonlautsprecher
     * ueberhaupt etwas abstrahlt - ein Ton, dessen Energie bei 73 Hz liegt,
     * ist auf so einer Membran nicht zu hoeren.
     *
     * Gemessen wird ueber die Rauheit (Energie der ersten Differenz im
     * Verhaeltnis zur Gesamtenergie). Fuer einen reinen Sinus gilt
     * r = 2·sin(pi·f/fs); ist der hoechste Anteil f_max, dann ist
     * r <= 2·sin(pi·f_max/fs). Bei 48 kHz sind das fuer 800 Hz 0,114 und
     * fuer 2 kHz 0,260. Der Grenzwert 0,10 schliesst damit alles ueber
     * etwa 700 Hz aus - ein Schimmer bei 2 kHz (die erste Fassung) faellt
     * hier durch, und ein Nulldurchgangszaehler wuerde ihn ebenfalls
     * sehen, aber die 300-Hz-Fassung faelschlich verwerfen.
     */
    @Test
    fun `dauerton ist dumpf und nicht hell`() {
        val samples = pcm(R.raw.scan_hum)

        val roughness = roughness(samples)
        assertTrue(
            "Rauheit ${"%.4f".format(roughness)} - der Ton hat Anteile ueber etwa 700 Hz (Grenze 0,10)",
            roughness < 0.10
        )

        // Und der Grund fuer die Umstellung: der Schwerpunkt muss in dem
        // Band liegen, das ein Handylautsprecher abstrahlt (250-650 Hz).
        val speechShare = bandShare(samples, centerHz = 400.0, q = 0.7)
        assertTrue(
            "Nur ${"%.0f".format(speechShare * 100)} % der Energie liegen bei 400 Hz - " +
                "auf einem Telefonlautsprecher bleibt davon kaum etwas hoerbar",
            speechShare > 0.35
        )
    }

    @Test
    fun `auftragston laeuft als endlose schleife`() {
        markLoaded()

        SoundKit.startJobHum()

        val played = humPlaybacks(R.raw.job_hum)
        assertEquals("Der Auftragston muss einmal anlaufen", 1, played.size)
        assertEquals(-1, played.first().loop)
        assertTrue(SoundKit.humPlayingForTest(SoundKit.Hum.JOB))
        assertFalse(
            "Der Auftragston darf die Scan-Tonspur nicht mit anwerfen",
            SoundKit.humPlayingForTest(SoundKit.Hum.SCAN)
        )
    }

    @Test
    fun `auftragston endet mit dem auftrag`() {
        markLoaded()
        SoundKit.startJobHum()

        SoundKit.stopJobHum()

        assertFalse(SoundKit.humPlayingForTest(SoundKit.Hum.JOB))
    }

    @Test
    fun `auftragston ist dumpf aber von der scan-tonspur unterscheidbar`() {
        val job = pcm(R.raw.job_hum)
        val scan = pcm(R.raw.scan_hum)

        assertEquals("Beide Dauertoene sind 2 s lang", scan.size, job.size)

        // Dumpf wie der Scan-Ton: keine Anteile ueber etwa 700 Hz.
        assertTrue(
            "Rauheit ${"%.4f".format(roughness(job))} - der Auftragston ist nicht dumpf",
            roughness(job) < 0.10
        )
        assertTrue(
            "Nur ${"%.0f".format(bandShare(job, 500.0, 0.7) * 100)} % der Energie bei 500 Hz - " +
                "auf einem Telefonlautsprecher kaum hoerbar",
            bandShare(job, 500.0, 0.7) > 0.35
        )

        // Unterscheidbar: der Auftragston liegt eine Quarte hoeher, seine
        // tragenden Anteile sind 392 und 588 statt 294 und 441 Hz. Die
        // Nulldurchgaenge folgen dem hoechsten starken Anteil; 60
        // Unterschied waeren im Vorbeigehoeren dasselbe Signal.
        fun crossings(samples: ShortArray) =
            (1 until samples.size).count { samples[it - 1] < 0 != samples[it] < 0 }
        val gap = kotlin.math.abs(crossings(job) - crossings(scan))
        assertTrue(
            "Nur $gap Nulldurchgaenge Unterschied - die beiden Toene waeren nicht zu unterscheiden",
            gap >= 60
        )

        // Gleiche Lautstaerke: ein Zustandssignal darf nicht danach
        // klingen, wie wichtig er ist.
        fun rms(samples: ShortArray): Double =
            Math.sqrt(samples.sumOf { it.toDouble() * it } / samples.size) / 32767.0
        assertEquals(
            "Beide Dauertoene muessen gleich laut sein",
            rms(scan),
            rms(job),
            0.006
        )
    }

    /**
     * **Der Fehler, der niemandem auffallen wuerde, bis das Telefon in der
     * Hosentasche summt.** `stopHum` blendet ueber 160 ms aus und haelt
     * erst im letzten Schritt an. Wird in diesem Fenster ein zweites Mal
     * gestoppt, sieht der zweite Aufruf "kein Strom mehr" - und wenn er
     * dabei die laufende Rampe abloest, faellt der Schritt, der anhaelt,
     * mit weg. Die Endlosschleife (loop = -1) liefe dann fuer immer
     * weiter. Ausgeloest wird das im Normalbetrieb: `onStop` und
     * `onDestroy` der Activity rufen beide `stopJobHum()`.
     */
    @Test
    fun `zweiter stopp laesst die ausblendung laufen`() {
        markLoaded()
        SoundKit.startScanHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        // Der Zaehler gehoert dem Objekt und laeuft ueber die Tests hinweg -
        // verglichen wird deshalb der Zuwachs.
        val before = SoundKit.stopCountForTest()

        SoundKit.stopScanHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))
        SoundKit.stopScanHum()   // der zweite Aufruf, mitten in der Ausblendung

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(
            "Der Strom wurde nie angehalten - die Endlosschleife laeuft im Hintergrund weiter",
            before + 1,
            SoundKit.stopCountForTest()
        )
        assertFalse(SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))
        assertEquals(1, humPlaybacks().size)
    }

    /**
     * Verlaesst man die App und kommt sofort zurueck, laeuft `onStop` vor
     * `onResume` - der Neustart faellt in die Ausblendung. Wird der alte
     * Strom dabei nicht angehalten, laufen zwei Schleifen derselben Datei
     * nebeneinander, und die Pegelteilung zaehlt nur eine.
     */
    @Test
    fun `neustart waehrend der ausblendung stoppt den alten strom`() {
        markLoaded()
        SoundKit.startScanHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        val before = SoundKit.stopCountForTest()

        SoundKit.stopScanHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))
        SoundKit.startScanHum()

        assertEquals(
            "Der alte Strom muss angehalten werden",
            before + 1,
            SoundKit.stopCountForTest()
        )
        assertEquals(2, humPlaybacks().size)
        assertTrue("Der neue Strom muss laufen", SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(
            "Ein einzelner Ton muss den ganzen Pegel haben - zwei Stroeme zaehlten als zwei",
            1f,
            SoundKit.humVolumeForTest(SoundKit.Hum.SCAN),
            0.001f
        )
        assertEquals(
            "Der alte Strom darf nicht nachtraeglich noch angehalten werden",
            before + 1,
            SoundKit.stopCountForTest()
        )
    }

    /**
     * Zwei Dauertoene plus Klick: 0,78·(0,6·2·x) + 0,85 muss unter 1,0
     * bleiben. Deshalb teilen sich beide das Klick-Budget.
     */
    @Test
    fun `klick absenkung teilt ihr budget auf beide toene`() {
        markLoaded()
        SoundKit.startScanHum()
        SoundKit.startJobHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(0.6f, SoundKit.humVolumeForTest(SoundKit.Hum.SCAN), 0.001f)

        SoundKit.play(SoundKit.Click.TICK)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))
        val scan = SoundKit.humVolumeForTest(SoundKit.Hum.SCAN)
        val job = SoundKit.humVolumeForTest(SoundKit.Hum.JOB)
        val peak = 0.78f * (scan + job) + 0.85f
        assertTrue(
            "Summenspitze ${"%.3f".format(peak)} beim Klick - das clippt (Soll <= 1,0)",
            peak <= 1.0f
        )
        assertTrue("Beide Toene muessen abgesenkt sein", scan > 0f && job > 0f)
    }

    /**
     * Der Dauerton ist auf Spitze 0,78 normiert, der Klick auf 0,85 - beide
     * gleichzeitig waeren 1,63 und damit weit ueber der Vollaussteuerung.
     * Fuer die Dauer des Klicks geht der Dauerton deshalb auf 0,22 zurueck
     * (das sind 0,17 Spitze) und danach wieder hoch.
     */
    @Test
    fun `klick senkt den dauerton ab`() {
        markLoaded()
        SoundKit.startScanHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(1f, SoundKit.humVolumeForTest(SoundKit.Hum.SCAN), 0.001f)

        SoundKit.play(SoundKit.Click.TICK)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))
        val ducked = SoundKit.humVolumeForTest(SoundKit.Hum.SCAN)
        assertTrue(
            "Der Dauerton steht beim Klick noch bei $ducked - 0,78 plus 0,85 clippt",
            ducked > 0f && ducked <= 0.26f
        )

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertEquals(
            "Nach dem Klick muss der Dauerton wieder voll da sein",
            1f,
            SoundKit.humVolumeForTest(SoundKit.Hum.SCAN),
            0.001f
        )
        assertTrue("Die Absenkung darf den Ton nicht beenden", SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))
    }

    @Test
    fun `beide toene teilen sich den pegel`() {
        markLoaded()
        SoundKit.startScanHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(1f, SoundKit.humVolumeForTest(SoundKit.Hum.SCAN), 0.001f)

        SoundKit.startJobHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        // Zwei Toene mit Spitze 0,78 waeren zusammen 1,56 - bei 0,6 sind es
        // 0,94 und damit unter der Vollaussteuerung. Und es muss fuer beide
        // gelten, nicht nur fuer den ersten.
        assertEquals(0.6f, SoundKit.humVolumeForTest(SoundKit.Hum.SCAN), 0.001f)
        assertEquals(0.6f, SoundKit.humVolumeForTest(SoundKit.Hum.JOB), 0.001f)
        val both = SoundKit.humVolumeForTest(SoundKit.Hum.SCAN)
        assertTrue(
            "Beide Toene stehen bei $both - zusammen waeren sie lauter als einer plus Klick",
            both in 0.6f..0.75f
        )
        assertEquals(
            "Der neue Ton muss denselben geteilten Pegel haben",
            both,
            SoundKit.humVolumeForTest(SoundKit.Hum.JOB),
            0.001f
        )

        // Ende des Auftrags: der Scan laeuft allein und bekommt den Pegel zurueck.
        SoundKit.stopJobHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(
            "Der verbliebene Ton muss wieder volle Lautstaerke haben",
            1f,
            SoundKit.humVolumeForTest(SoundKit.Hum.SCAN),
            0.001f
        )
        assertTrue(SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))
        assertFalse(SoundKit.humPlayingForTest(SoundKit.Hum.JOB))
    }

    @Test
    fun `abschalten beendet beide dauertoene`() {
        markLoaded()
        SoundKit.startScanHum()
        SoundKit.startJobHum()

        SoundKit.setEnabled(false)

        assertFalse(SoundKit.humPlayingForTest(SoundKit.Hum.SCAN))
        assertFalse(SoundKit.humPlayingForTest(SoundKit.Hum.JOB))
    }

    @Test
    fun `lautloser modus unterdrueckt auch den auftragston`() {
        markLoaded()
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT

        SoundKit.startJobHum()

        assertTrue(humPlaybacks(R.raw.job_hum).isEmpty())
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

    /**
     * Rauheit: Effektivwert der ersten Differenz im Verhaeltnis zum
     * Effektivwert des Signals. Fuer einen reinen Sinus der Frequenz f ist
     * das 2·sin(pi·f/fs) - ein direkter, filterfreier Schrankenwert fuer
     * den hoechsten enthaltenen Anteil.
     */
    private fun roughness(samples: ShortArray): Double {
        val total = Math.sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        var diff = 0.0
        for (i in 1 until samples.size) {
            val d = (samples[i].toDouble() - samples[i - 1].toDouble())
            diff += d * d
        }
        return Math.sqrt(diff / (samples.size - 1)) / total
    }

    /**
     * Anteil der Energie in einem Bandpass um [centerHz] (RBJ-Biquad,
     * Guete [q]) an der Gesamtenergie. Damit laesst sich pruefen, ob der
     * Ton ueberhaupt in dem Bereich liegt, den ein Handylautsprecher
     * abstrahlt - ohne FFT, ohne externe Bibliothek.
     */
    private fun bandShare(samples: ShortArray, centerHz: Double, q: Double): Double {
        val sampleRate = 48000.0
        val w = 2 * Math.PI * centerHz / sampleRate
        val alpha = Math.sin(w) / (2 * q)
        val cos = Math.cos(w)
        val a0 = 1 + alpha
        val b0 = alpha / a0
        val b2 = -alpha / a0
        val a1 = -2 * cos / a0
        val a2 = (1 - alpha) / a0
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        var outEnergy = 0.0
        var totalEnergy = 0.0
        for (s in samples) {
            val x = s / 32768.0
            val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            outEnergy += y * y
            totalEnergy += x * x
        }
        return Math.sqrt(outEnergy / totalEnergy)
    }

}
