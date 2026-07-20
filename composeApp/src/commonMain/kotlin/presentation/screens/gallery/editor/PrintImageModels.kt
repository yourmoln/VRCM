package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

data class ImageSize(
    val width: Int,
    val height: Int,
)

data class CropTransform(
    val centerOffsetX: Float = 0f,
    val centerOffsetY: Float = 0f,
    val zoom: Float = 1f,
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
)

data class RenderGeometry(
    val imageWidth: Float,
    val imageHeight: Float,
    val translationX: Float,
    val translationY: Float,
    val rotationDegrees: Float,
    val scaleXSign: Float,
    val scaleYSign: Float,
)
