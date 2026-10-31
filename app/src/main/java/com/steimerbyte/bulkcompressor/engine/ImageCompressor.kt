package com.steimerbyte.bulkcompressor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.steimerbyte.bulkcompressor.log.Log
import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.model.ImageFormat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Bildkompression mit kleinem Speicherbedarf.
 *
 * Es wird nie das volle Original dekodiert: erst werden nur die
 * Abmessungen gelesen, dann bestimmt [chooseSampleSize] einen
 * Dekodier-Faktor, der das Bitmap in ein festes Budget zwingt
 * (maxMemory/6). Auf einem Geraet mit 96 MB Heap sind das rund 16 MB -
 * genug fuer ein 4K-Bild, ohne dass die App abstuerzt.
 */
class ImageCompressor(private val context: Context) {

    data class Format(val kind: String, val compress: Bitmap.CompressFormat, val extension: String, val mime: String)

    data class Result(val width: Int, val height: Int, val format: Format)

    fun resolveFormat(settings: CompressSettings, sourceExtension: String): Format {
        val target = when (settings.imageFormat) {
            ImageFormat.JPEG -> "jpeg"
            ImageFormat.WEBP -> "webp"
            ImageFormat.KEEP -> when (sourceExtension) {
                "png" -> "png"
                "webp" -> "webp"
                // HEIC/HEIF/BMP/GIF haben keinen Encoder auf Android:
                // sie werden als JPEG geschrieben.
                else -> "jpeg"
            }
        }
        return when (target) {
            "png" -> Format("png", Bitmap.CompressFormat.PNG, "png", "image/png")
            "webp" -> Format("webp", webpFormat(), "webp", "image/webp")
            else -> Format("jpeg", Bitmap.CompressFormat.JPEG, "jpg", "image/jpeg")
        }
    }

    /** Kann die Datei unter ihrem eigenen Namen ersetzt werden? */
    fun isInPlaceCompatible(sourceExtension: String, format: Format): Boolean = when (format.kind) {
        "jpeg" -> sourceExtension == "jpg" || sourceExtension == "jpeg"
        else -> sourceExtension == format.kind
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    fun compress(source: Uri, sourceExtension: String, target: File, settings: CompressSettings): Result {
        val startedAt = System.currentTimeMillis()
        val name = source.lastPathSegment ?: source.toString()
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(source).use { BitmapFactory.decodeStream(it, null, bounds) }
            val srcWidth = bounds.outWidth
            val srcHeight = bounds.outHeight
            Log.d(
                "Image",
                "$name · Quelle ${srcWidth}x${srcHeight} · Format ${settings.imageFormat.name}" +
                    " · Kurzkante " +
                    if (settings.imageShortEdge <= 0) "original" else "${settings.imageShortEdge}px"
            )
            if (srcWidth <= 0 || srcHeight <= 0) throw IOException("Bild nicht lesbar")

            val desiredShortEdge =
                if (settings.imageShortEdge <= 0) minOf(srcWidth, srcHeight) else settings.imageShortEdge
            val sample = chooseSampleSize(srcWidth, srcHeight, desiredShortEdge)
            val budgetMb = Runtime.getRuntime().maxMemory() / 6 / (1024 * 1024)
            Log.d(
                "Image",
                "$name · Ziel-Kurzkante ${desiredShortEdge}px · inSampleSize=$sample" +
                    " · Speicherbudget $budgetMb MB (maxMemory/6)"
            )

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decoded = try {
                openStream(source).use { BitmapFactory.decodeStream(it, null, options) }
            } catch (e: OutOfMemoryError) {
                throw IOException("Nicht genug Arbeitsspeicher fuer dieses Bild")
            } ?: throw IOException("Bild nicht dekodierbar")

            val decodedWidth = decoded.width
            val decodedHeight = decoded.height
            val decodedBytes = decodedWidth.toLong() * decodedHeight * 4
            Log.d(
                "Image",
                "$name · dekodiert ${decodedWidth}x$decodedHeight nach inSampleSize=$sample" +
                    " · Bitmap ${mb(decodedBytes)} im Speicher"
            )

            var current: Bitmap = decoded
            try {
                val orientation = readOrientation(source)
                val degrees = OrientationUtil.rotationDegrees(orientation)
                val flipped = OrientationUtil.flipHorizontal(orientation)
                val orientationText = if (degrees == 0 && !flipped) {
                    "keine Drehung noetig"
                } else {
                    "Drehung um $degrees Grad" + if (flipped) " · zusaetzlich gespiegelt" else ""
                }
                Log.d("Image", "$name · EXIF-Orientierung $orientation · $orientationText")

                current = rotate(current, orientation)
                current = downscale(current, desiredShortEdge)

                val outWidth = current.width
                val outHeight = current.height
                val sourceArea = srcWidth.toLong() * srcHeight
                val areaPercent = if (sourceArea > 0) {
                    outWidth.toLong() * outHeight * 100 / sourceArea
                } else {
                    0L
                }
                Log.d(
                    "Image",
                    "$name · Bild ${decodedWidth}x$decodedHeight -> ${outWidth}x$outHeight" +
                        " (Drehung und Skalierung)" +
                        " · Ziel-Kurzkante ${desiredShortEdge}px" +
                        " · tatsaechliche Kurzkante ${minOf(outWidth, outHeight)}px" +
                        " · Flaeche $areaPercent % der Quelle"
                )

                val format = resolveFormat(settings, sourceExtension)
                FileOutputStream(target).use { out ->
                    if (!current.compress(format.compress, settings.imageQuality, out)) {
                        throw IOException("Bild konnte nicht geschrieben werden")
                    }
                }
                val targetBytes = target.length()
                if (targetBytes <= 0L) {
                    Log.e(
                        "Image",
                        "$name · Zieldatei ist leer (0 B): ${target.absolutePath}" +
                            " · ${format.kind} · q${settings.imageQuality} · inSampleSize=$sample"
                    )
                }
                val sourceBytes = querySourceBytes(source)
                val sizeText = if (sourceBytes > 0L) {
                    "$sourceBytes B -> $targetBytes B" +
                        " · Ersparnis ${(sourceBytes - targetBytes) * 100 / sourceBytes} %"
                } else {
                    "$targetBytes B · Ersparnis unbekannt (Quellgroesse nicht lesbar)"
                }
                Log.i(
                    "Image",
                    "$name · ${srcWidth}x${srcHeight} -> ${current.width}x${current.height}" +
                        " · ${format.kind} · q${settings.imageQuality} · inSampleSize=$sample" +
                        " · $sizeText" +
                        " · ${System.currentTimeMillis() - startedAt} ms"
                )
                return Result(current.width, current.height, format)
            } finally {
                if (!current.isRecycled) current.recycle()
            }
        } catch (t: Throwable) {
            Log.e("Image", "fehlgeschlagen: $name", t)
            throw t
        }
    }

    /**
     * Groesster Zweierfaktor, dessen Ergebnis noch mindestens die
     * Ziel-Kurzkante hat - danach zusaetzlich so weit, bis das Bitmap
     * in das Speicherbudget passt.
     */
    private fun chooseSampleSize(width: Int, height: Int, desiredShortEdge: Int): Int {
        val shortEdge = minOf(width, height)
        var sample = 1
        while (sample < 16 && sample * 2 * desiredShortEdge <= shortEdge) {
            sample *= 2
        }

        val budget = Runtime.getRuntime().maxMemory() / 6
        while (sample < 32 && (width / sample).toLong() * (height / sample) * 4 > budget) {
            sample *= 2
        }
        return sample
    }

    private fun rotate(source: Bitmap, orientation: Int): Bitmap {
        val degrees = OrientationUtil.rotationDegrees(orientation)
        val flip = OrientationUtil.flipHorizontal(orientation)
        if (degrees == 0 && !flip) return source
        val matrix = Matrix()
        if (flip) matrix.postScale(-1f, 1f)
        if (degrees != 0) matrix.postRotate(degrees.toFloat())
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    private fun downscale(source: Bitmap, maxShortEdge: Int): Bitmap {
        val shortEdge = minOf(source.width, source.height)
        if (maxShortEdge <= 0 || maxShortEdge >= shortEdge) return source
        val factor = maxShortEdge.toDouble() / shortEdge
        val width = PresetEngine.evenDown(Math.round(source.width * factor).toInt())
        val height = PresetEngine.evenDown(Math.round(source.height * factor).toInt())
        if (width <= 0 || height <= 0) return source
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun readOrientation(source: Uri): Int = runCatching {
        val descriptor = context.contentResolver.openFileDescriptor(source, "r")
        if (descriptor == null) {
            OrientationUtil.NORMAL
        } else {
            descriptor.use {
                ExifInterface(it.fileDescriptor)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, OrientationUtil.NORMAL)
            }
        }
    }.getOrDefault(OrientationUtil.NORMAL)

    private fun openStream(source: Uri): InputStream =
        context.contentResolver.openInputStream(source) ?: throw IOException("Quelle nicht lesbar")

    /**
     * Groesse in MB mit einer Nachkommastelle. Ganzzahlig gerechnet statt
     * ueber String.format, damit die Ausgabe unabhaengig von der
     * Geraeteeinstellung immer ein Komma als Trenner hat.
     */
    private fun mb(bytes: Long): String {
        val whole = bytes / (1024 * 1024)
        val tenths = bytes % (1024 * 1024) * 10 / (1024 * 1024)
        return "$whole,$tenths MB"
    }

    /**
     * Quellgroesse laut Provider, nur zum Protokollieren. Liefert 0,
     * wenn der Provider die Spalte nicht kennt - die Zeile nennt die
     * Ersparnis dann "unbekannt" statt eine Zahl zu erfinden.
     */
    private fun querySourceBytes(source: Uri): Long = runCatching {
        context.contentResolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || !cursor.moveToFirst() || cursor.isNull(index)) {
                    0L
                } else {
                    cursor.getLong(index)
                }
            } ?: 0L
    }.getOrDefault(0L)
}
