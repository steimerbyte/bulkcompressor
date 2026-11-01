package com.steimerbyte.bulkcompressor.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Range
import android.view.Surface
import com.steimerbyte.bulkcompressor.log.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TranscodeException(message: String) : Exception(message)

class JobCancelledException : Exception("Abgebrochen")

/** So lange darf der Encoder nach dem letzten Bild auf sein Ende warten. */
private const val EOS_DEADLINE_NS = 15_000_000_000L

/** So lange darf der Audio-Encoder ohne freien Eingabepuffer bleiben. */
private const val AUDIO_STALL_NS = 5_000_000_000L

data class VideoSourceInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val bitrate: Int,
    val frameRate: Int,
    val durationMs: Long,
    val videoMime: String,
    val audioMime: String?
)

/**
 * Video-Transkodierung mit Decoder -> Surface -> Encoder.
 *
 * Der entscheidende Punkt fuer wenig RAM: es wird **kein Frame** in den
 * App-Speicher kopiert. Der Decoder rendert direkt auf die Eingabe-
 * Surface des Encoders, beide Puffer liegen im Codec. Die App haelt
 * waehrend des gesamten Laufs nur ein paar Kilobyte.
 *
 * Die Tonspur wird unveraendert durchgereicht, wenn sie AAC ist;
 * alles andere (Opus, Vorbis, PCM) wird nach AAC gewandelt, damit es
 * in den MP4-Container passt.
 */
class VideoTranscoder(private val context: Context) {

    fun probe(uri: Uri): VideoSourceInfo {
        val extractor = MediaExtractor()
        var videoMime: String? = null
        var audioMime: String? = null
        var frameRate = 0
        var bitrate = 0
        var trackCount = 0
        var videoIndex = -1
        var audioIndex = -1
        try {
            extractor.setDataSource(context, uri, null)
            trackCount = extractor.trackCount
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                Log.d(
                    "Video",
                    "Probe Spur $i/$trackCount: $mime" +
                        " · maxInputSize ${formatValue(format, MediaFormat.KEY_MAX_INPUT_SIZE)}" +
                        if (mime.startsWith("video/")) {
                            " · Profil ${formatValue(format, MediaFormat.KEY_PROFILE)}" +
                                " · Level ${formatValue(format, MediaFormat.KEY_LEVEL)}"
                        } else ""
                )
                if (mime.startsWith("video/") && videoMime == null) {
                    videoIndex = i
                    videoMime = mime
                    frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        format.getInteger(MediaFormat.KEY_FRAME_RATE)
                    } else 0
                    bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        format.getInteger(MediaFormat.KEY_BIT_RATE)
                    } else 0
                } else if (mime.startsWith("audio/") && audioMime == null) {
                    audioIndex = i
                    audioMime = mime
                }
            }
        } finally {
            extractor.release()
        }
        val mimeType = videoMime ?: throw TranscodeException("Keine Videospur gefunden")

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (bitrate <= 0) {
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull() ?: 0
            }
            if (frameRate <= 0) {
                frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.toInt() ?: 0
            }
            if (width <= 0 || height <= 0) throw TranscodeException("Aufloesung nicht ermittelbar")
            Log.d(
                "Video",
                "Metadaten: ${width}x${height} · Drehung $rotation° · $duration ms" +
                    " · Bitrate ${bitrate / 1000} kbit/s · Capture-${frameRate} fps"
            )
            if (audioMime == null) {
                Log.d("Video", "Container ohne Tonspur - Ausgabe bleibt stumm")
            }
            Log.i(
                "Video",
                "Probe: ${width}x${height} · Drehung $rotation°" +
                    " · ${if (frameRate > 0) frameRate else 30} fps · ${bitrate / 1000} kbit/s" +
                    " · $mimeType · Ton ${audioMime ?: "keine"} · ${duration / 1000} s"
            )
            Log.d(
                "Video",
                "Track-Auswahl: videoTrack=${if (videoIndex >= 0) videoIndex.toString() else "keine"}" +
                    " · audioTrack=${if (audioIndex >= 0) audioIndex.toString() else "keine"}" +
                    " · $trackCount Spuren"
            )
            return VideoSourceInfo(
                width = width,
                height = height,
                rotation = rotation,
                bitrate = bitrate,
                frameRate = if (frameRate > 0) frameRate else 30,
                durationMs = duration,
                videoMime = mimeType,
                audioMime = audioMime
            )
        } finally {
            retriever.release()
        }
    }

    /**
     * Schreibt die komprimierte Fassung nach [target].
     * @return der tatsaechlich verwendete Plan (Groesse und Bitrate
     *         koennen durch die Encoder-Faehigkeiten kleiner ausfallen)
     */
    fun transcode(
        uri: Uri,
        target: File,
        plan: VideoPlan,
        durationMs: Long,
        orientationHint: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        throttle: () -> Unit = {}
    ): VideoPlan {
        val abort = AtomicBoolean(false)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIndex < 0) videoTrackIndex = i
                else if (mime.startsWith("audio/") && audioTrackIndex < 0) audioTrackIndex = i
            }
            if (videoTrackIndex < 0) throw TranscodeException("Keine Videospur gefunden")

            val sourceFormat = extractor.getTrackFormat(videoTrackIndex)
            val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw TranscodeException("Unbekanntes Quellformat")
            // Die Drehung sitzt im Container, nicht in den Pixeln. Steht
            // sie nicht im Track-Format, kommt sie aus dem Metadaten-Lauf
            // des Aufrufers - sonst kippt jedes Hochformatvideo.
            val formatRotation = if (sourceFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                sourceFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            val rotation = if (formatRotation != 0) {
                normalizeRotation(formatRotation)
            } else {
                normalizeRotation(orientationHint)
            }
            Log.d(
                "Video",
                "Quelle: ${extractor.trackCount} Spuren · videoTrack=$videoTrackIndex" +
                    " · audioTrack=${if (audioTrackIndex >= 0) audioTrackIndex.toString() else "keine"}" +
                    " · Drehung $rotation° aus " +
                    if (formatRotation != 0) "dem Track-Format ($formatRotation)" else "den Metadaten ($orientationHint)"
            )
            Log.i(
                "Video",
                "Transcode ${target.name}: ${plan.width}x${plan.height} @ ${plan.bitrate / 1000} kbit/s" +
                    " · ${plan.frameRate} fps · ${plan.codecMime} · Drehung $rotation°" +
                    " · Tonspur ${if (audioTrackIndex >= 0) "vorhanden" else "keine"}" +
                    " · Clip ${durationMs / 1000} s"
            )

            // Eingangsmasse fuer den Skalier-Faktor am Ende; sie stehen
            // im Track-Format kodiert, also vor jeder Drehung.
            val sourceWidth = if (sourceFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                sourceFormat.getInteger(MediaFormat.KEY_WIDTH)
            } else 0
            val sourceHeight = if (sourceFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                sourceFormat.getInteger(MediaFormat.KEY_HEIGHT)
            } else 0
            // Grobe Erwartung allein aus der Zielbitrate der Videospur.
            val estimatedBytes = (durationMs / 1000.0 * plan.bitrate / 8.0).toLong()
            Log.d(
                "Video",
                "Bitrate: Quelle ${plan.sourceBitrate / 1000} kbit/s" +
                    " -> Ziel ${plan.bitrate / 1000} kbit/s" +
                    " (${String.format(Locale.US, "%.0f", plan.bitrateRatio * 100)} %)" +
                    " · Skalierung ${if (plan.scaleChanged) "ja" else "nein"}"
            )
            Log.d(
                "Video",
                "Ziel ${target.absolutePath}: vor dem Start ${target.length()} B" +
                    " · geschaetzt ${mb(estimatedBytes)} Videospur" +
                    " (${plan.bitrate / 1000} kbit/s ueber ${durationMs / 1000} s)"
            )

            val started = startEncoder(plan)
            val encoder = started.codec
            val fitted = started.plan
            val planNote = if (fitted != plan) {
                "Plan angepasst: ${plan.width}x${plan.height}" +
                    " @ ${plan.bitrate / 1000} kbit/s -> ${fitted.width}x${fitted.height}" +
                    " @ ${fitted.bitrate / 1000} kbit/s"
            } else {
                "Plan unveraendert ${plan.width}x${plan.height}" +
                    " @ ${plan.bitrate / 1000} kbit/s"
            }
            val candidateText = encoderCandidates(plan).joinToString(", ")
            Log.i(
                "Video",
                "Encoder-Auswahl: ${encoder.name} · ${hardwareLabel(encoder)}" +
                    " · maxInstances=${maxInstances(encoder, fitted.codecMime)}" +
                    " · Kandidaten $candidateText · $planNote"
            )

            var decoder: MediaCodec? = null
            val session = MuxerSession(
                target.absolutePath,
                rotation,
                if (audioTrackIndex >= 0) 2 else 1
            )
            Log.d(
                "Video",
                "Muxer: MP4 nach ${target.absolutePath} · ${if (audioTrackIndex >= 0) 2 else 1} Spuren erwartet" +
                    " · Drehung im Container $rotation°"
            )
            var audioThread: Thread? = null
            try {
                decoder = MediaCodec.createDecoderByType(sourceMime)
                decoder.configure(sourceFormat, started.surface, null, 0)
                decoder.start()
                extractor.selectTrack(videoTrackIndex)
                Log.d(
                    "Video",
                    "Decoder: ${decoder.name} · $sourceMime · Spur $videoTrackIndex" +
                        " · Surface-Eingang fuer ${fitted.width}x${fitted.height}"
                )

                if (audioTrackIndex >= 0) {
                    val index = audioTrackIndex
                    Log.d("Video", "Tonspur laeuft parallel in eigenem Thread (Spur $index)")
                    audioThread = Thread({
                        try {
                            writeAudio(uri, index, session) { abort.get() || isCancelled() }
                        } catch (t: Throwable) {
                            Log.e("Video", "Tonspur fehlgeschlagen", t)
                            session.fail(t)
                        }
                    }, "audio-tonspur")
                    audioThread.start()
                }

                transcodeVideo(
                    extractor, decoder, encoder, session, durationMs,
                    onProgress, isCancelled, throttle
                )
                audioThread?.join(10_000)
                if (audioThread?.isAlive == true) {
                    Log.w("Video", "Tonspur-Thread nach 10 s noch aktiv - Tonspur abgeschnitten")
                } else if (audioThread != null) {
                    Log.d("Video", "Tonspur-Thread regulaer beendet")
                }
                session.rethrowFailure()
                val frames = session.videoFrameCount()
                val audioBytes = session.audioByteCount()
                if (frames <= 0) {
                    Log.w("Video", "keine Videoframes gemuxt - Ausgabe ist leer")
                }
                if (audioTrackIndex >= 0 && audioBytes <= 0L) {
                    Log.w("Video", "Tonspur vorhanden, aber 0 Audiobytes gemuxt")
                }
                val fileBytes = target.length()
                val outBitrateKbit = if (durationMs > 0) {
                    (fileBytes * 8 / (durationMs / 1000.0) / 1000).toLong()
                } else 0L
                val inPixels = sourceWidth.toLong() * sourceHeight
                val outPixels = fitted.width.toLong() * fitted.height
                val scalePercent = if (inPixels > 0) outPixels * 100 / inPixels else 100L
                Log.i(
                    "Video",
                    "gemuxt: $frames Frames · $audioBytes B Audio · $fileBytes B Datei" +
                        " (= ${mb(fileBytes)}) · Ausgabe ${outBitrateKbit} kbit/s im Schnitt" +
                        " · Skalierung ${fitted.width}x${fitted.height} von" +
                        " ${sourceWidth}x${sourceHeight} = $scalePercent % der Flaeche"
                )
                return fitted
            } finally {
                abort.set(true)
                quiet("Audio-Thread.join") { audioThread?.join(2_000) }
                quiet("Decoder.stop") { decoder?.stop() }
                quiet("Decoder.release") { decoder?.release() }
                quiet("Video-Encoder.stop") { encoder.stop() }
                quiet("Video-Encoder.release") { encoder.release() }
                quiet("Surface.release") { started.surface.release() }
                session.release()
            }
        } catch (t: JobCancelledException) {
            Log.i("Video", "Transcode ${target.name} abgebrochen (Nutzer)")
            throw t
        } catch (t: Throwable) {
            // Weiterreichen wie bisher, aber mit Dateibezug im Protokoll.
            Log.e("Video", "Transcode ${target.name} fehlgeschlagen", t)
            throw t
        } finally {
            extractor.release()
        }
    }

    // ------------------------------------------------------------------
    // Video
    // ------------------------------------------------------------------

    private fun transcodeVideo(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        session: MuxerSession,
        durationMs: Long,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        throttle: () -> Unit
    ) {
        val info = MediaCodec.BufferInfo()
        val durationUs = durationMs * 1000L
        var inputDone = false
        var outputDone = false

        // Zaehler fuer die Verdichtungszeile am Ende - pro Frame wird
        // nichts geschrieben, sonst waere das Protokoll unlesbar.
        var samples = 0L
        var rendered = 0L
        var bytesIn = 0L
        var smallest = Long.MAX_VALUE
        var largest = 0L
        var inputStalls = 0L
        var tryAgainLater = 0L
        // Bitmaske der erreichten Meilensteine: 10/25/50/75 %, je ein Bit.
        var milestones = 0
        var lastTimestampUs = 0L
        val startedNs = System.nanoTime()
        val encoderStats = EncoderStats()
        Log.d(
            "Video",
            "Videospur startet: Clip ${durationMs / 1000} s (${durationUs} us)" +
                " · Fortschritt nach Sample-Zeitstempel" +
                " · Stufen ${MILESTONES.joinToString("/")} %"
        )

        while (!outputDone) {
            if (isCancelled()) throw JobCancelledException()

            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inputIndex)
                        ?: throw TranscodeException("Decoder-Puffer fehlt")
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                        Log.d(
                            "Video",
                            "Video-Eingabe am Ende: $samples Samples · ${mb(bytesIn)}" +
                                " · letzte Medienzeit ${clock(lastTimestampUs)}" +
                                " · ab hier nur noch Encoder-Rest ausgeben"
                        )
                    } else {
                        val timestamp = extractor.sampleTime
                        decoder.queueInputBuffer(inputIndex, 0, size, timestamp, 0)
                        samples++
                        bytesIn += size
                        val sampleBytes = size.toLong()
                        if (sampleBytes < smallest) smallest = sampleBytes
                        if (sampleBytes > largest) largest = sampleBytes
                        lastTimestampUs = if (timestamp > 0) timestamp else lastTimestampUs
                        extractor.advance()
                        // Alle 24 Samples eine Gelegenheit zu bremsen. Der
                        // Encoder haelt dabei Puffer, aber keinen Sperrzustand:
                        // die Schleife setzt einfach spaeter fort, und der
                        // Ton laeuft in seinem eigenen Thread weiter.
                        if (samples % THROTTLE_EVERY == 0L) throttle()
                        if (durationUs > 0) {
                            onProgress((timestamp.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        // Meilensteine 10/25/50/75 %: die Bitmaske sorgt
                        // dafuer, dass jeder Schritt genau einmal kommt.
                        if (durationUs > 0L) {
                            val percent = (timestamp * 100 / durationUs).toInt()
                            var step = 0
                            while (step < MILESTONES.size) {
                                val mask = 1 shl step
                                if (percent >= MILESTONES[step] && milestones and mask == 0) {
                                    milestones = milestones or mask
                                    Log.i(
                                        "Video",
                                        "Fortschritt ${MILESTONES[step]} %" +
                                            " · Medien ${clock(timestamp)}" +
                                            " · Wand ${seconds(startedNs)} s" +
                                            " · ${speed(timestamp, startedNs)}x Echtzeit" +
                                            " · Rest ca. ${remaining(timestamp, startedNs, durationUs)}"
                                    )
                                }
                                step++
                            }
                        }
                    }
                } else {
                    inputStalls++
                }
            }

            val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
            if (outputIndex >= 0) {
                // true = auf die Encoder-Surface rendern, kein Byte im Heap
                decoder.releaseOutputBuffer(outputIndex, true)
                rendered++
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                tryAgainLater++
            }

            // Encoder entleeren, sonst laeuft die Surface-Queue voll und
            // der Decoder blockiert dauerhaft.
            drainEncoder(encoder, session, encoderStats, untilEos = false, isCancelled = isCancelled)
        }

        encoder.signalEndOfInputStream()
        drainEncoder(encoder, session, encoderStats, untilEos = true, isCancelled = isCancelled)
        onProgress(1f)
        Log.i(
            "Video",
            "Fortschritt 100 % · Medien ${clock(lastTimestampUs)}" +
                " · Wand ${seconds(startedNs)} s · ${speed(lastTimestampUs, startedNs)}x Echtzeit" +
                " · Rest ca. 0 s"
        )
        Log.i(
            "Video",
            "Encoder-Ausgabe: ${encoderStats.buffers} Puffer · ${encoderStats.configBuffers} Konfig-Puffer" +
                " · ${mb(encoderStats.bytes)} kodiert" +
                " · PTS ${encoderStats.firstPtsUs / 1000} ms bis ${encoderStats.lastPtsUs / 1000} ms"
        )
        Log.i(
            "Video",
            "Video verdichtet: $samples Samples gelesen · $rendered Decoder-Frames gerendert" +
                " · ${mb(bytesIn)} gelesen" +
                (if (samples > 0) {
                    " · Sample ${smallest / 1024} KiB bis ${largest / 1024} KiB"
                } else "") +
                " · $inputStalls Stalls (Eingabe)" +
                " · $tryAgainLater Wartezeiten (Ausgabe)" +
                " · Medien ${clock(lastTimestampUs)} · Wand ${seconds(startedNs)} s" +
                " · ${speed(lastTimestampUs, startedNs)}x Echtzeit"
        )
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        session: MuxerSession,
        stats: EncoderStats,
        untilEos: Boolean,
        isCancelled: () -> Boolean = { false }
    ) {
        val info = MediaCodec.BufferInfo()
        // Der Abschluss war bisher eine offene Schleife: liefert der
        // Encoder kein Ende, pollte sie mit 10 ms weiter, ohne dass der
        // Abbrechen-Knopf wirkte. Danach bleibt der Dienst an einer Datei
        // haengen, samt Wake-Lock.
        val deadline = System.nanoTime() + EOS_DEADLINE_NS
        while (true) {
            if (isCancelled()) throw JobCancelledException()
            val index = encoder.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    if (System.nanoTime() > deadline) {
                        throw TranscodeException("Encoder meldet kein Ende (${EOS_DEADLINE_NS / 1_000_000_000} s)")
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = encoder.outputFormat
                    Log.d(
                        "Video",
                        "Encoder-Format: ${outputFormat.getString(MediaFormat.KEY_MIME)}" +
                            " · Profil ${formatValue(outputFormat, MediaFormat.KEY_PROFILE)}" +
                            " · Level ${formatValue(outputFormat, MediaFormat.KEY_LEVEL)}"
                    )
                    session.addVideoTrack(outputFormat)
                }
                index >= 0 -> {
                    stats.buffers++
                    val buffer = encoder.getOutputBuffer(index)
                    if (info.size > 0) {
                        stats.bytes += info.size.toLong()
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            stats.configBuffers++
                        }
                    }
                    if (buffer != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        // PTS vor dem Muxen abgreifen: writeVideo setzt sie
                        // auf den Nullpunkt der Datei zurueck.
                        if (stats.firstPtsUs < 0) stats.firstPtsUs = info.presentationTimeUs
                        stats.lastPtsUs = info.presentationTimeUs
                        session.writeVideo(buffer, info)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Audio
    // ------------------------------------------------------------------

    private fun writeAudio(
        uri: Uri,
        trackIndex: Int,
        session: MuxerSession,
        isCancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime == null) {
                Log.w("Video", "Tonspur ohne MIME-Typ - wird nicht gemuxt")
                return
            }
            Log.d(
                "Video",
                "Tonspur-Quelle: $mime · ${formatValue(format, MediaFormat.KEY_SAMPLE_RATE)} Hz" +
                    " · ${formatValue(format, MediaFormat.KEY_CHANNEL_COUNT)} Kanaele" +
                    " · maxInputSize ${formatValue(format, MediaFormat.KEY_MAX_INPUT_SIZE)}" +
                    " · Spur $trackIndex"
            )
            if (mime == AUDIO_AAC) {
                Log.d("Video", "Tonspur: $mime wird durchgereicht")
                passthroughAudio(extractor, format, session, isCancelled)
            } else {
                Log.d("Video", "Tonspur: $mime wird nach AAC LC neu kodiert")
                transcodeAudio(extractor, format, mime, session, isCancelled)
            }
        } finally {
            extractor.release()
        }
    }

    /** AAC bleibt AAC: Samples werden unveraendert in den Muxer kopiert. */
    private fun passthroughAudio(
        extractor: MediaExtractor,
        format: MediaFormat,
        session: MuxerSession,
        isCancelled: () -> Boolean
    ) {
        val track = session.addAudioTrack(format)
        val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(AUDIO_BUFFER)
        } else AUDIO_BUFFER
        val buffer = ByteBuffer.allocateDirect(maxSize)
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        var bytes = 0L
        var firstPtsUs = -1L
        var lastPtsUs = -1L

        while (true) {
            if (isCancelled()) throw JobCancelledException()
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            val pts = extractor.sampleTime
            info.set(0, size, pts, 0)
            session.writeAudio(track, buffer, info)
            samples++
            bytes += size
            if (firstPtsUs < 0) firstPtsUs = pts
            lastPtsUs = pts
            extractor.advance()
        }
        Log.i(
            "Video",
            "Ton durchgereicht: $samples Samples · ${mb(bytes)}" +
                " · ${if (samples > 0) bytes / samples else 0} B im Schnitt" +
                " · PTS ${firstPtsUs.coerceAtLeast(0) / 1000} ms bis" +
                " ${lastPtsUs.coerceAtLeast(0) / 1000} ms"
        )
    }

    /** Opus/Vorbis/PCM -> AAC LC, damit der MP4-Container passt. */
    private fun transcodeAudio(
        extractor: MediaExtractor,
        sourceFormat: MediaFormat,
        sourceMime: String,
        session: MuxerSession,
        isCancelled: () -> Boolean
    ) {
        val sampleRate = if (sourceFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 44_100
        val channels = if (sourceFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 2

        val decoder = MediaCodec.createDecoderByType(sourceMime)
        val encoder = MediaCodec.createEncoderByType(AUDIO_AAC)
        try {
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()
            Log.d(
                "Video",
                "Ton-Decoder: ${decoder.name} · $sourceMime" +
                    " · ${formatValue(sourceFormat, MediaFormat.KEY_SAMPLE_RATE)} Hz" +
                    " · ${formatValue(sourceFormat, MediaFormat.KEY_CHANNEL_COUNT)} Kanaele"
            )

            val encoderFormat = MediaFormat.createAudioFormat(
                AUDIO_AAC,
                sampleRate,
                channels.coerceIn(1, 2)
            )
            encoderFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            Log.i(
                "Video",
                "Ton-Encoder: ${encoder.name} · ${hardwareLabel(encoder)} · $AUDIO_AAC" +
                    " · ${sampleRate} Hz · ${channels.coerceIn(1, 2)} Kanaele · 128 kbit/s" +
                    " · Quelle $sourceMime"
            )

            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            var decoderEos = false
            var encoderEosQueued = false
            var encoderTrack = -1
            var encoderDone = false
            var droppedFrames = 0L
            var samplesOut = 0L
            var bytesOut = 0L
            var firstPtsUs = -1L
            var lastPtsUs = -1L

            while (!encoderDone) {
                if (isCancelled()) throw JobCancelledException()

                if (!decoderEos) {
                    val inputIndex = decoder.dequeueInputBuffer(5_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        val size = if (inputBuffer != null) extractor.readSampleData(inputBuffer, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            decoderEos = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, 5_000)
                if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && decoderInfo.size > 0 &&
                        decoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        outputBuffer.position(decoderInfo.offset)
                        outputBuffer.limit(decoderInfo.offset + decoderInfo.size)
                        var queued = false
                        var waitStarted = 0L
                        while (!queued) {
                            if (isCancelled()) throw JobCancelledException()
                            val encoderInput = encoder.dequeueInputBuffer(5_000)
                            if (encoderInput >= 0) {
                                val target = encoder.getInputBuffer(encoderInput)
                                if (target != null) {
                                    target.clear()
                                    target.put(outputBuffer)
                                    encoder.queueInputBuffer(
                                        encoderInput,
                                        0,
                                        decoderInfo.size,
                                        decoderInfo.presentationTimeUs,
                                        0
                                    )
                                    queued = true
                                } else {
                                    // Puffer nicht belegbar: Index zurueckgeben,
                                    // sonst fehlt er dem Encoder dauerhaft.
                                    encoder.queueInputBuffer(encoderInput, 0, 0, 0, 0)
                                    droppedFrames++
                                    queued = true
                                }
                            } else if (waitStarted == 0L) {
                                waitStarted = System.nanoTime()
                            } else if (System.nanoTime() - waitStarted > AUDIO_STALL_NS) {
                                throw TranscodeException("Audio-Encoder nimmt keine Daten mehr an")
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 &&
                        !encoderEosQueued
                    ) {
                        // Ein Encoder mit Puffereingang kennt kein
                        // signalEndOfInputStream - das gilt nur fuer
                        // Surface-Eingang. Ohne EOS-Puffer in der Warteschlange
                        // liefert er nie ein Ende, und die Schleife unten
                        // dreht endlos.
                        var signed = false
                        val waitStarted = System.nanoTime()
                        while (!signed) {
                            if (isCancelled()) throw JobCancelledException()
                            val eosIndex = encoder.dequeueInputBuffer(5_000)
                            if (eosIndex >= 0) {
                                encoder.queueInputBuffer(
                                    eosIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                signed = true
                            } else if (System.nanoTime() - waitStarted > AUDIO_STALL_NS) {
                                throw TranscodeException("Audio-Encoder nahm kein Ende an")
                            }
                        }
                        encoderEosQueued = true
                    }
                }

                val encodedIndex = encoder.dequeueOutputBuffer(encoderInfo, 5_000)
                when {
                    encodedIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        encoderTrack = session.addAudioTrack(encoder.outputFormat)
                    }
                    encodedIndex >= 0 -> {
                        val encoded = encoder.getOutputBuffer(encodedIndex)
                        if (encoded != null && encoderInfo.size > 0 &&
                            encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            encoded.position(encoderInfo.offset)
                            encoded.limit(encoderInfo.offset + encoderInfo.size)
                            if (encoderTrack < 0) {
                                Log.w("Video", "Audiospur ohne Muxer-Track - writeSampleData folgt trotzdem")
                            }
                            // PTS vor writeAudio lesen: der Muxer setzt sie
                            // auf den Nullpunkt der Datei zurueck.
                            val pts = encoderInfo.presentationTimeUs
                            session.writeAudio(encoderTrack, encoded, encoderInfo)
                            samplesOut++
                            bytesOut += encoderInfo.size
                            if (firstPtsUs < 0) firstPtsUs = pts
                            lastPtsUs = pts
                        }
                        encoder.releaseOutputBuffer(encodedIndex, false)
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                        }
                    }
                }
            }
            Log.i(
                "Video",
                "Ton neu kodiert: $samplesOut Samples · ${mb(bytesOut)}" +
                    (if (droppedFrames > 0) " · $droppedFrames Rahmen verworfen" else "") +
                    " · ${if (samplesOut > 0) bytesOut / samplesOut else 0} B im Schnitt" +
                    " · PTS ${firstPtsUs.coerceAtLeast(0) / 1000} ms bis" +
                    " ${lastPtsUs.coerceAtLeast(0) / 1000} ms"
            )
        } finally {
            quiet("Audio-Decoder.stop") { decoder.stop() }
            quiet("Audio-Decoder.release") { decoder.release() }
            quiet("Audio-Encoder.stop") { encoder.stop() }
            quiet("Audio-Encoder.release") { encoder.release() }
        }
    }

    // ------------------------------------------------------------------
    // Encoder
    // ------------------------------------------------------------------

    private class StartedEncoder(val codec: MediaCodec, val surface: Surface, val plan: VideoPlan)

    private fun startEncoder(plan: VideoPlan): StartedEncoder {
        val candidates = encoderCandidates(plan)
        var lastError: Throwable? = null
        for (mime in candidates) {
            var codec: MediaCodec? = null
            var surface: Surface? = null
            try {
                Log.d(
                    "Video",
                    "Encoder-Versuch $mime: Grenzen pruefen" +
                        " (${plan.width}x${plan.height} @ ${plan.bitrate / 1000} kbit/s angefragt)"
                )
                val fitted = fitToEncoder(plan, mime)
                if (fitted != plan) {
                    Log.d(
                        "Video",
                        "Encoder-Grenzen $mime: ${plan.width}x${plan.height} @ ${plan.bitrate / 1000} kbit/s" +
                            " -> ${fitted.width}x${fitted.height} @ ${fitted.bitrate / 1000} kbit/s"
                    )
                }
                codec = MediaCodec.createEncoderByType(mime)
                val format = MediaFormat.createVideoFormat(mime, fitted.width, fitted.height).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, fitted.bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fitted.frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                // Reihenfolge ist Pflicht: configure() legt den Codec auf
                // Surface-Eingabe fest, erst danach gibt es die Surface.
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = codec.createInputSurface()
                codec.start()
                Log.i(
                    "Video",
                    "Encoder gewaehlt: ${codec.name} · $mime · ${fitted.width}x${fitted.height}" +
                        " @ ${fitted.bitrate / 1000} kbit/s · ${fitted.frameRate} fps" +
                        " · ${hardwareLabel(codec)} · maxInstances=${maxInstances(codec, mime)}" +
                        if (fitted != plan) " · an Encoder-Grenzen angepasst" else ""
                )
                return StartedEncoder(codec, surface, fitted)
            } catch (t: Throwable) {
                lastError = t
                Log.w("Video", "Encoder $mime nicht nutzbar - naechster Versuch", t)
                runCatching { surface?.release() }
                runCatching { codec?.release() }
            }
        }
        Log.e("Video", "Kein Video-Encoder verfuegbar (versucht: $candidates)", lastError)
        throw TranscodeException(
            "Kein Video-Encoder verfuegbar (${lastError?.message ?: "unbekannt"})"
        )
    }

    /**
     * Reihenfolge der MIME-Typen, die der Encoder-Versuch durchprobiert.
     * HEVC ist auf alten Geraeten nicht immer vorhanden, H.264 schon.
     */
    private fun encoderCandidates(plan: VideoPlan): List<String> =
        if (plan.codecMime == PresetEngine.MIME_AVC) {
            listOf(PresetEngine.MIME_AVC)
        } else {
            listOf(plan.codecMime, PresetEngine.MIME_AVC)
        }

    /** Grobe Einordnung des Codecs fuer das Protokoll. */
    @Suppress("DEPRECATION")
    private fun hardwareLabel(codec: MediaCodec): String =
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            if (codec.codecInfo.isHardwareAccelerated) "Hardware" else "Software"
        } else "Codec-Art unbekannt"

    /** Wie viele Instanzen der Encoder gleichzeitig erlaubt; -1 wenn nicht abrufbar. */
    @Suppress("DEPRECATION")
    private fun maxInstances(codec: MediaCodec, mime: String): Int = runCatching {
        codec.codecInfo.getCapabilitiesForType(mime).maxSupportedInstances
    }.getOrDefault(-1)

    /** Zahl aus dem Format als Text; "-" wenn der Codec sie nicht meldet. */
    private fun formatValue(format: MediaFormat, key: String): String =
        if (format.containsKey(key)) format.getInteger(key).toString() else "-"

    /** Bytes in MB mit einer Dezimalstelle. */
    private fun mb(bytes: Long): String =
        String.format(Locale.US, "%.1f MB", bytes.coerceAtLeast(0L) / 1024.0 / 1024.0)

    /** Medienzeit als mm:ss. */
    private fun clock(us: Long): String {
        val totalSeconds = us.coerceAtLeast(0L) / 1000L
        return String.format(
            Locale.US,
            "%d:%02d",
            totalSeconds / 60,
            totalSeconds % 60
        )
    }

    /** Wandzeit in Sekunden mit einer Dezimalstelle. */
    private fun seconds(startedNs: Long): String =
        String.format(Locale.US, "%.1f", (System.nanoTime() - startedNs) / 1_000_000_000.0)

    /** Verhaeltnis Medienzeit zu Wandzeit. */
    private fun speed(mediaUs: Long, startedNs: Long): String {
        val wallSeconds = (System.nanoTime() - startedNs) / 1_000_000_000.0
        if (wallSeconds <= 0.0 || mediaUs <= 0L) return "0.0"
        return String.format(Locale.US, "%.1f", (mediaUs / 1_000_000.0) / wallSeconds)
    }

    /** Restzeit aus dem bisherigen Tempo. */
    private fun remaining(mediaUs: Long, startedNs: Long, durationUs: Long): String {
        if (mediaUs <= 0L || durationUs <= mediaUs) return "0 s"
        val wallSeconds = (System.nanoTime() - startedNs) / 1_000_000_000.0
        if (wallSeconds <= 0.0) return "-"
        val restSeconds = (durationUs - mediaUs) / 1_000_000.0 * wallSeconds / (mediaUs / 1_000_000.0)
        return String.format(Locale.US, "%.0f s", restSeconds)
    }

    /** Zaehler der Encoder-Ausgabe; wird zwischen den Drains weitergereicht. */
    private class EncoderStats {
        var buffers = 0L
        var configBuffers = 0L
        var bytes = 0L
        var firstPtsUs = -1L
        var lastPtsUs = -1L
    }

    /**
     * Fuehrt einen Aufraeumschritt aus und protokolliert einen Fehler,
     * ohne ihn weiterzureichen - das Verhalten bleibt wie bisher.
     */
    private fun quiet(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w("Video", "$label fehlgeschlagen", t)
        }
    }

    /**
     * Nur 0/90/180/270 sind erlaubt - MediaMuxer wirft bei allem anderen.
     */
    private fun normalizeRotation(value: Int): Int {
        val wrapped = ((value % 360) + 360) % 360
        return when (wrapped) {
            in 45..134 -> 90
            in 135..224 -> 180
            in 225..314 -> 270
            else -> 0
        }
    }

    /** Passt Groesse und Bitrate an das, was der Encoder wirklich kann. */
    private fun fitToEncoder(plan: VideoPlan, mime: String): VideoPlan {
        val capabilities = encoderCapabilities(mime) ?: return plan.copy(codecMime = mime)
        val widthAlignment = capabilities.widthAlignment.coerceAtLeast(1)
        val heightAlignment = capabilities.heightAlignment.coerceAtLeast(1)

        var width = (plan.width / widthAlignment) * widthAlignment
        var height = (plan.height / heightAlignment) * heightAlignment
        var guard = 0
        while (!capabilities.isSizeSupported(width, height) && guard++ < 16) {
            val nextWidth = (width * 3 / 4 / widthAlignment) * widthAlignment
            val nextHeight = (height * 3 / 4 / heightAlignment) * heightAlignment
            if (nextWidth < 16 || nextHeight < 16) break
            width = nextWidth
            height = nextHeight
        }
        if (!capabilities.isSizeSupported(width, height)) {
            width = clamp(capabilities.supportedWidths, width) / widthAlignment * widthAlignment
            height = clamp(capabilities.supportedHeights, height) / heightAlignment * heightAlignment
        }
        return plan.copy(
            codecMime = mime,
            width = width.coerceAtLeast(2),
            height = height.coerceAtLeast(2),
            bitrate = clamp(capabilities.bitrateRange, plan.bitrate)
        )
    }

    private fun clamp(range: Range<Int>, value: Int): Int = value.coerceIn(range.lower, range.upper)

    /**
     * Faehigkeiten des Encoders, den [startEncoder] auch wirklich nimmt.
     * Ein Hardware-Encoder wird vorgezogen: ein Software-Encoder schafft
     * bei Stapelverarbeitung nur einen Bruchteil des Durchsatzes.
     */
    private fun encoderCapabilities(mime: String): MediaCodecInfo.VideoCapabilities? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val matching = list.codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        val ordered = if (android.os.Build.VERSION.SDK_INT >= 29) {
            matching.sortedByDescending { if (it.isHardwareAccelerated) 1 else 0 }
        } else {
            matching
        }
        for (info in ordered) {
            val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
            if (caps != null) return caps
        }
        return null
    }

    // ------------------------------------------------------------------
    // Muxer
    // ------------------------------------------------------------------

    /**
     * Haelt den Muxer und serialisiert beide Schreiber. Video und Audio
     * laufen in getrennten Threads, damit die Samples zeitlich sortiert
     * ankommen - der MP4-Writer verlangt das.
     */
    private class MuxerSession(path: String, orientation: Int, expectedTracks: Int) {
        private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val lock = ReentrantLock()
        private val ready = lock.newCondition()
        private var pendingTracks = expectedTracks
        private var started = false
        private var released = false
        private var failure: Throwable? = null
        private var videoTrack = -1
        private var audioTrack = -1
        private var videoBase = Long.MIN_VALUE
        private var audioBase = Long.MIN_VALUE
        private var lastVideoUs = -1L
        private var lastAudioUs = -1L
        private var videoFrames = 0
        private var audioBytes = 0L

        init {
            if (orientation != 0) muxer.setOrientationHint(orientation)
        }

        fun fail(error: Throwable) {
            lock.withLock {
                if (failure == null) failure = error
                ready.signalAll()
            }
        }

        fun rethrowFailure() {
            lock.withLock {
                val error = failure ?: return
                throw if (error is TranscodeException) error else TranscodeException(
                    error.message ?: "Tonspur fehlgeschlagen"
                )
            }
        }

        fun addVideoTrack(format: MediaFormat): Int = lock.withLock {
            if (videoTrack < 0) {
                videoTrack = muxer.addTrack(format)
                startIfCompleteLocked()
            }
            videoTrack
        }

        fun addAudioTrack(format: MediaFormat): Int = lock.withLock {
            if (audioTrack < 0) {
                audioTrack = muxer.addTrack(format)
                startIfCompleteLocked()
            }
            audioTrack
        }

        private fun startIfCompleteLocked() {
            pendingTracks--
            if (pendingTracks <= 0 && !started) {
                muxer.start()
                started = true
                Log.d(
                    "Video",
                    "Muxer gestartet (Video-Track $videoTrack, Audio-Track $audioTrack)" +
                        " - ab hier werden Samples geschrieben"
                )
                ready.signalAll()
            }
        }

        private fun awaitStartLocked() {
            var waitedMs = 0L
            while (!started) {
                failure?.let { throw it }
                if (released) throw TranscodeException("Muxer bereits geschlossen")
                ready.await(50, TimeUnit.MILLISECONDS)
                waitedMs += 50
                if (waitedMs == AWAIT_WARN_MS) {
                    Log.w(
                        "Video",
                        "Muxer wartet seit ${AWAIT_WARN_MS / 1000} s auf eine fehlende Spur" +
                            " (offen: $pendingTracks)"
                    )
                }
            }
        }

        fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            lock.withLock {
                awaitStartLocked()
                if (info.size <= 0) return
                if (videoBase == Long.MIN_VALUE) videoBase = info.presentationTimeUs
                var timestamp = info.presentationTimeUs - videoBase
                if (timestamp < 0) timestamp = 0
                if (timestamp <= lastVideoUs) timestamp = lastVideoUs + 1
                lastVideoUs = timestamp
                info.presentationTimeUs = timestamp
                muxer.writeSampleData(videoTrack, buffer, info)
                videoFrames++
            }
        }

        fun writeAudio(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            lock.withLock {
                awaitStartLocked()
                if (info.size <= 0) return
                if (audioBase == Long.MIN_VALUE) audioBase = info.presentationTimeUs
                var timestamp = info.presentationTimeUs - audioBase
                if (timestamp < 0) timestamp = 0
                if (timestamp <= lastAudioUs) timestamp = lastAudioUs + 1
                lastAudioUs = timestamp
                info.presentationTimeUs = timestamp
                muxer.writeSampleData(track, buffer, info)
                audioBytes += info.size.toLong()
            }
        }

        /** Anzahl der tatsaechlich geschriebenen Videoframes. */
        fun videoFrameCount(): Int = lock.withLock { videoFrames }

        /** Summe der geschriebenen Audiobytes. */
        fun audioByteCount(): Long = lock.withLock { audioBytes }

        fun release() {
            lock.withLock {
                if (released) return
                released = true
                ready.signalAll()
                if (!started) {
                    Log.w("Video", "Muxer nie gestartet - Ausgabedatei bleibt ohne Spuren")
                }
                try {
                    if (started) muxer.stop()
                } catch (t: Throwable) {
                    Log.w("Video", "Muxer.stop fehlgeschlagen", t)
                }
                try {
                    muxer.release()
                } catch (t: Throwable) {
                    Log.w("Video", "Muxer.release fehlgeschlagen", t)
                }
            }
        }

        private companion object {
            /** Ab dieser Wartezeit gilt eine Muxer-Spur als haengend. */
            const val AWAIT_WARN_MS = 5_000L
        }
    }

    companion object {
        private const val AUDIO_AAC = "audio/mp4a-latm"
        private const val AUDIO_BUFFER = 64 * 1024

        /** Fortschrittsstufen, die genau eine Protokollzeile bekommen. */
        private val MILESTONES = listOf(10, 25, 50, 75)

        /**
         * Samples zwischen zwei Bremsgelegenheiten. Bei 30 fps sind das
         * knapp 0,8 Sekunden Video - haeufig genug, um Speicherdruck
         * zeitnah zu bemerken, selten genug, um nicht selbst zu bremsen.
         */
        private const val THROTTLE_EVERY = 24L
    }
}
