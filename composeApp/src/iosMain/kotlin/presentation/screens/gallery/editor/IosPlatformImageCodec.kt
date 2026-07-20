package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.posix.memcpy
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalForeignApi::class)
class IosPlatformImageCodec : PlatformImageCodec {
    override suspend fun decode(bytes: ByteArray, maxDimension: Int): DecodedImage =
        withContext(Dispatchers.Default) {
            require(maxDimension > 0) { "maxDimension must be positive" }
            if (!bytes.hasSupportedImageSignature()) {
                throw PrintImageFailure.UnsupportedFormat()
            }
            try {
                val image = UIImage.imageWithData(bytes.toNSData())
                    ?: throw PrintImageFailure.UnsupportedFormat()
                val logicalSize = image.size.useContents {
                    ImageSize(
                        width = (width * image.scale).roundToInt(),
                        height = (height * image.scale).roundToInt(),
                    )
                }
                if (logicalSize.width <= 0 || logicalSize.height <= 0) {
                    throw PrintImageFailure.UnsupportedFormat()
                }
                if (logicalSize.width.toLong() * logicalSize.height > PrintImageLimits.MAX_PIXELS) {
                    throw PrintImageFailure.ImageDimensionsTooLarge
                }

                val scale = (maxDimension.toFloat() / max(logicalSize.width, logicalSize.height))
                    .coerceAtMost(1f)
                val targetWidth = (logicalSize.width * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (logicalSize.height * scale).roundToInt().coerceAtLeast(1)
                UIGraphicsBeginImageContextWithOptions(
                    CGSizeMake(targetWidth.toDouble(), targetHeight.toDouble()),
                    false,
                    1.0,
                )
                val normalized = try {
                    image.drawInRect(
                        CGRectMake(
                            0.0,
                            0.0,
                            targetWidth.toDouble(),
                            targetHeight.toDouble(),
                        )
                    )
                    UIGraphicsGetImageFromCurrentImageContext()
                        ?: throw PrintImageFailure.DecodeFailed(
                            IllegalStateException("Unable to render selected image")
                        )
                } finally {
                    UIGraphicsEndImageContext()
                }
                val normalizedBytes = UIImagePNGRepresentation(normalized)?.toByteArray()
                    ?: throw PrintImageFailure.DecodeFailed(
                        IllegalStateException("Unable to encode normalized image")
                    )
                DecodedImage(
                    bitmap = normalizedBytes.decodeToImageBitmap(),
                    originalSize = logicalSize,
                )
            } catch (failure: PrintImageFailure) {
                throw failure
            } catch (cause: Throwable) {
                throw PrintImageFailure.DecodeFailed(cause)
            }
        }

    override suspend fun encodePng(bitmap: ImageBitmap): ByteArray =
        withContext(Dispatchers.Default) {
            try {
                val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
                val data = image.encodeToData(EncodedImageFormat.PNG, 100)
                    ?: throw PrintImageFailure.EncodeFailed()
                val bytes = data.bytes
                data.close()
                image.close()
                bytes
            } catch (failure: PrintImageFailure) {
                throw failure
            } catch (cause: Throwable) {
                throw PrintImageFailure.EncodeFailed(cause)
            }
        }

    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
        NSData.dataWithBytes(bytes = pinned.addressOf(0), length = size.toULong())
    }

    private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { result ->
        result.usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }

    private fun ByteArray.hasSupportedImageSignature(): Boolean =
        hasPrefix(JPEG_SIGNATURE) || hasPrefix(PNG_SIGNATURE) || hasSupportedHeifBrand()

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
