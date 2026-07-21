package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class AndroidPlatformImageCodec : PlatformImageCodec {
    override suspend fun decode(bytes: ByteArray, maxDimension: Int): DecodedImage = decode(
        bytes,
        DecodeRequest(
            maxDimension = maxDimension,
            maxPixels = PrintImageLimits.MAX_INTERMEDIATE_DECODE_PIXELS,
        ),
    )

    suspend fun decode(bytes: ByteArray, request: DecodeRequest): DecodedImage =
        withContext(Dispatchers.IO) {
            require(request.maxDimension > 0) { "maxDimension must be positive" }
            require(request.maxPixels > 0) { "maxPixels must be positive" }
            try {
                val metadata = inspect(bytes)
                val target = DecodeSizePlanner.plan(metadata.orientedSize, request)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculatePreviewSampleSize(
                        metadata.rawSize,
                        metadata.orientation,
                        target,
                        request,
                    )
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: throw PrintImageFailure.UnsupportedFormat()
                val oriented = applyOrientation(decoded, metadata.orientation)
                val scaled = scaleDown(oriented, target)

                DecodedImage(
                    bitmap = scaled.asImageBitmap(),
                    originalSize = metadata.orientedSize,
                )
            } catch (failure: PrintImageFailure) {
                throw failure
            } catch (cause: Throwable) {
                throw PrintImageFailure.DecodeFailed(cause)
            }
        }

    suspend fun renderCrop(bytes: ByteArray, request: CropRenderRequest): ImageBitmap =
        withContext(Dispatchers.IO) {
            try {
                val format = bytes.detectFormat() ?: throw PrintImageFailure.UnsupportedFormat()
                if (format == ImageFormat.HEIF && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    throw PrintImageFailure.UnsupportedFormat()
                }
                val metadata = inspect(bytes, format)
                if (metadata.orientedSize != request.originalSize) {
                    throw PrintImageFailure.RenderFailed(
                        IllegalArgumentException(
                            "Original size ${request.originalSize} does not match " +
                                    "image size ${metadata.orientedSize}",
                        ),
                    )
                }
                val plan = CropRenderPlanner().plan(request)
                val rendered = if (metadata.format == ImageFormat.HEIF) {
                    renderHeifCrop(bytes, metadata, plan)
                } else {
                    renderRegionCrop(bytes, metadata, plan)
                }
                try {
                    rendered.asImageBitmap()
                } catch (cause: Throwable) {
                    rendered.recycle()
                    throw cause
                }
            } catch (failure: PrintImageFailure) {
                throw failure
            } catch (cause: Throwable) {
                throw PrintImageFailure.RenderFailed(cause)
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

    private fun inspect(bytes: ByteArray, knownFormat: ImageFormat? = null): ImageMetadata {
        val format = knownFormat ?: bytes.detectFormat() ?: throw PrintImageFailure.UnsupportedFormat()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val rawWidth = bounds.outWidth
        val rawHeight = bounds.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) {
            throw PrintImageFailure.UnsupportedFormat()
        }
        if (rawWidth.toLong() * rawHeight > PrintImageLimits.MAX_PIXELS) {
            throw PrintImageFailure.ImageDimensionsTooLarge
        }

        val orientation = readOrientation(bytes)
        val rawSize = ImageSize(rawWidth, rawHeight)
        val orientedSize = if (orientation.swapsDimensions()) {
            ImageSize(rawHeight, rawWidth)
        } else {
            rawSize
        }
        return ImageMetadata(format, rawSize, orientedSize, orientation)
    }

    private fun readOrientation(bytes: ByteArray): Int {
        val orientation = runCatching {
            ByteArrayInputStream(bytes).use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return orientation.takeIf { it in 1..8 } ?: ExifInterface.ORIENTATION_NORMAL
    }

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
        return try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
                if (it !== source) source.recycle()
            }
        } catch (cause: Throwable) {
            source.recycle()
            throw cause
        }
    }

    private fun scaleDown(source: Bitmap, target: ImageSize): Bitmap {
        if (source.width <= target.width && source.height <= target.height) return source
        val scale = min(
            target.width.toDouble() / source.width,
            target.height.toDouble() / source.height,
        ).coerceAtMost(1.0)
        return try {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true,
            ).also {
                if (it !== source) source.recycle()
            }
        } catch (cause: Throwable) {
            source.recycle()
            throw cause
        }
    }

    @Suppress("DEPRECATION")
    private fun renderRegionCrop(
        bytes: ByteArray,
        metadata: ImageMetadata,
        plan: CropRenderPlan,
    ): Bitmap {
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        var regionBitmap: Bitmap? = null
        try {
            val rawToOriented = rawToOriented(metadata.rawSize, metadata.orientation)
            val rawBounds = rawRegionBounds(
                orientedBounds = plan.visibleSourceBounds,
                orientedToRaw = rawToOriented.inverse(),
                rawSize = metadata.rawSize,
            )
            regionBitmap = decoder.decodeRegion(
                rawBounds.toAndroidRect(),
                BitmapFactory.Options().apply {
                    inSampleSize = cropSampleSize(plan.sourceToOutput)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: throw IllegalStateException("Unable to decode image crop")
            val regionToRaw = AffineTransform(
                scaleX = rawBounds.width.toDouble() / regionBitmap.width,
                skewX = 0.0,
                translateX = rawBounds.left.toDouble(),
                skewY = 0.0,
                scaleY = rawBounds.height.toDouble() / regionBitmap.height,
                translateY = rawBounds.top.toDouble(),
            )
            val regionToOutput = plan.sourceToOutput
                .compose(rawToOriented)
                .compose(regionToRaw)
            return drawCrop(regionBitmap, regionToOutput, plan.outputSize)
        } finally {
            regionBitmap?.recycle()
            decoder.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun renderHeifCrop(
        bytes: ByteArray,
        metadata: ImageMetadata,
        plan: CropRenderPlan,
    ): Bitmap {
        val sample = cropSampleSize(plan.sourceToOutput)
        val orientedBounds = plan.visibleSourceBounds.expanded(metadata.orientedSize, 1)
        val targetFullWidth = ceilDiv(metadata.orientedSize.width, sample)
        val targetFullHeight = ceilDiv(metadata.orientedSize.height, sample)
        val targetCrop = Rect(
            floor(orientedBounds.left.toDouble() * targetFullWidth / metadata.orientedSize.width)
                .toInt()
                .coerceIn(0, targetFullWidth - 1),
            floor(orientedBounds.top.toDouble() * targetFullHeight / metadata.orientedSize.height)
                .toInt()
                .coerceIn(0, targetFullHeight - 1),
            ceil(orientedBounds.right.toDouble() * targetFullWidth / metadata.orientedSize.width)
                .toInt()
                .coerceIn(1, targetFullWidth),
            ceil(orientedBounds.bottom.toDouble() * targetFullHeight / metadata.orientedSize.height)
                .toInt()
                .coerceIn(1, targetFullHeight),
        )
        var crop: Bitmap? = null
        try {
            crop = ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
            ) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(targetFullWidth, targetFullHeight)
                decoder.crop = targetCrop
            }
            val orientedLeft = targetCrop.left.toDouble() *
                    metadata.orientedSize.width / targetFullWidth
            val orientedTop = targetCrop.top.toDouble() *
                    metadata.orientedSize.height / targetFullHeight
            val orientedWidth = targetCrop.width().toDouble() *
                    metadata.orientedSize.width / targetFullWidth
            val orientedHeight = targetCrop.height().toDouble() *
                    metadata.orientedSize.height / targetFullHeight
            val cropToOriented = AffineTransform(
                scaleX = orientedWidth / crop.width,
                skewX = 0.0,
                translateX = orientedLeft,
                skewY = 0.0,
                scaleY = orientedHeight / crop.height,
                translateY = orientedTop,
            )
            return drawCrop(crop, plan.sourceToOutput.compose(cropToOriented), plan.outputSize)
        } finally {
            crop?.recycle()
        }
    }

    private fun drawCrop(source: Bitmap, transform: AffineTransform, outputSize: ImageSize): Bitmap {
        val output = Bitmap.createBitmap(
            outputSize.width,
            outputSize.height,
            Bitmap.Config.ARGB_8888,
        )
        try {
            val matrix = Matrix().apply {
                setValues(
                    floatArrayOf(
                        transform.scaleX.toFloat(),
                        transform.skewX.toFloat(),
                        transform.translateX.toFloat(),
                        transform.skewY.toFloat(),
                        transform.scaleY.toFloat(),
                        transform.translateY.toFloat(),
                        0f,
                        0f,
                        1f,
                    ),
                )
            }
            val paint = Paint(
                Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
            )
            Canvas(output).drawBitmap(source, matrix, paint)
            return output
        } catch (cause: Throwable) {
            output.recycle()
            throw cause
        }
    }

    private fun rawRegionBounds(
        orientedBounds: PixelRect,
        orientedToRaw: AffineTransform,
        rawSize: ImageSize,
    ): PixelRect {
        val corners = listOf(
            orientedToRaw.map(orientedBounds.left.toDouble(), orientedBounds.top.toDouble()),
            orientedToRaw.map(orientedBounds.right.toDouble(), orientedBounds.top.toDouble()),
            orientedToRaw.map(orientedBounds.left.toDouble(), orientedBounds.bottom.toDouble()),
            orientedToRaw.map(orientedBounds.right.toDouble(), orientedBounds.bottom.toDouble()),
        )
        return PixelRect(
            left = (floor(corners.minOf { it.x }).toInt() - 1).coerceAtLeast(0),
            top = (floor(corners.minOf { it.y }).toInt() - 1).coerceAtLeast(0),
            right = (ceil(corners.maxOf { it.x }).toInt() + 1).coerceAtMost(rawSize.width),
            bottom = (ceil(corners.maxOf { it.y }).toInt() + 1).coerceAtMost(rawSize.height),
        )
    }

    private fun rawToOriented(rawSize: ImageSize, orientation: Int): AffineTransform =
        when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, rawSize.width.toDouble(), 0.0, 1.0, 0.0)
            3 -> AffineTransform(
                -1.0,
                0.0,
                rawSize.width.toDouble(),
                0.0,
                -1.0,
                rawSize.height.toDouble(),
            )
            4 -> AffineTransform(1.0, 0.0, 0.0, 0.0, -1.0, rawSize.height.toDouble())
            5 -> AffineTransform(0.0, 1.0, 0.0, 1.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, -1.0, rawSize.height.toDouble(), 1.0, 0.0, 0.0)
            7 -> AffineTransform(
                0.0,
                -1.0,
                rawSize.height.toDouble(),
                -1.0,
                0.0,
                rawSize.width.toDouble(),
            )
            8 -> AffineTransform(0.0, 1.0, 0.0, -1.0, 0.0, rawSize.width.toDouble())
            else -> IDENTITY_AFFINE
        }

    private fun ByteArray.detectFormat(): ImageFormat? = when {
        hasPrefix(JPEG_SIGNATURE) -> ImageFormat.JPEG
        hasPrefix(PNG_SIGNATURE) -> ImageFormat.PNG
        hasSupportedHeifBrand() -> ImageFormat.HEIF
        else -> null
    }

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
    }
}

private enum class ImageFormat {
    JPEG,
    PNG,
    HEIF,
}

private data class ImageMetadata(
    val format: ImageFormat,
    val rawSize: ImageSize,
    val orientedSize: ImageSize,
    val orientation: Int,
)

internal fun calculatePreviewSampleSize(
    rawSize: ImageSize,
    orientation: Int,
    target: ImageSize,
    request: DecodeRequest,
): Int {
    var sample = 1
    while (true) {
        val sampledRawWidth = ceilDiv(rawSize.width, sample)
        val sampledRawHeight = ceilDiv(rawSize.height, sample)
        val sampledWidth = if (orientation.swapsDimensions()) sampledRawHeight else sampledRawWidth
        val sampledHeight = if (orientation.swapsDimensions()) sampledRawWidth else sampledRawHeight
        val withinPixels = sampledWidth.toLong() * sampledHeight <= request.maxPixels
        val withinTargetDimension = maxOf(sampledWidth, sampledHeight) <=
                maxOf(target.width, target.height)
        if (withinPixels && withinTargetDimension) return sample
        check(sample <= Int.MAX_VALUE / 2) { "Unable to calculate a bounded sample size" }
        sample *= 2
    }
}

private fun cropSampleSize(transform: AffineTransform): Int {
    val sourceToOutputScale = hypot(transform.scaleX, transform.skewY)
    var sample = 1
    while (sample <= Int.MAX_VALUE / 2 && sample.toDouble() * 2 * sourceToOutputScale <= 1.0) {
        sample *= 2
    }
    return sample
}

private fun Int.swapsDimensions(): Boolean = this in 5..8

private fun ceilDiv(value: Int, divisor: Int): Int =
    ((value.toLong() + divisor - 1) / divisor).toInt()

private fun PixelRect.toAndroidRect(): Rect = Rect(left, top, right, bottom)

private fun PixelRect.expanded(size: ImageSize, pixels: Int): PixelRect = PixelRect(
    left = (left - pixels).coerceAtLeast(0),
    top = (top - pixels).coerceAtLeast(0),
    right = (right + pixels).coerceAtMost(size.width),
    bottom = (bottom + pixels).coerceAtMost(size.height),
)

private fun AffineTransform.compose(right: AffineTransform): AffineTransform = AffineTransform(
    scaleX = scaleX * right.scaleX + skewX * right.skewY,
    skewX = scaleX * right.skewX + skewX * right.scaleY,
    translateX = scaleX * right.translateX + skewX * right.translateY + translateX,
    skewY = skewY * right.scaleX + scaleY * right.skewY,
    scaleY = skewY * right.skewX + scaleY * right.scaleY,
    translateY = skewY * right.translateX + scaleY * right.translateY + translateY,
)

private fun AffineTransform.inverse(): AffineTransform {
    val determinant = scaleX * scaleY - skewX * skewY
    require(determinant != 0.0) { "Transform is not invertible" }
    val inverseScaleX = scaleY / determinant
    val inverseSkewX = -skewX / determinant
    val inverseSkewY = -skewY / determinant
    val inverseScaleY = scaleX / determinant
    return AffineTransform(
        scaleX = inverseScaleX,
        skewX = inverseSkewX,
        translateX = -inverseScaleX * translateX - inverseSkewX * translateY,
        skewY = inverseSkewY,
        scaleY = inverseScaleY,
        translateY = -inverseSkewY * translateX - inverseScaleY * translateY,
    )
}

private val IDENTITY_AFFINE = AffineTransform(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
