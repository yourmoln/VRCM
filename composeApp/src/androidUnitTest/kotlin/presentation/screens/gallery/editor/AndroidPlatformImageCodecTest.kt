package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PixelBitmapRegionDecoderShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidPlatformImageCodecTest {
    private val codec = AndroidPlatformImageCodec()

    @Test
    fun pngRoundTripPreservesDimensions() = runBlocking {
        val png = createEncodedBitmap(12, 7, Bitmap.CompressFormat.PNG)
        assertDecodableBounds(png, "image/png")
        val decoded = codec.decode(png, 2_048)

        assertEquals(ImageSize(12, 7), decoded.originalSize)
        assertEquals(12, decoded.bitmap.width)
        assertEquals(7, decoded.bitmap.height)

        val encoded = codec.encodePng(decoded.bitmap)
        assertTrue(encoded.hasPngSignature())
    }

    @Test
    fun exifOrientationSixIsNormalized() = runBlocking {
        val jpeg = createEncodedBitmap(12, 7, Bitmap.CompressFormat.JPEG).withExifOrientation(6)
        assertDecodableBounds(jpeg, "image/jpeg")

        val decoded = codec.decode(
            jpeg,
            DecodeRequest(maxDimension = 2_048, maxPixels = 16_000_000L),
        )

        assertEquals(ImageSize(7, 12), decoded.originalSize)
        assertEquals(7, decoded.bitmap.asAndroidBitmap().width)
        assertEquals(12, decoded.bitmap.asAndroidBitmap().height)
    }

    @Test
    fun malformedBytesAreRejected() = runBlocking {
        assertFailsWith<PrintImageFailure.UnsupportedFormat> {
            codec.decode(
                byteArrayOf(1, 2, 3),
                DecodeRequest(maxDimension = 2_048, maxPixels = 16_000_000L),
            )
        }
        Unit
    }

    @Test
    fun previewIsBoundedByDefaultPixelBudget() = runBlocking {
        val png = createEncodedBitmap(4_100, 4_000, Bitmap.CompressFormat.PNG)

        val decoded = codec.decode(
            png,
            DecodeRequest(maxDimension = 5_760, maxPixels = 16_000_000L),
        )
        val bitmap = decoded.bitmap.asAndroidBitmap()

        assertEquals(ImageSize(4_100, 4_000), decoded.originalSize)
        assertTrue(maxOf(bitmap.width, bitmap.height) <= 5_760)
        assertTrue(bitmap.width.toLong() * bitmap.height <= 16_000_000L)
    }

    @Test
    fun legacyDecodeUsesDefaultPixelBudget() = runBlocking {
        val png = createEncodedBitmap(4_100, 4_000, Bitmap.CompressFormat.PNG)

        val decoded = codec.decode(png, 5_760)
        val bitmap = decoded.bitmap.asAndroidBitmap()

        assertEquals(ImageSize(4_100, 4_000), decoded.originalSize)
        assertTrue(maxOf(bitmap.width, bitmap.height) <= 5_760)
        assertTrue(
            bitmap.width.toLong() * bitmap.height <=
                    PrintImageLimits.MAX_INTERMEDIATE_DECODE_PIXELS,
        )
    }

    @Test
    fun previewSampleBoundsIntermediateBitmapToPlannedSize() {
        val source = ImageSize(6_000, 1_000)
        val request = DecodeRequest(maxDimension = 2_048, maxPixels = 16_000_000L)
        val target = DecodeSizePlanner.plan(source, request)

        val sample = calculatePreviewSampleSize(
            rawSize = source,
            orientation = 1,
            target = target,
            request = request,
        )
        val sampledSize = ImageSize(
            width = (source.width + sample - 1) / sample,
            height = (source.height + sample - 1) / sample,
        )

        assertEquals(ImageSize(2_048, 341), target)
        assertEquals(4, sample)
        assertEquals(ImageSize(1_500, 250), sampledSize)
        assertTrue(
            maxOf(sampledSize.width, sampledSize.height) <= maxOf(target.width, target.height),
        )
        assertTrue(sampledSize.width.toLong() * sampledSize.height <= request.maxPixels)
    }

    @Test
    fun previewHonorsRequestPixelBudget() = runBlocking {
        val png = createEncodedBitmap(1_600, 1_200, Bitmap.CompressFormat.PNG)
        val request = DecodeRequest(maxDimension = 2_048, maxPixels = 400_000L)

        val decoded = codec.decode(png, request)
        val bitmap = decoded.bitmap.asAndroidBitmap()

        assertEquals(ImageSize(1_600, 1_200), decoded.originalSize)
        assertTrue(bitmap.width.toLong() * bitmap.height <= request.maxPixels)
        assertTrue(maxOf(bitmap.width, bitmap.height) <= request.maxDimension)
    }

    @Test
    fun cropRenderingUsesResetZoomAndPanSourceRegions() = runBlocking {
        val sourceSize = ImageSize(400, 300)
        val outputSize = ImageSize(200, 100)
        val png = createGradientPng(sourceSize)
        val transforms = listOf(
            CropTransform(),
            CropTransform(zoom = 2f),
            CropTransform(centerOffsetX = 0.35f, centerOffsetY = -0.25f, zoom = 2f),
        )

        transforms.forEach { transform ->
            val request = CropRenderRequest(sourceSize, transform, outputSize)
            val rendered = codec.renderCrop(png, request).asAndroidBitmap()

            assertEquals(outputSize.width, rendered.width)
            assertEquals(outputSize.height, rendered.height)
            listOf(
                12 to 12,
                outputSize.width / 2 to outputSize.height / 2,
                outputSize.width - 13 to outputSize.height - 13,
            ).forEach { (x, y) ->
                val expected = expectedGradientAtOutput(request, x, y)
                assertColorNear(expected, rendered.getPixel(x, y), tolerance = 5)
            }
            rendered.recycle()
        }
    }

    @Test
    fun highZoomCropPreservesSourceStripeDetail() = runBlocking {
        val sourceSize = ImageSize(2_160, 4_320)
        val png = createStripedPng(sourceSize)
        val request = CropRenderRequest(
            originalSize = sourceSize,
            transform = CropTransform(zoom = 3f),
            outputSize = ImageSize(1_920, 1_080),
        )

        val rendered = codec.renderCrop(png, request).asAndroidBitmap()
        val direct = stripeStats(rendered)
        val preview = decodeLegacyFullPreview(png)
        val legacyOutput = renderFromFullPreview(preview, request)
        val legacy = stripeStats(legacyOutput)

        // A 2048-edge full-image preview collapses these two-source-pixel stripes before zooming.
        assertTrue(
            direct.highContrastPixels >= rendered.width * 3 / 5,
            "Expected high-contrast stripe pixels, got $direct",
        )
        assertTrue(
            direct.transitions >= 250,
            "Expected preserved stripe transitions, got $direct",
        )
        assertTrue(direct.highContrastPixels >= legacy.highContrastPixels + 200, "$direct vs $legacy")
        assertTrue(direct.transitions >= legacy.transitions + 100, "$direct vs $legacy")

        rendered.recycle()
        preview.recycle()
        legacyOutput.recycle()
    }

    @Test
    fun malformedCropSourceIsRejectedWithTypedFailure() = runBlocking {
        assertFailsWith<PrintImageFailure.UnsupportedFormat> {
            codec.renderCrop(
                byteArrayOf(1, 2, 3),
                CropRenderRequest(ImageSize(12, 7), CropTransform(), ImageSize(20, 10)),
            )
        }
        Unit
    }

    @Test
    fun cropRejectsOriginalSizeMismatch() = runBlocking {
        val png = createEncodedBitmap(40, 30, Bitmap.CompressFormat.PNG)

        assertFailsWith<PrintImageFailure.RenderFailed> {
            codec.renderCrop(
                png,
                CropRenderRequest(ImageSize(41, 30), CropTransform(), ImageSize(20, 10)),
            )
        }
        Unit
    }
}

private fun assertDecodableBounds(bytes: ByteArray, expectedMimeType: String) {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    assertTrue(
        options.outWidth > 0 && options.outHeight > 0,
        "Expected positive bounds, mime=${options.outMimeType}, bytes=${bytes.size}",
    )
    if (options.outMimeType != null) {
        assertEquals(expectedMimeType, options.outMimeType)
    }
}

private fun createEncodedBitmap(
    width: Int,
    height: Int,
    format: Bitmap.CompressFormat,
): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.RED)
    }
    return ByteArrayOutputStream().use { output ->
        check(bitmap.compress(format, 100, output))
        bitmap.recycle()
        output.toByteArray()
    }
}

private fun createGradientPng(size: ImageSize): ByteArray = createPng(size) { x, y ->
    gradientColor(x.toDouble(), y.toDouble(), size)
}

private fun createStripedPng(size: ImageSize): ByteArray = createPng(size) { x, _ ->
    if (x / 2 % 2 == 0) Color.BLACK else Color.WHITE
}

private inline fun createPng(size: ImageSize, colorAt: (Int, Int) -> Int): ByteArray {
    val pixels = IntArray(size.width * size.height)
    for (y in 0 until size.height) {
        for (x in 0 until size.width) {
            pixels[y * size.width + x] = colorAt(x, y)
        }
    }
    val bitmap = Bitmap.createBitmap(pixels, size.width, size.height, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        output.toByteArray()
    }
}

private fun expectedGradientAtOutput(
    request: CropRenderRequest,
    outputX: Int,
    outputY: Int,
): Int {
    val transform = CropRenderPlanner().plan(request).sourceToOutput
    val determinant = transform.scaleX * transform.scaleY - transform.skewX * transform.skewY
    val translatedX = outputX + 0.5 - transform.translateX
    val translatedY = outputY + 0.5 - transform.translateY
    val sourceX = (transform.scaleY * translatedX - transform.skewX * translatedY) / determinant
    val sourceY = (-transform.skewY * translatedX + transform.scaleX * translatedY) / determinant
    return gradientColor(sourceX - 0.5, sourceY - 0.5, request.originalSize)
}

private fun gradientColor(x: Double, y: Double, size: ImageSize): Int {
    val red = (x.coerceIn(0.0, (size.width - 1).toDouble()) * 255 / (size.width - 1))
        .toInt()
    val green = (y.coerceIn(0.0, (size.height - 1).toDouble()) * 255 / (size.height - 1))
        .toInt()
    return Color.rgb(red, green, 127)
}

private fun assertColorNear(expected: Int, actual: Int, tolerance: Int) {
    assertTrue(
        abs(Color.red(expected) - Color.red(actual)) <= tolerance,
        "red channel: expected=${Color.red(expected)}, actual=${Color.red(actual)}, " +
                "alpha=${Color.alpha(actual)}, rgb=${Color.red(actual)}/${Color.green(actual)}/" +
                "${Color.blue(actual)}",
    )
    assertTrue(
        abs(Color.green(expected) - Color.green(actual)) <= tolerance,
        "green channel: expected=${Color.green(expected)}, actual=${Color.green(actual)}",
    )
    assertTrue(
        abs(Color.blue(expected) - Color.blue(actual)) <= tolerance,
        "blue channel: expected=${Color.blue(expected)}, actual=${Color.blue(actual)}",
    )
}

private fun renderFromFullPreview(preview: Bitmap, request: CropRenderRequest): Bitmap {
    val plan = CropRenderPlanner().plan(request)
    val sourcePerPreviewX = request.originalSize.width.toDouble() / preview.width
    val sourcePerPreviewY = request.originalSize.height.toDouble() / preview.height
    val transform = plan.sourceToOutput
    val matrix = Matrix().apply {
        setValues(
            floatArrayOf(
                (transform.scaleX * sourcePerPreviewX).toFloat(),
                (transform.skewX * sourcePerPreviewY).toFloat(),
                transform.translateX.toFloat(),
                (transform.skewY * sourcePerPreviewX).toFloat(),
                (transform.scaleY * sourcePerPreviewY).toFloat(),
                transform.translateY.toFloat(),
                0f,
                0f,
                1f,
            ),
        )
    }
    return Bitmap.createBitmap(
        request.outputSize.width,
        request.outputSize.height,
        Bitmap.Config.ARGB_8888,
    ).also { output ->
        Canvas(output).drawBitmap(preview, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    }
}

private fun decodeLegacyFullPreview(bytes: ByteArray): Bitmap {
    val sampled = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = 2
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    ) ?: error("Unable to decode legacy preview fixture")
    return Bitmap.createScaledBitmap(sampled, 1_024, 2_048, true).also {
        if (it !== sampled) sampled.recycle()
    }
}

private fun stripeStats(bitmap: Bitmap): StripeStats {
    val luminance = IntArray(bitmap.width) { x -> Color.red(bitmap.getPixel(x, bitmap.height / 2)) }
    val contrastStates = luminance.asSequence().mapNotNull { value ->
        when {
            value <= 64 -> false
            value >= 191 -> true
            else -> null
        }
    }.toList()
    return StripeStats(
        highContrastPixels = luminance.count { it <= 32 || it >= 223 },
        transitions = contrastStates.zipWithNext().count { (left, right) -> left != right },
        range = luminance.min()..luminance.max(),
    )
}

private data class StripeStats(
    val highContrastPixels: Int,
    val transitions: Int,
    val range: IntRange,
)

private fun ByteArray.withExifOrientation(orientation: Int): ByteArray {
    val segment = byteArrayOf(
        0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22,
        0x45, 0x78, 0x69, 0x66, 0x00, 0x00,
        0x49, 0x49, 0x2A, 0x00,
        0x08, 0x00, 0x00, 0x00,
        0x01, 0x00,
        0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
        orientation.toByte(), 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
    )
    return copyOfRange(0, 2) + segment + copyOfRange(2, size)
}

private fun ByteArray.hasPngSignature(): Boolean {
    val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    return size >= signature.size && signature.indices.all { this[it] == signature[it] }
}

@Implements(BitmapRegionDecoder::class)
class PixelBitmapRegionDecoderShadow {
    private lateinit var source: Bitmap

    @Implementation
    fun decodeRegion(rect: Rect, options: BitmapFactory.Options): Bitmap {
        val cropped = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        val sample = options.inSampleSize.coerceAtLeast(1)
        if (sample == 1) return cropped
        return Bitmap.createScaledBitmap(
            cropped,
            (rect.width() + sample - 1) / sample,
            (rect.height() + sample - 1) / sample,
            true,
        ).also { cropped.recycle() }
    }

    @Implementation
    fun recycle() {
        source.recycle()
    }

    companion object {
        @JvmStatic
        @Implementation
        fun newInstance(
            data: ByteArray,
            offset: Int,
            length: Int,
            @Suppress("UNUSED_PARAMETER") isShareable: Boolean,
        ): BitmapRegionDecoder {
            val decoder = Shadow.newInstanceOf(BitmapRegionDecoder::class.java)
            Shadow.extract<PixelBitmapRegionDecoderShadow>(decoder).source =
                BitmapFactory.decodeByteArray(data, offset, length)
                    ?: throw IllegalArgumentException("Unable to decode test image")
            return decoder
        }
    }
}
