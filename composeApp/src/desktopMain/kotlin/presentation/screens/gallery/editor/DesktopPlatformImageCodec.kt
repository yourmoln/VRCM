package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
            try {
                withCodec(bytes) { codec, metadata ->
                    val target = DecodeSizePlanner.plan(metadata.orientedSize, request)
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
                        val bitmap = try {
                            codec.readPixels(decoded)
                            orientPreview(decoded, metadata.origin, target)
                        } catch (cause: IllegalArgumentException) {
                            if (!cause.isUnsupportedCodecScale()) throw cause
                            renderEncodedPreview(bytes, metadata, target)
                        }
                        DecodedImage(
                            bitmap = bitmap,
                            originalSize = metadata.orientedSize,
                        )
                    }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (failure: PrintImageFailure) {
                throw failure
            } catch (cause: IllegalArgumentException) {
                throw PrintImageFailure.UnsupportedFormat(cause)
            } catch (cause: Throwable) {
                throw PrintImageFailure.DecodeFailed(cause)
            }
        }

    suspend fun renderCrop(bytes: ByteArray, request: CropRenderRequest): ImageBitmap =
        withContext(Dispatchers.Default) {
            try {
                val metadata = withCodec(bytes) { _, metadata -> metadata }
                if (metadata.orientedSize != request.originalSize) {
                    throw PrintImageFailure.RenderFailed(
                        IllegalArgumentException(
                            "Original size ${request.originalSize} does not match " +
                                    "image size ${metadata.orientedSize}",
                        ),
                    )
                }
                val plan = CropRenderPlanner().plan(request)
                renderEncodedCrop(bytes, metadata, plan)
            } catch (cause: CancellationException) {
                throw cause
            } catch (failure: PrintImageFailure) {
                throw failure
            } catch (cause: Throwable) {
                throw PrintImageFailure.RenderFailed(cause)
            }
        }

    override suspend fun encodePng(bitmap: ImageBitmap): ByteArray =
        withContext(Dispatchers.Default) {
            try {
                Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                    requireNotNull(image.encodeToData(EncodedImageFormat.PNG, 100)).use { data ->
                        data.bytes
                    }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (failure: PrintImageFailure) {
                throw failure
            } catch (cause: Throwable) {
                throw PrintImageFailure.EncodeFailed(cause)
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
            val localToOutput = plan.sourceToOutput.rebase(anchorX, anchorY)

            surface.canvas.concat(localToOutput.toSkiaMatrix())
            surface.canvas.drawImage(image, -anchorX.toFloat(), -anchorY.toFloat())
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

private fun AffineTransform.rebase(anchorX: Double, anchorY: Double): AffineTransform = copy(
    translateX = scaleX * anchorX + skewX * anchorY + translateX,
    translateY = skewY * anchorX + scaleY * anchorY + translateY,
)

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

private fun IllegalArgumentException.isUnsupportedCodecScale(): Boolean =
    message?.startsWith("Invalid scale:") == true

// The returned ImageBitmap owns this Bitmap; every temporary Skia object remains caller-closed.
private fun Image.toOwnedComposeImageBitmap(): ImageBitmap =
    Bitmap.makeFromImage(this).asComposeImageBitmap()
