package com.steimerbyte.bulkcompressor.engine

import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.model.VideoCodec
import com.steimerbyte.bulkcompressor.model.VideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Zahlen, nach denen der Transcoder arbeitet. Diese Tests laufen
 * ohne Geraet, weil PresetEngine bewusst keine Android-Klasse anfasst.
 */
class PresetEngineTest {

    @Test
    fun `querformat skaliert auf die kurze kante`() {
        val (w, h) = PresetEngine.scaleToShortEdge(3840, 2160, 1080)
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun `hochformat skaliert auf die kurze kante`() {
        val (w, h) = PresetEngine.scaleToShortEdge(2160, 3840, 1080)
        assertEquals(1080, w)
        assertEquals(1920, h)
    }

    @Test
    fun `wird nie hochskaliert`() {
        val (w, h) = PresetEngine.scaleToShortEdge(1280, 720, 2160)
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun `kanten sind immer gerade`() {
        // 1920 * 601/1080 = 1068.9 -> 1069 waere ungerade und damit fuer
        // den Encoder unbrauchbar.
        val (w, h) = PresetEngine.scaleToShortEdge(1920, 1080, 601)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test
    fun `bitrate modus laesst die aufloesung stehen`() {
        val plan = PresetEngine.videoPlan(
            width = 1920,
            height = 1080,
            sourceBitrate = 8_000_000,
            frameRate = 30,
            sourceMime = "video/avc",
            settings = CompressSettings(videoMode = VideoMode.BITRATE, bitratePercent = 60)
        )
        assertEquals(1920, plan.width)
        assertEquals(1080, plan.height)
        assertEquals(4_800_000, plan.bitrate)
        assertFalse(plan.scaleChanged)
    }

    @Test
    fun `skalieren haelt die bits pro pixel`() {
        val plan = PresetEngine.videoPlan(
            width = 3840,
            height = 2160,
            sourceBitrate = 40_000_000,
            frameRate = 30,
            sourceMime = "video/avc",
            settings = CompressSettings(videoMode = VideoMode.SCALE, videoShortEdge = 1080)
        )
        assertEquals(1920, plan.width)
        assertEquals(1080, plan.height)
        // Viertel der Flaeche (1920*1080 / 3840*2160 = 0.25)
        assertEquals(10_000_000, plan.bitrate)
    }

    @Test
    fun `beides nimmt den kleineren wert`() {
        val plan = PresetEngine.videoPlan(
            width = 3840,
            height = 2160,
            sourceBitrate = 40_000_000,
            frameRate = 30,
            sourceMime = "video/avc",
            settings = CompressSettings(
                videoMode = VideoMode.BOTH,
                bitratePercent = 50,
                videoShortEdge = 1080
            )
        )
        // 50 % = 20 Mbit, Flaechenanteil = 10 Mbit -> der kleinere gewinnt
        assertEquals(10_000_000, plan.bitrate)
    }

    @Test
    fun `bitrate steigt nie ueber die quelle`() {
        val plan = PresetEngine.videoPlan(
            width = 640,
            height = 480,
            sourceBitrate = 300_000,
            frameRate = 30,
            sourceMime = "video/avc",
            settings = CompressSettings(videoMode = VideoMode.BITRATE, bitratePercent = 100)
        )
        assertTrue(plan.bitrate <= 300_000)
    }

    @Test
    fun `ohne skalierung und ohne bitratenverlust wird uebersprungen`() {
        val plan = PresetEngine.videoPlan(
            width = 1920,
            height = 1080,
            sourceBitrate = 8_000_000,
            frameRate = 30,
            sourceMime = "video/avc",
            settings = CompressSettings(videoMode = VideoMode.SCALE, videoShortEdge = 0)
        )
        assertTrue(plan.isNoOp)
    }

    @Test
    fun `auto waehlt hevc nur bei hevc quelle`() {
        assertEquals(
            PresetEngine.MIME_HEVC,
            PresetEngine.codecMime(VideoCodec.AUTO, "video/hevc")
        )
        assertEquals(
            PresetEngine.MIME_AVC,
            PresetEngine.codecMime(VideoCodec.AUTO, "video/avc")
        )
        assertEquals(
            PresetEngine.MIME_AVC,
            PresetEngine.codecMime(VideoCodec.AUTO, null)
        )
    }

    @Test
    fun `geschaetzte groesse bleibt unter der quelle`() {
        val plan = PresetEngine.videoPlan(
            width = 1920,
            height = 1080,
            sourceBitrate = 8_000_000,
            frameRate = 30,
            sourceMime = "video/avc",
            settings = CompressSettings(videoMode = VideoMode.BITRATE, bitratePercent = 60)
        )
        val estimated = PresetEngine.estimateVideoBytes(
            durationMs = 60_000,
            plan = plan,
            sourceSizeBytes = 60_000_000,
            audioBytes = 960_000
        )
        assertTrue(estimated < 60_000_000)
        assertTrue(estimated > 0)
    }
}
