package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.impl.Stats
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopPlatformImageCodecTest {
    private val codec = DesktopPlatformImageCodec()

    @Test
    fun previewStrategyRejectsUnsafeLazyDecodeBeforeRasterization() {
        val source = ImageSize(4_001, 4_000)
        val target = DecodeSizePlanner.plan(
            source,
            DecodeRequest(maxDimension = 2_048, maxPixels = 4_000_000L),
        )

        assertEquals(
            DesktopRasterStrategy.REJECT_UNSAFE_SOURCE,
            planDesktopPreviewRaster(source, target),
        )
    }

    @Test
    fun previewStrategyRejectsUnsafeExactSizeDecode() {
        val source = ImageSize(10_000, 10_000)

        assertEquals(
            DesktopRasterStrategy.REJECT_UNSAFE_SOURCE,
            planDesktopPreviewRaster(source, source),
        )
    }

    @Test
    fun previewStrategyUsesStablePathsWithoutDecoderExceptions() {
        val safeSource = ImageSize(1_600, 1_200)

        assertEquals(
            DesktopRasterStrategy.DIRECT_CODEC,
            planDesktopPreviewRaster(safeSource, safeSource),
        )
        assertEquals(
            DesktopRasterStrategy.BOUNDED_ENCODED_IMAGE,
            planDesktopPreviewRaster(safeSource, ImageSize(800, 600)),
        )
    }

    @Test
    fun cropStrategyRejectsUnsafeLazyDecodeBeforeRasterization() {
        assertEquals(
            DesktopRasterStrategy.REJECT_UNSAFE_SOURCE,
            planDesktopCropRaster(ImageSize(50_000_000, 2)),
        )
        assertEquals(
            DesktopRasterStrategy.BOUNDED_ENCODED_IMAGE,
            planDesktopCropRaster(ImageSize(4_000, 4_000)),
        )
    }

    @Test
    fun skiaRebaseUsesOneFloatAnchorForMatrixAndDrawTranslation() {
        val anchorX = 50_000_003.0
        val anchorY = 1.0
        val transform = AffineTransform(
            scaleX = 2_048.0,
            skewX = 0.0,
            translateX = -2_048.0 * anchorX + 1_024.0,
            skewY = 0.0,
            scaleY = 1_024.0,
            translateY = -1_024.0 * anchorY + 512.0,
        )

        val rebased = transform.rebaseForDesktopSkia(anchorX, anchorY)
        val floatAnchorX = anchorX.toFloat()
        val floatAnchorY = anchorY.toFloat()
        val canonicalMapped = transform.map(floatAnchorX.toDouble(), floatAnchorY.toDouble())

        assertEquals(-floatAnchorX, rebased.drawX)
        assertEquals(-floatAnchorY, rebased.drawY)
        assertEquals(canonicalMapped.x.toFloat(), rebased.matrix.mat[2])
        assertEquals(canonicalMapped.y.toFloat(), rebased.matrix.mat[5])
        assertTrue(
            transform.map(anchorX, anchorY).x.toFloat() != rebased.matrix.mat[2],
            "Fixture must expose a Float-anchor error amplified beyond output pixels",
        )
    }

    @Test
    fun fatalErrorsAreNotMappedToRecoverableImageFailures() {
        DesktopFailureOperation.entries.forEach { operation ->
            val fatal = AssertionError("fatal-$operation")

            val thrown = assertFailsWith<AssertionError> {
                mapDesktopImageFailure(operation) { throw fatal }
            }

            assertSame(fatal, thrown)
        }
    }

    @Test
    fun cancelledImageBitmapHandoffClosesOwnedPixels() {
        val cancellation = CancellationException("cancelled")
        val previousEnabled = Stats.enabled
        Stats.enabled = true
        Stats.allocated.clear()
        try {
            val bitmap = createOwnedImageBitmap()

            val thrown = assertFailsWith<CancellationException> {
                handoffDesktopImageBitmap(bitmap) { throw cancellation }
            }

            assertSame(cancellation, thrown)
            assertEquals(0, Stats.allocated["Bitmap"] ?: 0)
        } finally {
            Stats.allocated.clear()
            Stats.enabled = previousEnabled
        }
    }

    @Test
    fun successfulImageBitmapHandoffKeepsOwnedPixelsOpen() {
        val previousEnabled = Stats.enabled
        Stats.enabled = true
        Stats.allocated.clear()
        try {
            val bitmap = createOwnedImageBitmap()

            assertSame(bitmap, handoffDesktopImageBitmap(bitmap) {})
            assertEquals(1, Stats.allocated["Bitmap"] ?: 0)

            bitmap.asSkiaBitmap().close()
            assertEquals(0, Stats.allocated["Bitmap"] ?: 0)
        } finally {
            Stats.allocated.clear()
            Stats.enabled = previousEnabled
        }
    }

    @Test
    fun promptCancellationAfterWorkerCompletionClosesOwnedPixels() {
        val previousEnabled = Stats.enabled
        Stats.enabled = true
        Stats.allocated.clear()
        try {
            val workerDispatcher = QueuedTestDispatcher()
            val cancellation = CancellationException("cancelled")
            val bitmap = createOwnedImageBitmap()

            val result = cancelAfterWorkerCompletion(workerDispatcher, cancellation) {
                withOwnedPlatformImageResult(
                    dispatcher = workerDispatcher,
                    ownedBitmap = { it },
                ) {
                    bitmap
                }
            }

            assertIs<CancellationException>(result.exceptionOrNull())
            assertEquals(0, Stats.allocated["Bitmap"] ?: 0)
        } finally {
            Stats.allocated.clear()
            Stats.enabled = previousEnabled
        }
    }

    @Test
    fun pngRoundTripPreservesDimensions() = runBlocking {
        val decoded = codec.decode(
            createEncodedImage(12, 7, EncodedImageFormat.PNG),
            DecodeRequest(2_048, PrintImageLimits.MAX_INTERMEDIATE_DECODE_PIXELS),
        )

        assertEquals(ImageSize(12, 7), decoded.originalSize)
        assertEquals(12, decoded.bitmap.width)
        assertEquals(7, decoded.bitmap.height)

        val encoded = codec.encodePng(decoded.bitmap)
        assertTrue(encoded.hasPngSignature())
        Image.makeFromEncoded(encoded).use { image ->
            assertEquals(12, image.width)
            assertEquals(7, image.height)
        }
    }

    @Test
    fun previewHonorsEdgeAndPixelBudgets() = runBlocking {
        val source = ImageSize(1_600, 1_200)
        val request = DecodeRequest(maxDimension = 700, maxPixels = 300_000L)

        val decoded = codec.decode(createEncodedImage(source, EncodedImageFormat.PNG), request)

        assertEquals(source, decoded.originalSize)
        assertEquals(DecodeSizePlanner.plan(source, request).width, decoded.bitmap.width)
        assertEquals(DecodeSizePlanner.plan(source, request).height, decoded.bitmap.height)
        assertTrue(maxOf(decoded.bitmap.width, decoded.bitmap.height) <= request.maxDimension)
        assertTrue(decoded.bitmap.width.toLong() * decoded.bitmap.height <= request.maxPixels)
    }

    @Test
    fun cropRenderingUsesPlannerTransformsAndFixedOutput() = runBlocking {
        val sourceSize = ImageSize(400, 300)
        val outputSize = ImageSize(320, 180)
        val png = createFourColorImage(sourceSize, EncodedImageFormat.PNG)
        val transforms = listOf(
            CropTransform(),
            CropTransform(zoom = 2f),
            CropTransform(centerOffsetX = 0.3f, centerOffsetY = -0.2f, zoom = 2f),
            CropTransform(quarterTurns = 1),
            CropTransform(flipHorizontal = true),
            CropTransform(flipVertical = true),
            CropTransform(
                centerOffsetX = 0.15f,
                centerOffsetY = -0.1f,
                zoom = 1.6f,
                quarterTurns = 3,
                flipHorizontal = true,
                flipVertical = true,
            ),
        )

        transforms.forEach { transform ->
            val request = CropRenderRequest(sourceSize, transform, outputSize)
            val rendered = codec.renderCrop(png, request).asSkiaBitmap()

            assertEquals(outputSize.width, rendered.width)
            assertEquals(outputSize.height, rendered.height)
            listOf(
                outputSize.width / 4 to outputSize.height / 4,
                outputSize.width * 3 / 4 to outputSize.height / 4,
                outputSize.width / 4 to outputSize.height * 3 / 4,
                outputSize.width * 3 / 4 to outputSize.height * 3 / 4,
            ).forEach { (x, y) ->
                assertColorNear(
                    expectedFourColorAtOutput(request, x, y),
                    rendered.getColor(x, y),
                    tolerance = 8,
                )
            }
        }
    }

    @Test
    fun exifSixDecodeAndCropNormalizePixelDirection() = runBlocking {
        val rawSize = ImageSize(400, 300)
        val orientedSize = ImageSize(300, 400)
        val jpeg = createFourColorImage(rawSize, EncodedImageFormat.JPEG).withExifOrientation(6)

        val decoded = codec.decode(
            jpeg,
            DecodeRequest(maxDimension = 2_048, maxPixels = 16_000_000L),
        )
        val preview = decoded.bitmap.asSkiaBitmap()
        val rendered = codec.renderCrop(
            jpeg,
            CropRenderRequest(orientedSize, CropTransform(), ImageSize(150, 200)),
        ).asSkiaBitmap()

        assertEquals(orientedSize, decoded.originalSize)
        assertEquals(orientedSize.width, preview.width)
        assertEquals(orientedSize.height, preview.height)
        assertColorNear(Color.BLUE, preview.getColor(25, 25), tolerance = 35)
        assertColorNear(Color.RED, preview.getColor(274, 25), tolerance = 35)
        assertColorNear(Color.YELLOW, preview.getColor(25, 374), tolerance = 35)
        assertColorNear(Color.GREEN, preview.getColor(274, 374), tolerance = 35)
        assertColorNear(Color.BLUE, rendered.getColor(20, 20), tolerance = 35)
        assertColorNear(Color.RED, rendered.getColor(129, 20), tolerance = 35)
        assertColorNear(Color.YELLOW, rendered.getColor(20, 179), tolerance = 35)
        assertColorNear(Color.GREEN, rendered.getColor(129, 179), tolerance = 35)
    }

    @Test
    fun highZoomCropPreservesMoreStripeDetailThanBoundedFullPreview() = runBlocking {
        val sourceSize = ImageSize(2_160, 4_320)
        val png = createStripedPng(sourceSize)
        val request = CropRenderRequest(
            originalSize = sourceSize,
            transform = CropTransform(zoom = 3f),
            outputSize = ImageSize(1_920, 1_080),
        )

        val rendered = codec.renderCrop(png, request)
        val direct = stripeStats(rendered.asSkiaBitmap())
        val preview = codec.decode(
            png,
            DecodeRequest(2_048, PrintImageLimits.MAX_INTERMEDIATE_DECODE_PIXELS),
        ).bitmap
        val legacy = legacyStripeStats(preview, request)

        assertTrue(
            direct.highContrastPixels >= rendered.width * 3 / 5,
            "Expected preserved high-contrast pixels, got $direct",
        )
        assertTrue(direct.transitions >= 250, "Expected preserved transitions, got $direct")
        assertTrue(direct.highContrastPixels >= legacy.highContrastPixels, "$direct vs $legacy")
        assertTrue(direct.transitions >= legacy.transitions + 25, "$direct vs $legacy")
    }

    @Test
    fun repeatedDecodeAndRenderCloseTemporarySkiaResources() = runBlocking {
        val source = ImageSize(80, 60)
        val png = createFourColorImage(source, EncodedImageFormat.PNG)
        val request = CropRenderRequest(source, CropTransform(zoom = 1.5f), ImageSize(40, 30))
        val previousEnabled = Stats.enabled
        Stats.enabled = true
        Stats.allocated.clear()
        try {
            repeat(12) {
                val preview = codec.decode(
                    png,
                    DecodeRequest(maxDimension = 50, maxPixels = 2_000L),
                ).bitmap
                val crop = codec.renderCrop(png, request)

                assertColorNear(Color.RED, preview.asSkiaBitmap().getColor(5, 5), tolerance = 8)
                assertEquals(request.outputSize.width, crop.width)
                preview.asSkiaBitmap().close()
                crop.asSkiaBitmap().close()
            }

            listOf("Data", "Codec", "Image", "Surface", "Bitmap").forEach { resource ->
                assertEquals(0, Stats.allocated[resource] ?: 0, "$resource ownership imbalance")
            }
        } finally {
            Stats.allocated.clear()
            Stats.enabled = previousEnabled
        }
    }

    @Test
    fun returnedBitmapOwnsPixelsAfterSnapshotCloses() = runBlocking {
        val source = ImageSize(80, 60)
        val png = createFourColorImage(source, EncodedImageFormat.PNG)
        val previousEnabled = Stats.enabled
        Stats.enabled = true
        Stats.allocated.clear()
        try {
            val rendered = codec.renderCrop(
                png,
                CropRenderRequest(source, CropTransform(), ImageSize(40, 30)),
            )

            assertColorNear(Color.RED, rendered.asSkiaBitmap().getColor(5, 5), tolerance = 8)
            assertEquals(0, Stats.allocated["Image"] ?: 0, "snapshot must be closed")
            assertEquals(0, Stats.allocated["Surface"] ?: 0, "surface must be closed")
            assertEquals(1, Stats.allocated["Bitmap"] ?: 0, "only returned pixels stay owned")

            rendered.asSkiaBitmap().close()
            assertEquals(0, Stats.allocated["Bitmap"] ?: 0)
        } finally {
            Stats.allocated.clear()
            Stats.enabled = previousEnabled
        }
    }

    @Test
    fun malformedBytesAreRejectedForDecodeAndCrop() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)

        assertFailsWith<PrintImageFailure.UnsupportedFormat> {
            codec.decode(bytes, DecodeRequest(maxDimension = 2_048, maxPixels = 16_000_000L))
        }
        assertFailsWith<PrintImageFailure.UnsupportedFormat> {
            codec.renderCrop(
                bytes,
                CropRenderRequest(ImageSize(12, 7), CropTransform(), ImageSize(20, 10)),
            )
        }
        Unit
    }

    @Test
    fun cropRejectsOriginalSizeMismatch() = runBlocking {
        val png = createEncodedImage(40, 30, EncodedImageFormat.PNG)

        assertFailsWith<PrintImageFailure.RenderFailed> {
            codec.renderCrop(
                png,
                CropRenderRequest(ImageSize(41, 30), CropTransform(), ImageSize(20, 10)),
            )
        }
        Unit
    }
}

private fun createEncodedImage(
    width: Int,
    height: Int,
    format: EncodedImageFormat,
): ByteArray = createEncodedImage(ImageSize(width, height), format)

private fun createOwnedImageBitmap(): ImageBitmap =
    Surface.makeRasterN32Premul(2, 2).use { surface ->
        surface.makeImageSnapshot().use { image ->
            Bitmap.makeFromImage(image).asComposeImageBitmap()
        }
    }

private fun createEncodedImage(
    size: ImageSize,
    format: EncodedImageFormat,
): ByteArray = Surface.makeRasterN32Premul(size.width, size.height).use { surface ->
    surface.canvas.clear(Color.RED)
    surface.makeImageSnapshot().use { image ->
        requireNotNull(image.encodeToData(format, 100)).use { data -> data.bytes }
    }
}

private fun createFourColorImage(
    size: ImageSize,
    format: EncodedImageFormat,
): ByteArray = Surface.makeRasterN32Premul(size.width, size.height).use { surface ->
    Paint().use { paint ->
        val halfWidth = size.width / 2f
        val halfHeight = size.height / 2f
        listOf(
            Rect.makeXYWH(0f, 0f, halfWidth, halfHeight) to Color.RED,
            Rect.makeXYWH(halfWidth, 0f, halfWidth, halfHeight) to Color.GREEN,
            Rect.makeXYWH(0f, halfHeight, halfWidth, halfHeight) to Color.BLUE,
            Rect.makeXYWH(halfWidth, halfHeight, halfWidth, halfHeight) to Color.YELLOW,
        ).forEach { (rect, color) ->
            paint.color = color
            surface.canvas.drawRect(rect, paint)
        }
    }
    surface.makeImageSnapshot().use { image ->
        requireNotNull(image.encodeToData(format, 100)).use { data -> data.bytes }
    }
}

private fun createStripedPng(size: ImageSize): ByteArray =
    Surface.makeRasterN32Premul(size.width, size.height).use { surface ->
        Paint().use { paint ->
            var x = 0
            while (x < size.width) {
                paint.color = if (x / 2 % 2 == 0) Color.BLACK else Color.WHITE
                surface.canvas.drawRect(
                    Rect.makeXYWH(x.toFloat(), 0f, 2f, size.height.toFloat()),
                    paint,
                )
                x += 2
            }
        }
        surface.makeImageSnapshot().use { image ->
            requireNotNull(image.encodeToData(EncodedImageFormat.PNG, 100)).use { data -> data.bytes }
        }
    }

private fun expectedFourColorAtOutput(
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
    return when {
        sourceX < request.originalSize.width / 2.0 &&
                sourceY < request.originalSize.height / 2.0 -> Color.RED
        sourceX >= request.originalSize.width / 2.0 &&
                sourceY < request.originalSize.height / 2.0 -> Color.GREEN
        sourceX < request.originalSize.width / 2.0 -> Color.BLUE
        else -> Color.YELLOW
    }
}

private fun legacyStripeStats(preview: ImageBitmap, request: CropRenderRequest): StripeStats {
    val transform = CropRenderPlanner().plan(request).sourceToOutput
    val previewBitmap = preview.asSkiaBitmap()
    val sourcePerPreviewX = request.originalSize.width.toDouble() / previewBitmap.width
    val sourcePerPreviewY = request.originalSize.height.toDouble() / previewBitmap.height
    return Surface.makeRasterN32Premul(request.outputSize.width, request.outputSize.height).use { surface ->
        surface.canvas.clear(Color.TRANSPARENT)
        Image.makeFromBitmap(previewBitmap).use { previewImage ->
            surface.canvas.concat(
                Matrix33(
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
            surface.canvas.drawImage(previewImage, 0f, 0f)
        }
        surface.makeImageSnapshot().use { snapshot ->
            Bitmap.makeFromImage(snapshot).use(::stripeStats)
        }
    }
}

private fun stripeStats(bitmap: Bitmap): StripeStats {
    val luminance = IntArray(bitmap.width) { x -> Color.getR(bitmap.getColor(x, bitmap.height / 2)) }
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

private fun assertColorNear(expected: Int, actual: Int, tolerance: Int) {
    assertTrue(
        abs(Color.getR(expected) - Color.getR(actual)) <= tolerance,
        "red: expected=${Color.getR(expected)}, actual=${Color.getR(actual)}, color=$actual",
    )
    assertTrue(
        abs(Color.getG(expected) - Color.getG(actual)) <= tolerance,
        "green: expected=${Color.getG(expected)}, actual=${Color.getG(actual)}, color=$actual",
    )
    assertTrue(
        abs(Color.getB(expected) - Color.getB(actual)) <= tolerance,
        "blue: expected=${Color.getB(expected)}, actual=${Color.getB(actual)}, color=$actual",
    )
}

private fun ByteArray.withExifOrientation(orientation: Int): ByteArray {
    require(size >= 2 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte())
    val exifSegment = byteArrayOf(
        0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22,
        0x45, 0x78, 0x69, 0x66, 0x00, 0x00,
        0x49, 0x49, 0x2A, 0x00,
        0x08, 0x00, 0x00, 0x00,
        0x01, 0x00,
        0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
        orientation.toByte(), 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
    )
    return copyOfRange(0, 2) + exifSegment + copyOfRange(2, size)
}

private fun ByteArray.hasPngSignature(): Boolean {
    val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    return size >= signature.size && signature.indices.all { this[it] == signature[it] }
}
