package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DecodeSizePlannerTest {
    @Test
    fun squareImageIsBoundedByPixelCount() {
        val result = DecodeSizePlanner.plan(
            source = ImageSize(10_000, 10_000),
            request = DecodeRequest(maxDimension = 5_760, maxPixels = 16_000_000L),
        )

        assertEquals(ImageSize(4_000, 4_000), result)
    }

    @Test
    fun landscapeImageIsBoundedByLongestEdge() {
        val result = DecodeSizePlanner.plan(
            source = ImageSize(8_000, 4_000),
            request = DecodeRequest(maxDimension = 2_048, maxPixels = 16_000_000L),
        )

        assertEquals(ImageSize(2_048, 1_024), result)
    }

    @Test
    fun portraitImageIsBoundedByLongestEdge() {
        val result = DecodeSizePlanner.plan(
            source = ImageSize(4_000, 8_000),
            request = DecodeRequest(maxDimension = 2_048, maxPixels = 16_000_000L),
        )

        assertEquals(ImageSize(1_024, 2_048), result)
    }

    @Test
    fun imageSmallerThanBoundsIsNotUpscaled() {
        val result = DecodeSizePlanner.plan(
            source = ImageSize(640, 480),
            request = DecodeRequest(maxDimension = 2_048, maxPixels = 16_000_000L),
        )

        assertEquals(ImageSize(640, 480), result)
    }

    @Test
    fun irregularImageSatisfiesBothBoundsAndPreservesAspectRatio() {
        val source = ImageSize(9_999, 7_777)
        val request = DecodeRequest(maxDimension = 5_760, maxPixels = 16_000_000L)

        val result = DecodeSizePlanner.plan(source, request)

        assertTrue(maxOf(result.width, result.height) <= request.maxDimension)
        assertTrue(result.width.toLong() * result.height <= request.maxPixels)
        val sourceRatio = source.width.toDouble() / source.height
        val resultRatio = result.width.toDouble() / result.height
        assertTrue(abs(sourceRatio - resultRatio) <= 0.001)
    }

    @Test
    fun nonPositiveSourceWidthIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DecodeSizePlanner.plan(ImageSize(0, 480), DecodeRequest(2_048, 16_000_000L))
        }
    }

    @Test
    fun nonPositiveSourceHeightIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DecodeSizePlanner.plan(ImageSize(640, -1), DecodeRequest(2_048, 16_000_000L))
        }
    }

    @Test
    fun nonPositiveMaximumDimensionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DecodeSizePlanner.plan(ImageSize(640, 480), DecodeRequest(0, 16_000_000L))
        }
    }

    @Test
    fun nonPositiveMaximumPixelCountIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DecodeSizePlanner.plan(ImageSize(640, 480), DecodeRequest(2_048, 0L))
        }
    }

    @Test
    fun codecRequestOverloadForwardsMaximumDimension() = runBlocking {
        val codec = RecordingPlatformImageCodec()

        codec.decode(byteArrayOf(1), DecodeRequest(1_234, 5_678L))

        assertEquals(1_234, codec.maxDimension)
    }
}

private class RecordingPlatformImageCodec : PlatformImageCodec {
    var maxDimension: Int? = null

    override suspend fun decode(bytes: ByteArray, maxDimension: Int): DecodedImage {
        this.maxDimension = maxDimension
        return DecodedImage(ImageBitmap(1, 1), ImageSize(1, 1))
    }

    override suspend fun encodePng(bitmap: ImageBitmap): ByteArray = byteArrayOf()
}
