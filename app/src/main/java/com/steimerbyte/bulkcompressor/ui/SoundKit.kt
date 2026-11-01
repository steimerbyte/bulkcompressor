package com.steimerbyte.bulkcompressor.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.steimerbyte.bulkcompressor.R
import com.steimerbyte.bulkcompressor.log.Log

/**
 * Klickgeraeusche fuer Beruehrungen.
 *
 * Die App liefert fuenf kurze, synthetisch erzeugte Holz-Klicks mit
 * (`tools/generate_click_sounds.py`): einen hellen fuer Chips und kleine
 * Knoepfe, einen tieferen fuer Schalter, einen groesseren fuer den
 * Startknopf, einen Zweifach-Schlag mit steigender Tonhoehe fuer den
 * Beginn eines Ordner-Scans und einen tiefen Einzelschlag fuer dessen
 * Ende. Dazu ein **Dauerton**, der so lange laeuft wie der Scan selbst:
 * ein dumpfes Summen (Grundton D2, nichts ueber 300 Hz), das beginnt,
 * wenn der Ordner gelesen wird, und endet, wenn er gelesen ist. Die Klicks markieren Anfang und Ende, das
 * Summen sagt "es passiert gerade etwas" - man hoert es auch, wenn man
 * das Telefon in der Hand haelt und nicht hinsieht.
 *
 * [SoundPool] statt [android.media.MediaPlayer]: die Dateien sind beim
 * ersten Tippen schon dekodiert, es gibt keine Startlatenz und kein
 * Nachladen von der Platte. Mehr als vier gleichzeitige Straenge sind
 * nicht sinnvoll -- mehr Klicks pro Sekunde gibt es nicht.
 *
 * Ton an oder aus entscheidet [SoundPolicy]: der Schalter in der App und
 * die Lautlos-Regel des Systems. Die Systemeinstellung fuer
 * Beruehrungsklaenge schaltet den Ton **nicht** mehr ab - sie steht nur
 * im Protokoll (Begruendung dort).
 *
 * Jede Entscheidung wird protokolliert, aber nur beim Wechsel des
 * Grundes: ein Dauerfeuer waere im Terminal unlesbar, und "warum kein
 * Ton" ist genau die Frage, die man nach einem verdächtigen Tippen hat.
 */
object SoundKit {

    /**
     * Was geklickt hat. Bestimmt die Datei und den Mindestabstand.
     *
     * `SCAN_START` und `SCAN_DONE` gehoeren zur selben Familie wie die
     * Klicks: derselbe Holz-Anschlag, nur als Zweifach-Schlag mit
     * steigender Tonhoehe ("jetzt geht es los") und als einzelner tiefer
     * Schlag ("Ordner gelesen"). Kein Piep - ein Zustand, den man hoert,
     * ohne hinzusehen.
     */
    enum class Click { TICK, TOGGLE, THUNK, SCAN_START, SCAN_DONE }

    private const val MAX_STREAMS = 4

    /**
     * Die Klickdateien sind auf -1 dBFS normalisiert; jede Reduktion hier
     * waere nur leiser ohne Zweck. Die Hoer-Lautstaerke kommt vom
     * Systemton-Regler, nicht von diesem Faktor.
     */
    private const val VOLUME = 1.0f

    private const val TAG = "Sound"

    /**
     * Zwei Klicks derselben Sorte dichter als das hier werden verschluckt.
     * Ein Slider meldet beim Ziehen viele Schritte pro Sekunde; ohne
     * Bremse waere das ein Knattern, und nichts davon waere noch als
     * Klick zu erkennen.
     */
    private const val MIN_GAP_MS = 40L

    /**
     * Der Dauerton ist auf **Spitze 0,78** normiert (-2,2 dBFS), der
     * Klick auf 0,85. Die Lautstaerke kommt vom Medien-Regler: gespielt
     * wird auf `STREAM_MUSIC` (siehe [ensurePool]), also von dem Regler,
     * den die Seitentasten bewegen und den man mit "volle Lautstaerke"
     * meint.
     */
    private const val HUM_VOLUME = 1.0f

    /**
     * Einblendzeit: sechs Schritte zu 40 ms (der erste sofort, also
     * insgesamt 200 ms). Der Spitzenwert 0,78 erreicht der Ton erst am
     * Ende - der Start-Klick (0,85) findet ihn im Anlauf vor und nicht
     * schon voll da. Six steps machen aus dem Anlauf ausserdem einen
     * dumpfen Ansatz statt eines Stosses.
     */
    private const val HUM_FADE_IN_STEPS = 6

    /**
     * Ausblendzeit beim Ende des Scans. Ein tiefer Ton, der mitten in der
     * Schwingung abgeschnitten wird, knallt; 160 ms (vier Schritte, der
     * erste sofort) reichen, um das zu vermeiden, ohne das Ende zu
     * verschleppen. Der letzte Schritt schreibt **0**, bevor
     * [SoundPool.stop] kommt - ohne ihn wuerde der Strom bei 0,2 der
     * Lautstaerke abgeschnitten (ein Sprung von -16 dBFS).
     */
    private const val HUM_FADE_MS = 40L
    private const val HUM_FADE_STEPS = 5

    /**
     * Wenn beide Dauertoene gleichzeitig laufen (ein Scan waehrend eines
     * Auftrags), bekommt jeder diesen Anteil.
     *
     * Zwei Toene mit Spitze 0,78 waeren zusammen 1,56; bei 0,6 sind es
     * 0,94 und damit unter der Vollaussteuerung. Hoeher geht nicht, ohne
     * dass zwei gleichzeitige Toene clippen.
     */
    private const val HUM_DUCK = 0.6f

    /**
     * Waehrend ein Klick erklingt, laeuft der Dauerton in diesem Anteil.
     *
     * Der Klick hat Spitze 0,85, ein Dauerton 0,78 - gleichzeitig waeren
     * das 1,63 und damit weit ueber der Vollaussteuerung. Fuer die Dauer
     * des Klicks (hoechstens 180 ms) geht der Dauerton deshalb zurueck
     * und danach wieder hoch; das ist derselbe Trick wie eine
     * Sidechain-Kompression und faellt nur auf, wenn man darauf achtet.
     *
     * 0,19 ist die Obergrenze, nicht ein Gefuehlswert: 0,85 + 0,78·0,19
     * = 0,998. Laufen zwei Dauertoene, teilen sie sich dieses Budget.
     */
    private const val HUM_CLICK_DUCK = 0.19f
    private const val HUM_CLICK_DUCK_MS = 200L


    private var pool: SoundPool? = null
    private val streamIds = HashMap<Click, Int>()
    private val lastPlayedAt = HashMap<Click, Long>()

    private var appEnabled = true
    private var audio: AudioManager? = null

    /** Zuletzt protokollierter Grund; nur Aenderungen werden geschrieben. */
    private var lastReason: SoundPolicy.Reason? = null

    /** Noch nicht dekodierte Klicks: sie werden nach dem Laden nachgeholt. */
    /**
     * Wie oft ein Strom angehalten wurde. Nur fuer Tests: dass eine
     * Endlosschleife, die einmal lief, auch wieder angehalten wird, ist
     * die wichtigste Zusage dieses Objekts - und der Shadow von
     * `SoundPool` zeichnet `stop()` nicht auf.
     */
    private var stops = 0

    /** Haelt [stream] an und zaehlt das mit. */
    private fun stopStream(stream: Int) {
        if (stream == 0) return
        stops++
        runCatching { pool?.stop(stream) }
    }

    private val loaded = HashSet<Int>()
    private val pending = HashMap<Click, Int>()

    /**
     * Zwei Dauertoene, gleiche Bauart, unterschiedliche Lage: [SCAN]
     * laeuft, solange ein Ordner gelesen wird (Grundton D2), [JOB],
     * solange ein Auftrag laeuft (Grundton G2, eine Quarte hoeher).
     */
    enum class Hum { SCAN, JOB }

    /** Zustand eines Dauertons - jeder hat Strom, Lautstaerke und Rampe. */
    private class HumChannel {
        var resId = 0
        var stream = 0
        var volume = 0f
        var wanted = false
        var ramp: Runnable? = null

        /**
         * Der Strom laeuft noch, wird aber gerade ausgeblendet.
         *
         * [stream] bleibt bis zum letzten Schritt gesetzt - genau deshalb:
         * wuerde er sofort auf 0 gehen, koennte ein zweiter Stopp die
         * laufende Ausblendung als "nichts zu tun" abloesen, und die
         * Endlosschleife liefe danach fuer immer weiter. So ist [stream]
         * die Wahrheit ueber den Strom, [stopping] die Absicht.
         */
        var stopping = false
    }

    private val humChannels: Map<Hum, HumChannel> =
        Hum.values().associateWith { HumChannel() }

    /** Rampen laufen ueber den Main-Looper; ein Handler fuer alle Kanaele. */
    private var fadeHandler: Handler? = null

    /** Steht aus, solange ein Klick ueber dem abgesenkten Dauerton liegt. */
    private var clickDuckRestore: Runnable? = null

    /**
     * Einmal nach dem Aufbau der Oberflaeche aufrufen. Der zweite Aufruf
     * mit denselben Argumenten ist folgenlos -- nur ein neuer Prozess
     * traegt einen eigenen SoundPool.
     */
    fun init(context: Context, enabled: Boolean) {
        appEnabled = enabled
        if (pool != null) return
        audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // **Medien-Stream, nicht Systemton.** USAGE_ASSISTANCE_SONIFICATION
        // landet auf STREAM_SYSTEM; diese Lautstaerke regeln die
        // Seitentasten nicht, und sie steht auf vielen Geraeten niedrig
        // (getrennt von der "vollen Lautstaerke", die man einstellt). Ein
        // Dauerton, der eine Minute laeuft, ist ausserdem eher Inhalt als
        // Systemrueckmeldung. Mit CONTENT_TYPE_SONIFICATION bleibt der
        // Charakter "Rueckmeldung", die Lautstaerke kommt von der
        // Medienlautstaerke - also von dem Regler, den man tatsaechlich in
        // der Hand hat.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val created = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(attributes)
            .build()
        created.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) {
                Log.w(TAG, "Klickdatei $sampleId liess sich nicht dekodieren (Status $status)")
                return@setOnLoadCompleteListener
            }
            loaded.add(sampleId)
            // Scan oder Auftrag koennen begonnen haben, bevor der
            // Dauerton dekodiert war (App-Start mit wiederhergestelltem
            // Ordner). Dann laeuft er jetzt an - sonst bliebe der
            // Vorgang tonlos.
            humChannels.forEach { (kind, channel) ->
                if (channel.wanted && sampleId == channel.resId) {
                    channel.wanted = false
                    playHum(kind)
                }
            }
            // Der erste Tipp nach dem Start kam womoeglich, bevor die Datei
            // dekodiert war - er wird jetzt nachgeholt.
            val waiting = pending.entries.firstOrNull { streamIds[it.key] == sampleId }
            if (waiting != null) {
                pending.remove(waiting.key)
                pool?.play(sampleId, VOLUME, VOLUME, 1, 0, 1f)
            }
        }
        streamIds[Click.TICK] = created.load(context, R.raw.click_tick, 1)
        streamIds[Click.TOGGLE] = created.load(context, R.raw.click_toggle, 1)
        streamIds[Click.THUNK] = created.load(context, R.raw.click_thunk, 1)
        streamIds[Click.SCAN_START] = created.load(context, R.raw.scan_start, 1)
        streamIds[Click.SCAN_DONE] = created.load(context, R.raw.scan_done, 1)
        humChannels.getValue(Hum.SCAN).resId = created.load(context, R.raw.scan_hum, 1)
        humChannels.getValue(Hum.JOB).resId = created.load(context, R.raw.job_hum, 1)
        pool = created
        Log.i(TAG, describe(context))
    }

    fun setEnabled(enabled: Boolean) {
        appEnabled = enabled
        lastReason = null
        // Wer den Ton mitten im Scan abschaltet, will ihn sofort nicht
        // mehr hoeren - nicht erst beim naechsten Vorgang.
        if (!enabled) {
            stopScanHum()
            stopJobHum()
        }
    }

    /**
     * Nach der Rueckkehr in den Vordergrund: Lautlos-Modus und
     * Systemlautstaerke koennen sich waehrend der Laufzeit geaendert
     * haben. Der Wert wird erst beim naechsten Klick gelesen; hier wird
     * nur der Protokollstand zurueckgesetzt, damit die naechste
     * Entscheidung wieder begruendet wird.
     */
    fun refresh(context: Context) {
        audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        lastReason = null
    }

    /**
     * Darf jetzt Ton kommen? Ein Ort fuer die Regel, damit Klick und
     * Dauerton nicht auseinanderlaufen.
     */
    private fun mayPlay(): Boolean {
        val manager = audio
        val decision = SoundPolicy.decide(
            appEnabled = appEnabled,
            ringerMode = manager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL,
            streamVolume = runCatching {
                manager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1
            }.getOrDefault(1)
        )
        if (!decision.play) {
            logReasonOnce(decision.reason)
            return false
        }
        logReasonOnce(SoundPolicy.Reason.ALLOWED)
        return true
    }

    fun play(click: Click) {
        // Die Bremse zuerst: sonst kostet jeder einzelne Reglerschritt eine
        // Abfrage beim AudioService (Binder), obwohl danach neun von zehn
        // Aufrufen ohnehin verworfen werden.
        val now = SystemClock.uptimeMillis()
        lastPlayedAt[click]?.let { last ->
            if (now - last < MIN_GAP_MS) return
        }

        if (!mayPlay()) return

        // Achtung: hier stand einmal `?: Long.MIN_VALUE`. `now - Long.MIN_VALUE`
        // laeuft im Long-Bereich ueber und wird negativ - die Bedingung war
        // damit immer wahr und der erste Klick jeder Sorte wurde
        // verschluckt. Weil der Zeitstempel erst nach der Pruefung gesetzt
        // wurde, blieb es nicht beim ersten: der Ton ging nie los.
        val soundPool = pool ?: return
        val id = streamIds[click] ?: return
        lastPlayedAt[click] = now
        // Prioritaet 1, kein Loop, normale Tonhoehe. Ist die Datei noch
        // nicht dekodiert, liefert play() 0 - dann wird sie vorgemerkt und
        // nach dem Laden nachgeholt, statt den Tipp zu verschlucken.
        if (id !in loaded) pending[click] = id
        soundPool.play(id, VOLUME, VOLUME, 1, 0, 1f)
        // Der Klick soll ueber dem Dauerton stehen, nicht in ihm
        // untergehen - und die Summe beider darf nicht clippen.
        duckHumsForClick()
    }

    /** Beginn des Ordner-Scans: der Ton laeuft, bis [stopScanHum] ihn beendet. */
    fun startScanHum() = startHum(Hum.SCAN)

    /** Ende des Ordner-Scans. */
    fun stopScanHum() = stopHum(Hum.SCAN)

    /**
     * Ein Auftrag laeuft - der Ton laeuft mit.
     *
     * Dass die App dabei sichtbar ist, entscheidet der Aufrufer: der Ton
     * meldet einen Zustand dieser Oberflaeche, nicht des Prozesses.
     */
    fun startJobHum() = startHum(Hum.JOB)

    /** Auftrag beendet, abgebrochen oder Oberflaeche nicht mehr sichtbar. */
    fun stopJobHum() = stopHum(Hum.JOB)

    /**
     * Laeuft der Ton an.
     *
     * Laeuft er schon, bleibt er stehen - ein zweiter Strom derselben
     * Schleife waere doppelt so laut und liesse sich nicht mehr sauber
     * beenden.
     */
    private fun startHum(kind: Hum) {
        val channel = humChannels.getValue(kind)
        if (channel.stream != 0 && !channel.stopping) return
        if (channel.stream != 0 && channel.stopping) {
            // Der alte Strom wird gerade ausgeblendet. Er muss jetzt
            // weichen: sonst liefe er als zweite Schleife derselben Datei
            // weiter, waehrend der neue startet - doppelter Pegel, und
            // die Pegelteilung zaehlte ihn nicht mit. Das harte Anhalten
            // ist hier unhoerbar, der Ton steht schon unter 0,2.
            cancelRamp(kind)
            stopStream(channel.stream)
            channel.stream = 0
            channel.stopping = false
            channel.volume = 0f
        }
        if (channel.wanted) return
        if (!mayPlay()) return
        if (channel.resId == 0) return
        // Noch nicht dekodiert: vormerken und im Lade-Callback anlaufen
        // lassen. Sonst bliebe der Vorgang stumm, wenn er kurz nach dem
        // App-Start losgeht.
        if (channel.resId !in loaded) {
            channel.wanted = true
            return
        }
        playHum(kind)
    }

    /** Laeuft die Schleife an. Nur von [startHum] und dem Lade-Callback. */
    private fun playHum(kind: Hum) {
        val channel = humChannels.getValue(kind)
        val soundPool = pool ?: return
        if (channel.resId == 0 || channel.resId !in loaded || channel.stream != 0) return
        // loop = -1: endlos, bis stop() kommt. Die Dateien sind nahtlose
        // Schleifen (siehe tools/generate_click_sounds.py), damit an der
        // Naht kein Knacken entsteht.
        //
        // Anlauf bei 0: der Ton ist laut genug, dass er zusammen mit dem
        // Start-Klick in die Begrenzung liefe. Danach wird auf die
        // Ziel-Lautstaerke geblendet.
        val stream = soundPool.play(channel.resId, 0f, 0f, 1, -1, 1f)
        if (stream == 0) return
        channel.stream = stream
        channel.volume = 0f
        // Laeuft der andere Ton schon, teilen sich beide den Pegel.
        humChannels.forEach { (other, running) ->
            if (other != kind && running.stream != 0) {
                rampHum(other, running.stream, running.volume, targetVolume(), HUM_FADE_STEPS)
            }
        }
        rampHum(kind, stream, 0f, targetVolume(), HUM_FADE_IN_STEPS)
    }

    /**
     * Ende eines Dauertons: ausblenden, dann anhalten.
     *
     * Die Ausblendung laeuft ueber den Main-Looper (160 ms). Ein zweiter
     * Aufruf waehrenddessen ist **folgenlos** - er darf die Rampe nicht
     * abloesen, weil in ihrem letzten Schritt das Anhalten steckt; genau
     * das war der Fehler, der den Ton im Hintergrund weiterlaufen liess.
     */
    private fun stopHum(kind: Hum) {
        val channel = humChannels.getValue(kind)
        channel.wanted = false
        val stream = channel.stream
        if (stream == 0) return
        if (channel.stopping) return
        channel.stopping = true
        // Von der aktuellen Lautstaerke aus ausblenden: bricht ein Lauf
        // 60 ms nach dem Start ab, steht der Ton noch beim Anlaufwert -
        // von der Ziel-Lautstaerke aus zu rechnen waere ein Sprung nach
        // oben.
        rampHum(kind, stream, channel.volume, 0f, HUM_FADE_STEPS, thenStop = true)
        // Der andere laeuft jetzt allein und bekommt den Pegel zurueck.
        humChannels.forEach { (other, running) ->
            if (other != kind && running.stream != 0 && !running.stopping) {
                rampHum(other, running.stream, running.volume, targetVolume(), HUM_FADE_STEPS)
            }
        }
    }

    /** Lautstaerke, die ein laufender Ton haben soll. */
    private fun targetVolume(): Float =
        if (humChannels.values.count { it.stream != 0 && !it.stopping } >= 2) {
            HUM_VOLUME * HUM_DUCK
        } else {
            HUM_VOLUME
        }

    /**
     * Blendet einen Strom von [from] auf [to] und haelt ihn dort (oder
     * haelt ihn am Ende an).
     *
     * Eine laufende Rampe desselben Kanals wird abgeloest, nicht
     * gestapelt: sonst schrieben zwei Rampen abwechselnd Lautstaerken in
     * denselben Strom, und eine Ausblendung waere wieder aufgehoben.
     */
    private fun rampHum(
        kind: Hum,
        stream: Int,
        from: Float,
        to: Float,
        steps: Int,
        thenStop: Boolean = false,
    ) {
        val channel = humChannels.getValue(kind)
        cancelRamp(kind)
        val handler = fadeHandler ?: Handler(Looper.getMainLooper()).also { fadeHandler = it }
        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                step++
                val done = step >= steps
                val volume = if (done) to else from + (to - from) * step.toFloat() / steps
                // Auch den letzten Schritt noch schreiben: ohne diese Zeile
                // wuerde ein ausgeblendeter Strom bei 0,2 seiner Lautstaerke
                // abgeschnitten - ein Sprung von -16 dBFS, also genau der
                // Knall, den die Ausblendung verhindern soll.
                runCatching { pool?.setVolume(stream, volume, volume) }
                if (stream == channel.stream) channel.volume = volume
                if (thenStop && done) {
                    stopStream(stream)
                    if (stream == channel.stream) {
                        channel.stream = 0
                        channel.stopping = false
                        channel.volume = 0f
                    }
                    channel.ramp = null
                    return
                }
                if (done) channel.ramp = null else handler.postDelayed(this, HUM_FADE_MS)
            }
        }
        channel.ramp = runnable
        // Sofort der erste Schritt: mit postDelayed waere ein Aufruf mit
        // steps = 1 (die Absenkung fuer einen Klick) wirkungslos.
        handler.post(runnable)
    }

    /**
     * Senkt die laufenden Dauertoene fuer die Dauer eines Klicks ab und
     * holt sie danach zurueck.
     *
     * Faellt nichts zu tun an, wenn kein Dauerton laeuft - das ist der
     * Normalfall (ein Klick beim Tippen).
     */
    private fun duckHumsForClick() {
        val running = humChannels.filter { it.value.stream != 0 && !it.value.stopping }
        if (running.isEmpty()) return
        val handler = fadeHandler ?: Handler(Looper.getMainLooper()).also { fadeHandler = it }
        // Ein Budget fuer alle: 0,85 + 0,78·0,19 = 0,998. Es auf die Zahl
        // der laufenden Toene zu verteilen ist der einzige Weg, der auch
        // dann haelt, wenn beide gleichzeitig laufen.
        val perHum = HUM_CLICK_DUCK / running.size
        running.forEach { (kind, channel) ->
            rampHum(kind, channel.stream, channel.volume, channel.volume * perHum, 1)
        }
        clickDuckRestore?.let { handler.removeCallbacks(it) }
        val restore = Runnable {
            clickDuckRestore = null
            humChannels.forEach { (kind, channel) ->
                if (channel.stream != 0 && !channel.stopping) {
                    rampHum(kind, channel.stream, channel.volume, targetVolume(), 4)
                }
            }
        }
        clickDuckRestore = restore
        handler.postDelayed(restore, HUM_CLICK_DUCK_MS)
    }

    private fun cancelRamp(kind: Hum) {
        val channel = humChannels.getValue(kind)
        channel.ramp?.let { fadeHandler?.removeCallbacks(it) }
        channel.ramp = null
    }

    private fun logReasonOnce(reason: SoundPolicy.Reason) {
        if (lastReason == reason) return
        lastReason = reason
        Log.d(TAG, reason.text)
    }

    /** Alles, was fuer "warum kein Ton" gebraucht wird - eine Zeile. */
    private fun describe(context: Context): String {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val systemSetting = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, -1)
        }.getOrDefault(-1)
        val ringer = when (manager?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "lautlos"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibration"
            else -> "normal"
        }
        val streamVolume = runCatching {
            manager?.getStreamVolume(AudioManager.STREAM_SYSTEM)
        }.getOrNull()
        val appVolume = runCatching {
            manager?.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrNull()
        return "Klickgeraeusche: App-Schalter ${if (appEnabled) "an" else "aus"}" +
            " \u00b7 Systemton-Modus $ringer" +
            " \u00b7 Medienlautstaerke ${appVolume ?: "unbekannt"} (regelt diese Toene)" +
            " \u00b7 Systemlautstaerke ${streamVolume ?: "unbekannt"} (ohne Wirkung)" +
            " \u00b7 Systemeinstellung Beruehrungsklaenge " +
            when (systemSetting) {
                1 -> "an (schaltet hier nichts ab)"
                0 -> "aus (schaltet hier nichts ab)"
                else -> "unbekannt"
            }
    }

    /** Nur fuer Tests: der Pool, um Aufrufe pruefen zu koennen. */
    internal fun poolForTest(): SoundPool? = pool

    /** Nur fuer Tests: das AudioManager-Objekt, aus dem die Regel liest. */
    internal fun audioForTest(): AudioManager? = audio

    /** Nur fuer Tests: laeuft gerade ein Schleifen-Strom? */
    internal fun humPlayingForTest(kind: Hum): Boolean =
        humChannels.getValue(kind).let { it.stream != 0 && !it.stopping }

    /** Nur fuer Tests: wie oft ein Strom angehalten wurde. */
    internal fun stopCountForTest(): Int = stops

    /** Nur fuer Tests: aktuelle Lautstaerke eines Schleifen-Stroms. */
    internal fun humVolumeForTest(kind: Hum): Float = humChannels.getValue(kind).volume

    /** Nur fuer Tests: wartet der Ton auf die Dekodierung? */
    internal fun humWantedForTest(kind: Hum): Boolean = humChannels.getValue(kind).wanted


    /** Gibt die dekodierten Puffer frei; nur fuer den Prozessabbau. */
    fun release() {
        // Erst die Ausblendung abbrechen: sie wuerde sonst auf einen
        // freigegebenen Pool zugreifen.
        clickDuckRestore?.let { fadeHandler?.removeCallbacks(it) }
        clickDuckRestore = null
        humChannels.forEach { (kind, channel) ->
            cancelRamp(kind)
            channel.stream = 0
            channel.stopping = false
            channel.wanted = false
            channel.volume = 0f
        }
        pool?.release()
        pool = null
        streamIds.clear()
        lastPlayedAt.clear()
        loaded.clear()
        pending.clear()
        lastReason = null
    }
}
