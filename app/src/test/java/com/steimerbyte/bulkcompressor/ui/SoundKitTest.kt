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

        // Lautstaerke: der Ton soll wesentlich lauter sein als die erste
        // Fassung (-29 dBFS). Untergrenze 0,10 haelt das fest, die
        // Obergrenze 0,14 haelt ihn unter den Klicks (0,11-0,12 im
        // Effektivwert, dort aber nur 50-180 ms lang) und laesst
        // Spielraum fuer spaetere Aenderungen nur nach oben, nicht ins
        // Clipping.
        val rms = all / 32767.0
        assertTrue("Effektivwert ${"%.3f".format(rms)} ist zu leise (Soll 0,11-0,13)", rms in 0.11..0.13)
        val peak = samples.maxOf { kotlin.math.abs(it.toInt()) } / 32767.0
        assertTrue(
            "Spitze $peak - zusammen mit dem Start-Klick (0,85) waere das ueber 1,0",
            peak < 0.45
        )
    }

    @Test
    fun `dauerton ist dumpf und nicht hell`() {
        val samples = pcm(R.raw.scan_hum)

        // Ein dumpfer Ton hat kaum Nulldurchgaenge: die Zahl haengt an der
        // Grundfrequenz, nicht an den Hoehen. Bei 73,5 Hz sind es 294 in
        // zwei Sekunden; Hoehen wuerden sie nach oben treiben, ein
        // 1320-Hz-Schimmer (die erste Fassung) auf ueber 5000. Der
        // Spielraum bis 450 laesst die 0,5-Hz-Schwebung zu, die den
        // Pegel periodisch kurz durch null fuehrt.
        val crossings = (1 until samples.size).count { samples[it - 1] < 0 != samples[it] < 0 }
        assertTrue(
            "$crossings Nulldurchgaenge in 2 s - das ist kein dumpfer Ton (Soll 250-450)",
            crossings in 250..450
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

        // Dumpf: die Nulldurchgaenge haengen an der Grundfrequenz, nicht an
        // Hoehen. Hohe Anteile wuerden sie nach oben treiben.
        fun crossings(samples: ShortArray) =
            (1 until samples.size).count { samples[it - 1] < 0 != samples[it] < 0 }
        val jobCrossings = crossings(job)
        assertTrue(
            "$jobCrossings Nulldurchgaenge - der Auftragston ist nicht dumpf (Soll 350-700)",
            jobCrossings in 350..700
        )

        // Unterscheidbar: eine Quarte hoeher heisst rund ein Drittel mehr
        // Nulldurchgaenge. Unter 60 waeren die beiden im Vorbeigehoeren
        // dasselbe Signal.
        val gap = kotlin.math.abs(jobCrossings - crossings(scan))
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

    @Test
    fun `beide toene teilen sich den pegel`() {
        markLoaded()
        SoundKit.startScanHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(1f, SoundKit.humVolumeForTest(SoundKit.Hum.SCAN), 0.001f)

        SoundKit.startJobHum()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
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
}
