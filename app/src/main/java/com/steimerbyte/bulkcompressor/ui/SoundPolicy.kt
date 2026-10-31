package com.steimerbyte.bulkcompressor.ui

import android.media.AudioManager

/**
 * Die Entscheidung "klingelt es jetzt oder nicht" - als reine Funktion.
 *
 * Sie ist bewusst von [SoundKit] getrennt: der Ton selbst laesst sich ohne
 * Geraet nicht pruefen, die Begruendung schon. Und die Begruendung ist
 * hier das Eigentliche - "kein Ton" hat drei verschiedene Ursachen, die
 * man dem Nutzer nicht zumuten kann zu raten.
 *
 * Zwei Schalter, nicht drei:
 *
 * 1. **Der Schalter in der App.** Er ist die ausdrueckliche Entscheidung
 *    fuer diese App und damit der massgebliche.
 * 2. **Die Lautlos-Regel des Systems.** Ein Telefon im Lautlos- oder
 *    Vibrationsmodus gibt keine Beruehrungstoene aus - das ist die
 *    Erwartung, die jeder kennt, und sie gilt auch fuer eine App, die
 *    ihre eigenen Klicks mitbringt.
 *
 * Was hier **nicht** mehr entscheidet, ist `SOUND_EFFECTS_ENABLED`
 * ("Beruehrungsklaenge" in den Systemeinstellungen). Diese Einstellung
 * steuert die Systemtoene; auf vielen Geraeten ist sie ab Werk aus, und
 * dann schwieg diese App, obwohl ihr eigener Schalter sichtbar auf an
 * stand - ohne einen Hinweis, warum. Der Wert wird weiterhin ins
 * Protokoll geschrieben, damit er bei einer Fehlersuche nicht fehlt,
 * aber er schaltet den Ton nicht mehr ab.
 */
object SoundPolicy {

    /** Warum gespielt wird oder nicht - der Text steht im Protokoll. */
    enum class Reason(val text: String) {
        ALLOWED("Klick wird gespielt"),
        APP_OFF("App-Schalter 'Klickgeraeusche' ist aus"),
        SILENT_MODE("Telefon ist lautlos oder auf Vibration"),
        NO_STREAM_VOLUME("Systemlautstaerke ist 0")
    }

    data class Decision(val play: Boolean, val reason: Reason)

    /**
     * [ringerMode] ist der Wert aus [AudioManager.getRingerMode],
     * [systemVolume] die Lautstaerke des Systemtons (`STREAM_SYSTEM`).
     * Beides als Zahlen, damit die Entscheidung ohne Android pruefbar ist.
     */
    fun decide(
        appEnabled: Boolean,
        ringerMode: Int,
        systemVolume: Int
    ): Decision = when {
        !appEnabled -> Decision(false, Reason.APP_OFF)
        ringerMode != AudioManager.RINGER_MODE_NORMAL -> Decision(false, Reason.SILENT_MODE)
        systemVolume <= 0 -> Decision(false, Reason.NO_STREAM_VOLUME)
        else -> Decision(true, Reason.ALLOWED)
    }
}
