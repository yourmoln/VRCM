package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

abstract class PrintImageProcessorContractTest {
    @Test
    fun oversizedEncodedFileFailsBeforeDecode() = runBlocking {
        val codec = FakePlatformImageCodec()
        val processor = DefaultPrintImageProcessor(
            codec = codec,
            calculator = CropTransformCalculator(),
            maxFileBytes = 4,
        )

        val result = processor.prepare(SelectedImage("large.jpg", ByteArray(5)))

        assertIs<PrintImageFailure.FileTooLarge>(result.exceptionOrNull())
        assertEquals(0, codec.decodeRequests.size)
    }

    @Test
    fun oversizedPixelDimensionsAreRejected() = runBlocking {
        val codec = FakePlatformImageCodec(originalSize = ImageSize(10_001, 10_001))
        val processor = DefaultPrintImageProcessor(
            codec = codec,
            calculator = CropTransformCalculator(),
        )

        val result = processor.prepare(SelectedImage("huge.png", byteArrayOf(1)))

        assertIs<PrintImageFailure.ImageDimensionsTooLarge>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun renderBuildsPrintCanvasWithWhiteMargins() = runBlocking {
        val codec = FakePlatformImageCodec()
        val processor = DefaultPrintImageProcessor(
            codec = codec,
            calculator = CropTransformCalculator(),
        )

        val result = processor.render(
            source = SelectedImage("photo.jpg", byteArrayOf(1)),
            transform = CropTransform(),
        )

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString())
        assertEquals(listOf(5_760), codec.decodeRequests)
        val output = requireNotNull(codec.encodedBitmap)
        assertEquals(2_048, output.width)
        assertEquals(1_440, output.height)
        val pixels = output.toPixelMap()
        assertEquals(Color.White, pixels[0, 0])
        assertEquals(Color.White, pixels[63, 68])
        assertEquals(Color.Red, pixels[64, 69])
        assertEquals(Color.Red, pixels[1_983, 1_148])
        assertEquals(Color.White, pixels[2_047, 1_439])
    }

    @Test
    fun invalidPngSignatureIsRejected() = runBlocking {
        val codec = FakePlatformImageCodec(encodedBytes = byteArrayOf(1, 2, 3))
        val processor = DefaultPrintImageProcessor(codec, CropTransformCalculator())

        val result = processor.render(
            source = SelectedImage("photo.png", byteArrayOf(1)),
            transform = CropTransform(),
        )

        assertIs<PrintImageFailure.EncodeFailed>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun wrongPngDimensionsAreRejected() = runBlocking {
        val codec = FakePlatformImageCodec(encodedBytes = pngHeader(width = 100, height = 100))
        val processor = DefaultPrintImageProcessor(codec, CropTransformCalculator())

        val result = processor.render(
            source = SelectedImage("photo.png", byteArrayOf(1)),
            transform = CropTransform(),
        )

        assertIs<PrintImageFailure.EncodeFailed>(result.exceptionOrNull())
        Unit
    }
}

private class FakePlatformImageCodec(
    private val originalSize: ImageSize = ImageSize(1_920, 1_080),
    private val encodedBytes: ByteArray = pngHeader(2_048, 1_440),
) : PlatformImageCodec {
    val decodeRequests = mutableListOf<Int>()
    var encodedBitmap: ImageBitmap? = null

    override suspend fun decode(bytes: ByteArray, maxDimension: Int): DecodedImage {
        decodeRequests += maxDimension
        val bitmap = ImageBitmap(16, 9)
        Canvas(bitmap).drawRect(
            rect = Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
            paint = Paint().apply { color = Color.Red },
        )
        return DecodedImage(bitmap, originalSize)
    }

    override suspend fun encodePng(bitmap: ImageBitmap): ByteArray {
        encodedBitmap = bitmap
        return encodedBytes
    }
}

private fun pngHeader(width: Int, height: Int): ByteArray = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0x00, 0x00, 0x00, 0x0D,
    0x49, 0x48, 0x44, 0x52,
    (width ushr 24).toByte(),
    (width ushr 16).toByte(),
    (width ushr 8).toByte(),
    width.toByte(),
    (height ushr 24).toByte(),
    (height ushr 16).toByte(),
    (height ushr 8).toByte(),
    height.toByte(),
)
