package com.steimerbyte.bulkcompressor.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
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
 * Ende.
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


    private var pool: SoundPool? = null
    private val streamIds = HashMap<Click, Int>()
    private val lastPlayedAt = HashMap<Click, Long>()

    private var appEnabled = true
    private var audio: AudioManager? = null

    /** Zuletzt protokollierter Grund; nur Aenderungen werden geschrieben. */
    private var lastReason: SoundPolicy.Reason? = null

    /** Noch nicht dekodierte Klicks: sie werden nach dem Laden nachgeholt. */
    private val loaded = HashSet<Int>()
    private val pending = HashMap<Click, Int>()

    /**
     * Einmal nach dem Aufbau der Oberflaeche aufrufen. Der zweite Aufruf
     * mit denselben Argumenten ist folgenlos -- nur ein neuer Prozess
     * traegt einen eigenen SoundPool.
     */
    fun init(context: Context, enabled: Boolean) {
        appEnabled = enabled
        if (pool != null) return
        audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
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
        pool = created
        Log.i(TAG, describe(context))
    }

    fun setEnabled(enabled: Boolean) {
        appEnabled = enabled
        lastReason = null
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

    fun play(click: Click) {
        // Die Bremse zuerst: sonst kostet jeder einzelne Reglerschritt eine
        // Abfrage beim AudioService (Binder), obwohl danach neun von zehn
        // Aufrufen ohnehin verworfen werden.
        val now = SystemClock.uptimeMillis()
        lastPlayedAt[click]?.let { last ->
            if (now - last < MIN_GAP_MS) return
        }

        val manager = audio
        val decision = SoundPolicy.decide(
            appEnabled = appEnabled,
            ringerMode = manager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL,
            systemVolume = runCatching {
                manager?.getStreamVolume(AudioManager.STREAM_SYSTEM) ?: 1
            }.getOrDefault(1)
        )
        if (!decision.play) {
            logReasonOnce(decision.reason)
            return
        }
        logReasonOnce(SoundPolicy.Reason.ALLOWED)

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
            " \u00b7 Systemlautstaerke ${streamVolume ?: "unbekannt"}" +
            " \u00b7 Medienlautstaerke ${appVolume ?: "unbekannt"}" +
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


    /** Gibt die dekodierten Puffer frei; nur fuer den Prozessabbau. */
    fun release() {
        pool?.release()
        pool = null
        streamIds.clear()
        lastPlayedAt.clear()
        loaded.clear()
        pending.clear()
        lastReason = null
    }
}
