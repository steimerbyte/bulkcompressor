package com.steimerbyte.bulkcompressor.engine

import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.model.VideoCodec
import com.steimerbyte.bulkcompressor.model.VideoMode

/** Zielgroesse einer Videospur nach Preset. */
data class VideoPlan(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Int,
    val codecMime: String,
    val sourceBitrate: Int,
    val scaleChanged: Boolean
) {
    /** Erwartete Groesse im Verhaeltnis zur Quelle (nur Videospur). */
    val bitrateRatio: Double
        get() = if (sourceBitrate <= 0) 1.0 else bitrate.toDouble() / sourceBitrate

    val isNoOp: Boolean
        get() = !scaleChanged && bitrateRatio >= 0.98
}

/**
 * Reine Rechenlogik: Presets, Skalierung und Bitraten.
 *
 * Bewusst ohne Android-Importe, damit sie in lokalen Unit-Tests
 * pruefbar bleibt - MediaCodec laesst sich im JVM-Test nicht betreiben,
 * die Zahlen folgen aber genau hier.
 */
object PresetEngine {

    const val MIME_AVC = "video/avc"
    const val MIME_HEVC = "video/hevc"

    /** Codec, wenn die Quelle keinen verwertbaren Typ hat (H.264 ist die sichere Wahl). */
    private const val MIN_BITRATE = 250_000

    /**
     * Durchschnittliche Bits pro Pixel und Frame, wenn die Quelle keine
     * Bitrate meldet. 0.10 entspricht einem ueblichen Handy-Encoding
     * (1080p30 -> ~6.2 Mbit/s).
     */
    private const val DEFAULT_BITS_PER_PIXEL = 0.10

    fun evenDown(value: Int): Int {
        val v = value / 2 * 2
        return if (v < 2) 2 else v
    }

    /**
     * Skaliert so, dass die **kurze** Kante hoechstens [maxShortEdge]
     * Pixel hat. Das ist die Bedeutung von "1080p": 1920x1080 im
     * Querformat, 1080x1920 im Hochformat. Nie hochskalieren, nie
     * ungerade Kanten - Encoder lehnen ungerade Werte ab.
     */
    fun scaleToShortEdge(width: Int, height: Int, maxShortEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return evenDown(width) to evenDown(height)
        val shortEdge = minOf(width, height)
        if (maxShortEdge <= 0 || maxShortEdge >= shortEdge) {
            return evenDown(width) to evenDown(height)
        }
        val factor = maxShortEdge.toDouble() / shortEdge
        return evenDown(Math.round(width * factor).toInt()) to
            evenDown(Math.round(height * factor).toInt())
    }

    fun estimateSourceBitrate(width: Int, height: Int, frameRate: Int): Int {
        val fps = frameRate.coerceIn(1, 240)
        val bits = width.toDouble() * height.toDouble() * fps * DEFAULT_BITS_PER_PIXEL
        return bits.toInt().coerceAtLeast(MIN_BITRATE)
    }

    fun codecMime(codec: VideoCodec, sourceMime: String?): String = when (codec) {
        VideoCodec.AVC -> MIME_AVC
        VideoCodec.HEVC -> MIME_HEVC
        VideoCodec.AUTO ->
            if (sourceMime?.startsWith("video/hevc") == true) MIME_HEVC else MIME_AVC
    }

    /**
     * Baut den Zielplan.
     *
     * @param sourceBitrate Bitrate der Quelldatei in bit/s, 0 wenn unbekannt
     * @param sourceMime    Typ der Quellspur, fuer den Codec-Modus AUTO
     */
    fun videoPlan(
        width: Int,
        height: Int,
        sourceBitrate: Int,
        frameRate: Int,
        sourceMime: String?,
        settings: CompressSettings
    ): VideoPlan {
        val fps = frameRate.coerceIn(1, 240)
        val srcBps = if (sourceBitrate > 0) sourceBitrate
        else estimateSourceBitrate(width, height, fps)

        val wantScale = settings.videoMode != VideoMode.BITRATE
        val (targetW, targetH) = if (wantScale) {
            scaleToShortEdge(width, height, settings.videoShortEdge)
        } else {
            evenDown(width) to evenDown(height)
        }
        val scaleChanged = targetW != width || targetH != height

        val areaRatio = if (width > 0 && height > 0) {
            (targetW.toDouble() * targetH) / (width.toDouble() * height)
        } else 1.0

        val percentRatio = settings.bitratePercent.coerceIn(5, 100) / 100.0
        val requested = when (settings.videoMode) {
            VideoMode.BITRATE -> srcBps * percentRatio
            // Beim reinen Skalieren bleibt die Bitrate pro Pixel gleich:
            // das Bild sieht genauso aus wie vorher, nur kleiner.
            VideoMode.SCALE -> srcBps * areaRatio
            VideoMode.BOTH -> minOf(srcBps * percentRatio, srcBps * areaRatio)
        }

        val clamped = requested
            .coerceAtMost(srcBps.toDouble())      // nie groesser als die Quelle
            .coerceAtLeast(MIN_BITRATE.toDouble()) // nie unbrauchbar klein

        return VideoPlan(
            width = targetW,
            height = targetH,
            bitrate = clamped.toInt(),
            frameRate = fps,
            codecMime = codecMime(settings.codec, sourceMime),
            sourceBitrate = srcBps,
            scaleChanged = scaleChanged
        )
    }

    /**
     * Erwartete Ausgabegroesse eines Videos: Videospur aus der Zielbitrate,
     * Tonspur bleibt unveraendert (wird durchgereicht).
     */
    fun estimateVideoBytes(durationMs: Long, plan: VideoPlan, sourceSizeBytes: Long, audioBytes: Long): Long {
        if (durationMs <= 0) return sourceSizeBytes
        val seconds = durationMs / 1000.0
        val videoBytes = seconds * plan.bitrate / 8.0
        return (videoBytes + audioBytes).toLong().coerceAtMost(sourceSizeBytes)
    }

    /** Grobe Erwartung fuer Bilder: Flaechenanteil mal Qualitaetsfaktor. */
    fun estimateImageBytes(sourceSize: Long, sourceShortEdge: Int, targetShortEdge: Int, quality: Int): Long {
        if (targetShortEdge <= 0 || sourceShortEdge <= 0 || targetShortEdge >= sourceShortEdge) {
            // keine Skalierung: nur die Qualitaetsstufe wirkt
            return (sourceSize * (0.55 + (quality - 60) / 100.0)).toLong().coerceAtMost(sourceSize)
        }
        val ratio = (targetShortEdge.toDouble() / sourceShortEdge)
        val areaRatio = ratio * ratio
        return (sourceSize * areaRatio * (0.75 + (quality - 60) / 200.0)).toLong().coerceAtMost(sourceSize)
    }
}
