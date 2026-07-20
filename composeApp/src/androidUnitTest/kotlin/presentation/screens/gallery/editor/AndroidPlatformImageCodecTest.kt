package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

        val decoded = codec.decode(jpeg, 2_048)

        assertEquals(ImageSize(7, 12), decoded.originalSize)
        assertEquals(7, decoded.bitmap.asAndroidBitmap().width)
        assertEquals(12, decoded.bitmap.asAndroidBitmap().height)
    }

    @Test
    fun malformedBytesAreRejected() = runBlocking {
        assertFailsWith<PrintImageFailure.UnsupportedFormat> {
            codec.decode(byteArrayOf(1, 2, 3), 2_048)
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
