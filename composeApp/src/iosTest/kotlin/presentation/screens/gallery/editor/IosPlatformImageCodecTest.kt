package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosPlatformImageCodecTest {
    private val codec = IosPlatformImageCodec()

    @Test
    fun pngRoundTripPreservesDimensions() = runBlocking {
        val decoded = codec.decode(createEncodedImage(12, 7, EncodedImageFormat.PNG), 2_048)

        assertEquals(ImageSize(12, 7), decoded.originalSize)
        assertEquals(12, decoded.bitmap.width)
        assertEquals(7, decoded.bitmap.height)
        assertTrue(codec.encodePng(decoded.bitmap).hasPngSignature())
    }

    @Test
    fun exifOrientationSixIsNormalized() = runBlocking {
        val jpeg = createEncodedImage(12, 7, EncodedImageFormat.JPEG).withExifOrientation(6)

        val decoded = codec.decode(jpeg, 2_048)

        assertEquals(ImageSize(7, 12), decoded.originalSize)
        assertEquals(7, decoded.bitmap.width)
        assertEquals(12, decoded.bitmap.height)
    }

    @Test
    fun malformedBytesAreRejected() = runBlocking {
        assertFailsWith<PrintImageFailure.UnsupportedFormat> {
            codec.decode(byteArrayOf(1, 2, 3), 2_048)
        }
        Unit
    }
}

private fun createEncodedImage(
    width: Int,
    height: Int,
    format: EncodedImageFormat,
): ByteArray {
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.clear(Color.RED)
    val image = surface.makeImageSnapshot()
    val data = requireNotNull(image.encodeToData(format, 100))
    val bytes = data.bytes
    data.close()
    image.close()
    surface.close()
    return bytes
}

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
