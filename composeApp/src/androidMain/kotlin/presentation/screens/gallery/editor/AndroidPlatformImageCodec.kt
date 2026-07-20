package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

class AndroidPlatformImageCodec : PlatformImageCodec {
    override suspend fun decode(bytes: ByteArray, maxDimension: Int): DecodedImage =
        withContext(Dispatchers.IO) {
            require(maxDimension > 0) { "maxDimension must be positive" }
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val rawWidth = bounds.outWidth
                val rawHeight = bounds.outHeight
                if (rawWidth <= 0 || rawHeight <= 0 || !bytes.hasSupportedImageSignature()) {
                    throw PrintImageFailure.UnsupportedFormat()
                }
                if (rawWidth.toLong() * rawHeight > PrintImageLimits.MAX_PIXELS) {
                    throw PrintImageFailure.ImageDimensionsTooLarge
                }

                val orientation = readOrientation(bytes)
                val swapsDimensions = orientation in SWAPPED_ORIENTATIONS
                val originalSize = ImageSize(
                    width = if (swapsDimensions) rawHeight else rawWidth,
                    height = if (swapsDimensions) rawWidth else rawHeight,
                )
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = calculateBoundedSampleSize(rawWidth, rawHeight, maxDimension)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                    ?: throw PrintImageFailure.UnsupportedFormat()
                val oriented = applyOrientation(decoded, orientation)
                val scaled = scaleDown(oriented, maxDimension)

                DecodedImage(
                    bitmap = scaled.asImageBitmap(),
                    originalSize = originalSize,
                )
            } catch (failure: PrintImageFailure) {
                throw failure
            } catch (cause: Throwable) {
                throw PrintImageFailure.DecodeFailed(cause)
            }
        }

    override suspend fun encodePng(bitmap: ImageBitmap): ByteArray =
        withContext(Dispatchers.IO) {
            try {
                ByteArrayOutputStream().use { output ->
                    if (!bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw PrintImageFailure.EncodeFailed()
                    }
                    output.toByteArray()
                }
            } catch (failure: PrintImageFailure) {
                throw failure
            } catch (cause: Throwable) {
                throw PrintImageFailure.EncodeFailed(cause)
            }
        }

    private fun readOrientation(bytes: ByteArray): Int = runCatching {
        ByteArrayInputStream(bytes).use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
            if (it !== source) source.recycle()
        }
    }

    private fun scaleDown(source: Bitmap, maxDimension: Int): Bitmap {
        val longestEdge = max(source.width, source.height)
        if (longestEdge <= maxDimension) return source
        val scale = maxDimension.toFloat() / longestEdge
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        ).also {
            if (it !== source) source.recycle()
        }
    }

    private fun ByteArray.hasSupportedImageSignature(): Boolean =
        hasPrefix(JPEG_SIGNATURE) ||
                hasPrefix(PNG_SIGNATURE) ||
                hasSupportedHeifBrand()

    private fun ByteArray.hasPrefix(signature: ByteArray): Boolean =
        size >= signature.size && signature.indices.all { this[it] == signature[it] }

    private fun ByteArray.hasSupportedHeifBrand(): Boolean {
        if (size < 12 || copyOfRange(4, 8).decodeToString() != "ftyp") return false
        var offset = 8
        val limit = minOf(size, 64)
        while (offset + 4 <= limit) {
            if (copyOfRange(offset, offset + 4).decodeToString() in HEIF_BRANDS) return true
            offset += 4
        }
        return false
    }

    private companion object {
        val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val HEIF_BRANDS = setOf(
            "heic",
            "heix",
            "hevc",
            "hevx",
            "heim",
            "heis",
            "mif1",
            "msf1",
        )
        val SWAPPED_ORIENTATIONS = setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
    }
}

internal fun calculateBoundedSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
): Int {
    require(width > 0 && height > 0 && maxDimension > 0)
    var sampleSize = 1
    while (true) {
        val sampledWidth = (width.toLong() + sampleSize - 1) / sampleSize
        val sampledHeight = (height.toLong() + sampleSize - 1) / sampleSize
        val withinPixelBudget = sampledWidth * sampledHeight <=
                PrintImageLimits.MAX_INTERMEDIATE_DECODE_PIXELS
        val withinDimensionBudget = max(sampledWidth, sampledHeight) <= maxDimension.toLong() * 2
        if (withinPixelBudget && withinDimensionBudget) return sampleSize
        sampleSize *= 2
    }
}
