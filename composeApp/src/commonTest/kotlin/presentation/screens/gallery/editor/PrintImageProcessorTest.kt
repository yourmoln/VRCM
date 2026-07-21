package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

abstract class PrintImageProcessorContractTest {
    @Test
    fun oversizedEncodedFileFailsBeforeDecode() = runBlocking {
        val codec = FakePlatformImageCodec()
        val processor = DefaultPrintImageProcessor(
            codec = codec,
            maxFileBytes = 4,
        )

        val result = processor.prepare(SelectedImage("large.jpg", ByteArray(5)))

        assertIs<PrintImageFailure.FileTooLarge>(result.exceptionOrNull())
        assertEquals(emptyList(), codec.decodeRequests)
    }

    @Test
    fun prepareUsesBoundedRequestAndRejectsOversizedPixelDimensions() = runBlocking {
        val codec = FakePlatformImageCodec(
            originalSize = ImageSize(10_001, 10_001),
            allowDecode = true,
        )
        val processor = DefaultPrintImageProcessor(codec = codec)

        val result = processor.prepare(SelectedImage("huge.png", byteArrayOf(1)))

        assertIs<PrintImageFailure.ImageDimensionsTooLarge>(result.exceptionOrNull())
        assertEquals(
            listOf(
                DecodeRequest(
                    maxDimension = 2_048,
                    maxPixels = PrintImageLimits.MAX_INTERMEDIATE_DECODE_PIXELS,
                ),
            ),
            codec.decodeRequests,
        )
    }

    @Test
    fun renderUsesPlatformCropWithoutPreviewDecodeAndBuildsPrintCanvas() = runBlocking {
        val originalSize = ImageSize(6_000, 4_000)
        val transform = CropTransform(
            centerOffsetX = 0.2f,
            centerOffsetY = -0.15f,
            zoom = 1.4f,
            quarterTurns = 1,
            flipHorizontal = true,
        )
        val codec = FakePlatformImageCodec()
        val processor = DefaultPrintImageProcessor(codec = codec)

        val result = processor.render(
            source = SelectedImage("photo.jpg", byteArrayOf(1)),
            originalSize = originalSize,
            transform = transform,
        )

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString())
        assertEquals(emptyList(), codec.decodeRequests)
        assertEquals(
            listOf(
                CropRenderRequest(
                    originalSize = originalSize,
                    transform = transform,
                    outputSize = ImageSize(1_920, 1_080),
                ),
            ),
            codec.cropRequests,
        )
        val output = requireNotNull(codec.encodedBitmap)
        assertEquals(2_048, output.width)
        assertEquals(1_440, output.height)
        val pixels = output.toPixelMap()
        assertEquals(Color.Red, pixels[64, 69])
        assertEquals(Color.Red, pixels[1_983, 69])
        assertEquals(Color.Red, pixels[64, 1_148])
        assertEquals(Color.Red, pixels[1_983, 1_148])

        for (y in 0 until output.height) {
            for (x in 0 until output.width) {
                val isContent = x in 64 until 1_984 && y in 69 until 1_149
                if (!isContent) {
                    assertEquals(Color.White, pixels[x, y], "Expected white background at ($x, $y)")
                }
            }
        }
    }

    @Test
    fun invalidPngSignatureIsRejected() = runBlocking {
        val codec = FakePlatformImageCodec(encodedBytes = byteArrayOf(1, 2, 3))
        val processor = DefaultPrintImageProcessor(codec = codec)

        val result = processor.render(
            source = SelectedImage("photo.png", byteArrayOf(1)),
            originalSize = ImageSize(1_920, 1_080),
            transform = CropTransform(),
        )

        assertIs<PrintImageFailure.EncodeFailed>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun wrongPngDimensionsAreRejected() = runBlocking {
        val codec = FakePlatformImageCodec(encodedBytes = pngHeader(width = 100, height = 100))
        val processor = DefaultPrintImageProcessor(codec = codec)

        val result = processor.render(
            source = SelectedImage("photo.png", byteArrayOf(1)),
            originalSize = ImageSize(1_920, 1_080),
            transform = CropTransform(),
        )

        assertIs<PrintImageFailure.EncodeFailed>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun cropCancellationPropagatesUnchanged() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val codec = FakePlatformImageCodec(renderFailure = cancellation)
        val processor = DefaultPrintImageProcessor(codec = codec)

        val thrown = assertFailsWith<CancellationException> {
            processor.render(
                source = SelectedImage("photo.png", byteArrayOf(1)),
                originalSize = ImageSize(1_920, 1_080),
                transform = CropTransform(),
            )
        }

        assertTrue(thrown === cancellation)
    }

    @Test
    fun fatalCropFailureIsNotCapturedInResult() = runBlocking {
        val fatal = AssertionError("fatal")
        val codec = FakePlatformImageCodec(renderFailure = fatal)
        val processor = DefaultPrintImageProcessor(codec = codec)

        val thrown = assertFailsWith<AssertionError> {
            processor.render(
                source = SelectedImage("photo.png", byteArrayOf(1)),
                originalSize = ImageSize(1_920, 1_080),
                transform = CropTransform(),
            )
        }

        assertTrue(thrown === fatal)
    }
}

private class FakePlatformImageCodec(
    private val originalSize: ImageSize = ImageSize(1_920, 1_080),
    private val encodedBytes: ByteArray = pngHeader(2_048, 1_440),
    private val allowDecode: Boolean = false,
    private val renderFailure: Throwable? = null,
) : PlatformImageCodec {
    val decodeRequests = mutableListOf<DecodeRequest>()
    val cropRequests = mutableListOf<CropRenderRequest>()
    var encodedBitmap: ImageBitmap? = null

    override suspend fun decode(bytes: ByteArray, request: DecodeRequest): DecodedImage {
        check(allowDecode) { "Final rendering must not use preview decode" }
        decodeRequests += request
        return DecodedImage(solidBitmap(16, 9, Color.Red), originalSize)
    }

    override suspend fun renderCrop(bytes: ByteArray, request: CropRenderRequest): ImageBitmap {
        renderFailure?.let { throw it }
        cropRequests += request
        return solidBitmap(request.outputSize.width, request.outputSize.height, Color.Red)
    }

    override suspend fun encodePng(bitmap: ImageBitmap): ByteArray {
        encodedBitmap = bitmap
        return encodedBytes
    }
}

private fun solidBitmap(width: Int, height: Int, color: Color): ImageBitmap =
    ImageBitmap(width, height).also { bitmap ->
        Canvas(bitmap).drawRect(
            rect = Rect(0f, 0f, width.toFloat(), height.toFloat()),
            paint = Paint().apply { this.color = color },
        )
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
