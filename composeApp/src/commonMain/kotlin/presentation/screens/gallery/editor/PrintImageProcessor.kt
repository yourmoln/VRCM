package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.withSave
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

interface PrintImageProcessor {
    suspend fun prepare(source: SelectedImage): Result<PreparedImage>

    suspend fun render(source: SelectedImage, transform: CropTransform): Result<ByteArray>
}

class DefaultPrintImageProcessor(
    private val codec: PlatformImageCodec,
    private val calculator: CropTransformCalculator,
    private val spec: PrintCanvasSpec = PrintCanvasSpec(),
    private val maxFileBytes: Int = PrintImageLimits.MAX_FILE_BYTES.toInt(),
    private val maxPixels: Long = PrintImageLimits.MAX_PIXELS,
) : PrintImageProcessor {
    override suspend fun prepare(source: SelectedImage): Result<PreparedImage> = runCatching {
        validateSource(source)
        val decoded = decode(source.bytes, PREVIEW_MAX_DIMENSION)
        validateDimensions(decoded.originalSize)
        PreparedImage(
            preview = decoded.bitmap,
            originalSize = decoded.originalSize,
        )
    }

    override suspend fun render(
        source: SelectedImage,
        transform: CropTransform,
    ): Result<ByteArray> = runCatching {
        validateSource(source)
        val decoded = decode(source.bytes, FINAL_MAX_DIMENSION)
        validateDimensions(decoded.originalSize)
        val output = renderCanvas(decoded.bitmap, decoded.originalSize, transform)
        val bytes = try {
            codec.encodePng(output)
        } catch (failure: PrintImageFailure) {
            throw failure
        } catch (cause: Throwable) {
            throw PrintImageFailure.EncodeFailed(cause)
        }
        if (!hasExpectedPngHeader(bytes, spec.canvasWidth, spec.canvasHeight)) {
            throw PrintImageFailure.EncodeFailed()
        }
        bytes
    }

    private suspend fun decode(bytes: ByteArray, maxDimension: Int): DecodedImage = try {
        codec.decode(bytes, maxDimension)
    } catch (failure: PrintImageFailure) {
        throw failure
    } catch (cause: Throwable) {
        throw PrintImageFailure.DecodeFailed(cause)
    }

    private fun renderCanvas(
        source: ImageBitmap,
        originalSize: ImageSize,
        transform: CropTransform,
    ): ImageBitmap = try {
        val output = ImageBitmap(
            width = spec.canvasWidth,
            height = spec.canvasHeight,
            hasAlpha = false,
        )
        val canvas = Canvas(output)
        canvas.drawRect(
            rect = Rect(0f, 0f, spec.canvasWidth.toFloat(), spec.canvasHeight.toFloat()),
            paint = Paint().apply { color = Color.White },
        )

        val viewport = ImageSize(spec.contentWidth, spec.contentHeight)
        val geometry = calculator.geometry(
            source = originalSize,
            viewport = viewport,
            transform = transform,
        )
        val oddTurn = transform.quarterTurns.mod(2) != 0
        val unrotatedWidth = if (oddTurn) geometry.imageHeight else geometry.imageWidth
        val unrotatedHeight = if (oddTurn) geometry.imageWidth else geometry.imageHeight
        val paint = Paint().apply {
            isAntiAlias = true
            filterQuality = FilterQuality.High
        }

        canvas.withSave {
            canvas.clipRect(
                left = spec.contentOffsetX.toFloat(),
                top = spec.contentOffsetY.toFloat(),
                right = (spec.contentOffsetX + spec.contentWidth).toFloat(),
                bottom = (spec.contentOffsetY + spec.contentHeight).toFloat(),
            )
            canvas.translate(
                dx = spec.contentOffsetX + spec.contentWidth / 2f + geometry.translationX,
                dy = spec.contentOffsetY + spec.contentHeight / 2f + geometry.translationY,
            )
            canvas.rotate(geometry.rotationDegrees)
            canvas.scale(geometry.scaleXSign, geometry.scaleYSign)
            canvas.drawImageRect(
                image = source,
                dstOffset = IntOffset(
                    x = (-unrotatedWidth / 2f).roundToInt(),
                    y = (-unrotatedHeight / 2f).roundToInt(),
                ),
                dstSize = IntSize(
                    width = unrotatedWidth.roundToInt(),
                    height = unrotatedHeight.roundToInt(),
                ),
                paint = paint,
            )
        }
        output
    } catch (failure: PrintImageFailure) {
        throw failure
    } catch (cause: Throwable) {
        throw PrintImageFailure.RenderFailed(cause)
    }

    private fun validateSource(source: SelectedImage) {
        if (source.bytes.size > maxFileBytes) {
            throw PrintImageFailure.FileTooLarge
        }
    }

    private fun validateDimensions(size: ImageSize) {
        if (size.width <= 0 || size.height <= 0) {
            throw PrintImageFailure.DecodeFailed(IllegalArgumentException("Invalid image dimensions"))
        }
        if (size.width.toLong() * size.height > maxPixels) {
            throw PrintImageFailure.ImageDimensionsTooLarge
        }
    }

    private fun hasExpectedPngHeader(bytes: ByteArray, width: Int, height: Int): Boolean {
        if (bytes.size < PNG_HEADER_SIZE) return false
        if (!PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) return false
        if (!IHDR.indices.all { bytes[PNG_CHUNK_TYPE_OFFSET + it] == IHDR[it] }) return false
        return bytes.readBigEndianInt(PNG_WIDTH_OFFSET) == width &&
                bytes.readBigEndianInt(PNG_HEIGHT_OFFSET) == height
    }

    private fun ByteArray.readBigEndianInt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
                ((this[offset + 1].toInt() and 0xFF) shl 16) or
                ((this[offset + 2].toInt() and 0xFF) shl 8) or
                (this[offset + 3].toInt() and 0xFF)

    private companion object {
        const val PREVIEW_MAX_DIMENSION = 2_048
        const val FINAL_MAX_DIMENSION = 5_760
        const val PNG_HEADER_SIZE = 24
        const val PNG_CHUNK_TYPE_OFFSET = 12
        const val PNG_WIDTH_OFFSET = 16
        const val PNG_HEIGHT_OFFSET = 20

        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val IHDR = byteArrayOf(0x49, 0x48, 0x44, 0x52)
    }
}
