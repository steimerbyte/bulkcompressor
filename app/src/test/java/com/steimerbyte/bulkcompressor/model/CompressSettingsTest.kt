package com.steimerbyte.bulkcompressor.model

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Jede Einstellung muss den Neustart ueberleben.
 *
 * Der Fehler, den dieser Test verhindert, ist unsichtbar und aergerlich:
 * ein Feld wird in `save` vergessen, die App startet nach dem Neustart mit
 * dem Standardwert, und der Nutzer stellt dieselbe Sache jeden Lauf neu
 * ein. Deshalb prueft der Test jedes Feld gegen einen Wert, der sich vom
 * Standard unterscheidet - ein vergessener Schluessel kann so nicht
 * durchrutschen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CompressSettingsTest {

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun leereAblage() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_${System.nanoTime()}", Context.MODE_PRIVATE)
    }

    @Test
    fun `alle Einstellungen ueberleben speichern und laden`() {
        val gesetzt = CompressSettings(
            videoMode = VideoMode.BITRATE,
            bitratePercent = 35,
            videoShortEdge = 720,
            codec = VideoCodec.HEVC,
            imageQuality = 62,
            imageShortEdge = 1440,
            imageFormat = ImageFormat.WEBP,
            outputMode = OutputMode.KEEP_ORIGINAL,
            keepTimestamps = false,
            keepExif = false,
            clickSounds = false,
            ignoreCompressionTag = true
        )
        gesetzt.save(prefs)

        assertEquals(gesetzt, CompressSettings.load(prefs))
    }

    @Test
    fun `leere Ablage ergibt die Standardwerte`() {
        assertEquals(CompressSettings(), CompressSettings.load(prefs))
    }

    @Test
    fun `unbekannter Enum-Name faellt auf den Standard zurueck`() {
        // Eine aeltere oder neuere Version kann andere Namen geschrieben
        // haben; das darf die Einstellungen nicht unlesbar machen.
        prefs.edit()
            .putString("video_mode", "GIBTSNICHT")
            .putString("codec", "AV2")
            .putString("output_mode", "")
            .apply()

        val geladen = CompressSettings.load(prefs)
        val standard = CompressSettings()
        assertEquals(standard.videoMode, geladen.videoMode)
        assertEquals(standard.codec, geladen.codec)
        assertEquals(standard.outputMode, geladen.outputMode)
    }
}
