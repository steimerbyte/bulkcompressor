package com.steimerbyte.bulkcompressor.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.provider.Settings
import com.steimerbyte.bulkcompressor.R

/**
 * Klickgeraeusche fuer Beruehrungen.
 *
 * Die App liefert drei kurze, synthetisch erzeugte Holz-Klicks mit
 * (`tools/generate_click_sounds.py`): einen hellen fuer Chips und kleine
 * Knoepfe, einen tieferen fuer Schalter, einen groesseren fuer den
 * Startknopf.
 *
 * [SoundPool] statt [android.media.MediaPlayer]: die Dateien sind beim
 * ersten Tippen schon dekodiert, es gibt keine Startlatenz und kein
 * Nachladen von der Platte. Mehr als vier gleichzeitige Straenge sind
 * nicht sinnvoll -- mehr Klicks pro Sekunde gibt es nicht.
 *
 * Ton an oder aus entscheiden zwei Dinge: der Schalter in der App und die
 * Systemeinstellung "Tastentoenen/Beruehrungsklaenge" (wer die
 * abgeschaltet hat, will sie auch hier nicht). Beides wird geprueft, der
 * Schalter in der App gewinnt nicht gegen das System.
 */
object SoundKit {

    /** Was geklickt hat. Bestimmt die Datei und den Mindestabstand. */
    enum class Click { TICK, TOGGLE, THUNK }

    private const val MAX_STREAMS = 4
    private const val VOLUME = 0.55f

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
    private var systemEnabled = true

    /** Wirksam ist nur, wenn die App es will UND das System es erlaubt. */
    val enabled: Boolean get() = appEnabled && systemEnabled

    /**
     * Einmal nach dem Aufbau der Oberflaeche aufrufen. Der zweite Aufruf
     * mit denselben Argumenten ist folgenlos -- nur ein neuer Prozess
     * traegt einen eigenen SoundPool.
     */
    fun init(context: Context, enabled: Boolean) {
        appEnabled = enabled
        systemEnabled = readSystemSetting(context)
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val created = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(attributes)
            .build()
        streamIds[Click.TICK] = created.load(context, R.raw.click_tick, 1)
        streamIds[Click.TOGGLE] = created.load(context, R.raw.click_toggle, 1)
        streamIds[Click.THUNK] = created.load(context, R.raw.click_thunk, 1)
        pool = created
    }

    fun setEnabled(enabled: Boolean) {
        appEnabled = enabled
    }

    /**
     * Nach der Rueckkehr in den Vordergrund: der Nutzer kann die
     * Systemeinstellung waehrend der Laufzeit umgestellt haben.
     */
    fun refreshSystemSetting(context: Context) {
        systemEnabled = readSystemSetting(context)
    }

    fun play(click: Click) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        val last = lastPlayedAt[click] ?: Long.MIN_VALUE
        if (now - last < MIN_GAP_MS) return
        val soundPool = pool ?: return
        val id = streamIds[click] ?: return
        lastPlayedAt[click] = now
        // Prioritaet 1, kein Loop, normale Tonhoehe. Solange eine Datei
        // noch laedt, liefert play() einfach 0 und tut nichts.
        soundPool.play(id, VOLUME, VOLUME, 1, 0, 1f)
    }

    /** Gibt die dekodierten Puffer frei; nur fuer den Prozessabbau. */
    fun release() {
        pool?.release()
        pool = null
        streamIds.clear()
        lastPlayedAt.clear()
    }

    private fun readSystemSetting(context: Context): Boolean = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SOUND_EFFECTS_ENABLED,
            1
        ) == 1
    }.getOrDefault(true)
}
