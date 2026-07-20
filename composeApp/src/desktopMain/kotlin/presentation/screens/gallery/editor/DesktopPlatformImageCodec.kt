package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.roundToInt

class DesktopPlatformImageCodec : PlatformImageCodec {
    override suspend fun decode(bytes: ByteArray, maxDimension: Int): DecodedImage =
        withContext(Dispatchers.Default) {
            require(maxDimension > 0) { "maxDimension must be positive" }
            try {
                Data.makeFromBytes(bytes).use { data ->
                    Codec.makeFromData(data).use { codec ->
                        if (codec.encodedImageFormat !in SUPPORTED_FORMATS) {
                            throw PrintImageFailure.UnsupportedFormat()
                        }

                        val rawWidth = codec.size.x
                        val rawHeight = codec.size.y
                        if (rawWidth <= 0 || rawHeight <= 0) {
                            throw PrintImageFailure.UnsupportedFormat()
                        }
                        if (rawWidth.toLong() * rawHeight > MAX_PIXELS) {
                            throw PrintImageFailure.ImageDimensionsTooLarge
                        }

                        val origin = codec.encodedOrigin
                        val orientedWidth = if (origin.swapsWidthHeight()) rawHeight else rawWidth
                        val orientedHeight = if (origin.swapsWidthHeight()) rawWidth else rawHeight
                        val scale = (maxDimension.toFloat() / max(orientedWidth, orientedHeight))
                            .coerceAtMost(1f)
                        val targetWidth = (orientedWidth * scale).roundToInt().coerceAtLeast(1)
                        val targetHeight = (orientedHeight * scale).roundToInt().coerceAtLeast(1)

                        val bitmap = Image.makeFromEncoded(bytes).use { image ->
                            Surface.makeRasterN32Premul(targetWidth, targetHeight).use { surface ->
                                surface.canvas.scale(
                                    targetWidth.toFloat() / orientedWidth,
                                    targetHeight.toFloat() / orientedHeight,
                                )
                                surface.canvas.concat(origin.toMatrix(orientedWidth, orientedHeight))
                                surface.canvas.drawImage(image, 0f, 0f)
                                surface.makeImageSnapshot().toComposeImageBitmap()
                            }
                        }
                        DecodedImage(
                            bitmap = bitmap,
                            originalSize = ImageSize(orientedWidth, orientedHeight),
                        )
                    }
                }
            } catch (failure: PrintImageFailure) {
                throw failure
            } catch (cause: IllegalArgumentException) {
                throw PrintImageFailure.UnsupportedFormat(cause)
            } catch (cause: Throwable) {
                throw PrintImageFailure.DecodeFailed(cause)
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
            } catch (failure: PrintImageFailure) {
                throw failure
            } catch (cause: Throwable) {
                throw PrintImageFailure.EncodeFailed(cause)
            }
        }

    private companion object {
        const val MAX_PIXELS = 100_000_000L
        val SUPPORTED_FORMATS = setOf(
            EncodedImageFormat.JPEG,
            EncodedImageFormat.PNG,
            EncodedImageFormat.HEIF,
        )
    }
}
