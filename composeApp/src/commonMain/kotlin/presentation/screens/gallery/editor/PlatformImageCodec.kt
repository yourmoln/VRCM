package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import androidx.compose.ui.graphics.ImageBitmap

interface PlatformImageCodec {
    suspend fun decode(bytes: ByteArray, maxDimension: Int): DecodedImage

    suspend fun decode(bytes: ByteArray, request: DecodeRequest): DecodedImage =
        decode(bytes, request.maxDimension)

    suspend fun encodePng(bitmap: ImageBitmap): ByteArray
}
