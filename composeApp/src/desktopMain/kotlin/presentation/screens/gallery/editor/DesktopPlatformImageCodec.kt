package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

class DesktopPlatformImageCodec : PlatformImageCodec {
    override suspend fun decode(bytes: ByteArray, maxDimension: Int): DecodedImage = decode(
        bytes,
        DecodeRequest(
            maxDimension = maxDimension,
            maxPixels = PrintImageLimits.MAX_INTERMEDIATE_DECODE_PIXELS,
        ),
    )

    suspend fun decode(bytes: ByteArray, request: DecodeRequest): DecodedImage =
        withContext(Dispatchers.Default) {
            require(request.maxDimension > 0) { "maxDimension must be positive" }
            require(request.maxPixels > 0) { "maxPixels must be positive" }
            mapDesktopImageFailure(DesktopFailureOperation.DECODE) {
                val coroutineContext = currentCoroutineContext().also { it.ensureActive() }
                val result = withCodec(bytes) { codec, metadata ->
                    val target = DecodeSizePlanner.plan(metadata.orientedSize, request)
                    coroutineContext.ensureActive()
                    val bitmap = when (
                        planDesktopPreviewRaster(metadata.orientedSize, target)
                    ) {
                        DesktopRasterStrategy.DIRECT_CODEC -> {
                            val rawTarget = if (metadata.origin.swapsWidthHeight()) {
                                ImageSize(target.height, target.width)
                            } else {
                                target
                            }
                            Bitmap().use { decoded ->
                                check(
                                    decoded.allocPixels(
                                        ImageInfo.makeN32Premul(rawTarget.width, rawTarget.height),
                                    ),
                                ) { "Unable to allocate bounded preview bitmap" }
                                codec.readPixels(decoded)
                                orientPreview(decoded, metadata.origin, target)
                            }
                        }
                        DesktopRasterStrategy.BOUNDED_ENCODED_IMAGE ->
                            renderEncodedPreview(bytes, metadata, target)
                        DesktopRasterStrategy.REJECT_UNSAFE_SOURCE ->
                            throw PrintImageFailure.DecodeFailed(
                                unsafeDesktopRasterCause(metadata.orientedSize),
                            )
                    }
                    DecodedImage(
                        bitmap = bitmap,
                        originalSize = metadata.orientedSize,
                    )
                }
                currentCoroutineContext().ensureActive()
                result
            }
        }

    suspend fun renderCrop(bytes: ByteArray, request: CropRenderRequest): ImageBitmap =
        withContext(Dispatchers.Default) {
            mapDesktopImageFailure(DesktopFailureOperation.RENDER) {
                currentCoroutineContext().ensureActive()
                val metadata = withCodec(bytes) { _, metadata -> metadata }
                currentCoroutineContext().ensureActive()
                if (metadata.orientedSize != request.originalSize) {
                    throw PrintImageFailure.RenderFailed(
                        IllegalArgumentException(
                            "Original size ${request.originalSize} does not match " +
                                    "image size ${metadata.orientedSize}",
                        ),
                    )
                }
                if (planDesktopCropRaster(metadata.orientedSize) ==
                    DesktopRasterStrategy.REJECT_UNSAFE_SOURCE
                ) {
                    throw PrintImageFailure.RenderFailed(
                        unsafeDesktopRasterCause(metadata.orientedSize),
                    )
                }
                val plan = CropRenderPlanner().plan(request)
                renderEncodedCrop(bytes, metadata, plan).also {
                    currentCoroutineContext().ensureActive()
                }
            }
        }

    override suspend fun encodePng(bitmap: ImageBitmap): ByteArray =
        withContext(Dispatchers.Default) {
            mapDesktopImageFailure(DesktopFailureOperation.ENCODE) {
                currentCoroutineContext().ensureActive()
                val result = Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                    requireNotNull(image.encodeToData(EncodedImageFormat.PNG, 100)).use { data ->
                        data.bytes
                    }
                }
                currentCoroutineContext().ensureActive()
                result
            }
        }

    private fun orientPreview(
        decoded: Bitmap,
        origin: EncodedOrigin,
        target: ImageSize,
    ): ImageBitmap = Surface.makeRasterN32Premul(target.width, target.height).use { surface ->
        surface.canvas.clear(Color.TRANSPARENT)
        Image.makeFromBitmap(decoded).use { image ->
            surface.canvas.concat(origin.toOrientedAffine(target).toSkiaMatrix())
            surface.canvas.drawImage(image, 0f, 0f)
        }
        surface.makeImageSnapshot().use { snapshot ->
            snapshot.toOwnedComposeImageBitmap()
        }
    }

    private fun renderEncodedPreview(
        bytes: ByteArray,
        metadata: ImageMetadata,
        target: ImageSize,
    ): ImageBitmap = Surface.makeRasterN32Premul(target.width, target.height).use { surface ->
        surface.canvas.clear(Color.TRANSPARENT)
        Image.makeFromEncoded(bytes).use { image ->
            // Encoded Skia images apply Codec.encodedOrigin before exposing their dimensions.
            check(image.width == metadata.orientedSize.width)
            check(image.height == metadata.orientedSize.height)
            surface.canvas.drawImageRect(
                image,
                Rect.makeWH(target.width.toFloat(), target.height.toFloat()),
            )
        }
        surface.makeImageSnapshot().use { snapshot ->
            snapshot.toOwnedComposeImageBitmap()
        }
    }

    private fun renderEncodedCrop(
        bytes: ByteArray,
        metadata: ImageMetadata,
        plan: CropRenderPlan,
    ): ImageBitmap = Surface.makeRasterN32Premul(
        plan.outputSize.width,
        plan.outputSize.height,
    ).use { surface ->
        surface.canvas.clear(Color.TRANSPARENT)
        Image.makeFromEncoded(bytes).use { image ->
            // The source is already origin-normalized, so the shared crop affine consumes it directly.
            check(image.width == metadata.orientedSize.width)
            check(image.height == metadata.orientedSize.height)
            val visible = plan.visibleSourceBounds
            val anchorX = ((visible.left.toDouble() + visible.right) / 2.0)
                .coerceIn(0.0, metadata.orientedSize.width.toDouble())
            val anchorY = ((visible.top.toDouble() + visible.bottom) / 2.0)
                .coerceIn(0.0, metadata.orientedSize.height.toDouble())
            val localToOutput = plan.sourceToOutput.rebaseForDesktopSkia(anchorX, anchorY)

            surface.canvas.concat(localToOutput.matrix)
            surface.canvas.drawImage(image, localToOutput.drawX, localToOutput.drawY)
        }
        surface.makeImageSnapshot().use { snapshot ->
            snapshot.toOwnedComposeImageBitmap()
        }
    }

    private inline fun <T> withCodec(
        bytes: ByteArray,
        block: (Codec, ImageMetadata) -> T,
    ): T = Data.makeFromBytes(bytes).use { data ->
        val codec = try {
            Codec.makeFromData(data)
        } catch (cause: IllegalArgumentException) {
            throw PrintImageFailure.UnsupportedFormat(cause)
        }
        codec.use {
            if (codec.encodedImageFormat !in SUPPORTED_FORMATS) {
                throw PrintImageFailure.UnsupportedFormat()
            }
            val rawWidth = codec.size.x
            val rawHeight = codec.size.y
            if (rawWidth <= 0 || rawHeight <= 0) {
                throw PrintImageFailure.UnsupportedFormat()
            }
            if (rawWidth.toLong() * rawHeight > PrintImageLimits.MAX_PIXELS) {
                throw PrintImageFailure.ImageDimensionsTooLarge
            }
            val origin = codec.encodedOrigin
            val rawSize = ImageSize(rawWidth, rawHeight)
            val orientedSize = if (origin.swapsWidthHeight()) {
                ImageSize(rawHeight, rawWidth)
            } else {
                rawSize
            }
            block(codec, ImageMetadata(orientedSize, origin))
        }
    }

    private data class ImageMetadata(
        val orientedSize: ImageSize,
        val origin: EncodedOrigin,
    )

    private companion object {
        val SUPPORTED_FORMATS = setOf(
            EncodedImageFormat.JPEG,
            EncodedImageFormat.PNG,
            EncodedImageFormat.HEIF,
        )
    }
}

private fun EncodedOrigin.toOrientedAffine(orientedSize: ImageSize): AffineTransform = when (this) {
    EncodedOrigin.TOP_LEFT -> AffineTransform(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    EncodedOrigin.TOP_RIGHT -> AffineTransform(
        -1.0,
        0.0,
        orientedSize.width.toDouble(),
        0.0,
        1.0,
        0.0,
    )
    EncodedOrigin.BOTTOM_RIGHT -> AffineTransform(
        -1.0,
        0.0,
        orientedSize.width.toDouble(),
        0.0,
        -1.0,
        orientedSize.height.toDouble(),
    )
    EncodedOrigin.BOTTOM_LEFT -> AffineTransform(
        1.0,
        0.0,
        0.0,
        0.0,
        -1.0,
        orientedSize.height.toDouble(),
    )
    EncodedOrigin.LEFT_TOP -> AffineTransform(0.0, 1.0, 0.0, 1.0, 0.0, 0.0)
    EncodedOrigin.RIGHT_TOP -> AffineTransform(
        0.0,
        -1.0,
        orientedSize.width.toDouble(),
        1.0,
        0.0,
        0.0,
    )
    EncodedOrigin.RIGHT_BOTTOM -> AffineTransform(
        0.0,
        -1.0,
        orientedSize.width.toDouble(),
        -1.0,
        0.0,
        orientedSize.height.toDouble(),
    )
    EncodedOrigin.LEFT_BOTTOM -> AffineTransform(
        0.0,
        1.0,
        0.0,
        -1.0,
        0.0,
        orientedSize.height.toDouble(),
    )
    EncodedOrigin.UNUSED -> throw PrintImageFailure.UnsupportedFormat()
}

internal data class DesktopSkiaRebase(
    val matrix: Matrix33,
    val drawX: Float,
    val drawY: Float,
)

internal fun AffineTransform.rebaseForDesktopSkia(
    anchorX: Double,
    anchorY: Double,
): DesktopSkiaRebase {
    val floatAnchorX = anchorX.toFloat()
    val floatAnchorY = anchorY.toFloat()
    val canonicalAnchorX = floatAnchorX.toDouble()
    val canonicalAnchorY = floatAnchorY.toDouble()
    val rebased = copy(
        translateX = scaleX * canonicalAnchorX + skewX * canonicalAnchorY + translateX,
        translateY = skewY * canonicalAnchorX + scaleY * canonicalAnchorY + translateY,
    )
    return DesktopSkiaRebase(
        matrix = rebased.toSkiaMatrix(),
        drawX = -floatAnchorX,
        drawY = -floatAnchorY,
    )
}

private fun AffineTransform.toSkiaMatrix(): Matrix33 = Matrix33(
    scaleX.toFloat(),
    skewX.toFloat(),
    translateX.toFloat(),
    skewY.toFloat(),
    scaleY.toFloat(),
    translateY.toFloat(),
    0f,
    0f,
    1f,
)

// The returned ImageBitmap owns this Bitmap; every temporary Skia object remains caller-closed.
private fun Image.toOwnedComposeImageBitmap(): ImageBitmap =
    Bitmap.makeFromImage(this).asComposeImageBitmap()

internal enum class DesktopRasterStrategy {
    DIRECT_CODEC,
    BOUNDED_ENCODED_IMAGE,
    REJECT_UNSAFE_SOURCE,
}

internal fun planDesktopPreviewRaster(
    source: ImageSize,
    target: ImageSize,
): DesktopRasterStrategy = when {
    source == target -> DesktopRasterStrategy.DIRECT_CODEC
    source.pixelCount() <= PrintImageLimits.MAX_INTERMEDIATE_DECODE_PIXELS ->
        DesktopRasterStrategy.BOUNDED_ENCODED_IMAGE
    else -> DesktopRasterStrategy.REJECT_UNSAFE_SOURCE
}

internal fun planDesktopCropRaster(source: ImageSize): DesktopRasterStrategy =
    if (source.pixelCount() <= PrintImageLimits.MAX_INTERMEDIATE_DECODE_PIXELS) {
        DesktopRasterStrategy.BOUNDED_ENCODED_IMAGE
    } else {
        DesktopRasterStrategy.REJECT_UNSAFE_SOURCE
    }

private fun ImageSize.pixelCount(): Long = width.toLong() * height

private fun unsafeDesktopRasterCause(source: ImageSize): IllegalStateException =
    IllegalStateException(
        "Desktop Skia cannot safely rasterize ${source.width}x${source.height}; " +
                "the source exceeds the ${PrintImageLimits.MAX_INTERMEDIATE_DECODE_PIXELS}-pixel " +
                "full-raster limit and this Skiko version has no region decode API",
    )

internal enum class DesktopFailureOperation {
    DECODE,
    RENDER,
    ENCODE,
}

internal inline fun <T> mapDesktopImageFailure(
    operation: DesktopFailureOperation,
    block: () -> T,
): T = try {
    block()
} catch (cause: CancellationException) {
    throw cause
} catch (failure: PrintImageFailure) {
    throw failure
} catch (cause: Exception) {
    throw when (operation) {
        DesktopFailureOperation.DECODE -> PrintImageFailure.DecodeFailed(cause)
        DesktopFailureOperation.RENDER -> PrintImageFailure.RenderFailed(cause)
        DesktopFailureOperation.ENCODE -> PrintImageFailure.EncodeFailed(cause)
    }
}
