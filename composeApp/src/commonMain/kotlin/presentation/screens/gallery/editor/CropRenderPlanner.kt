package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import kotlin.math.ceil
import kotlin.math.floor

class CropRenderPlanner(
    private val calculator: CropTransformCalculator = CropTransformCalculator(),
) {
    fun plan(request: CropRenderRequest): CropRenderPlan {
        val source = request.originalSize
        val output = request.outputSize
        val geometry = calculator.geometry(source, output, request.transform)
        val turns = normalizeTurns(request.transform.quarterTurns)
        val uniformScale = if (turns % 2 == 0) {
            geometry.imageWidth / source.width
        } else {
            geometry.imageHeight / source.width
        }
        val flipX = geometry.scaleXSign
        val flipY = geometry.scaleYSign
        val rotation = rotation(turns)
        val scaleX = rotation.scaleX * uniformScale * flipX
        val skewX = rotation.skewX * uniformScale * flipY
        val skewY = rotation.skewY * uniformScale * flipX
        val scaleY = rotation.scaleY * uniformScale * flipY
        val outputCenterX = output.width / 2f + geometry.translationX
        val outputCenterY = output.height / 2f + geometry.translationY
        val sourceCenterX = source.width / 2f
        val sourceCenterY = source.height / 2f
        val sourceToOutput = AffineTransform(
            scaleX = scaleX,
            skewX = skewX,
            translateX = outputCenterX - scaleX * sourceCenterX - skewX * sourceCenterY,
            skewY = skewY,
            scaleY = scaleY,
            translateY = outputCenterY - skewY * sourceCenterX - scaleY * sourceCenterY,
        )

        return CropRenderPlan(
            sourceToOutput = sourceToOutput,
            visibleSourceBounds = visibleSourceBounds(sourceToOutput, source, output),
            outputSize = output,
        )
    }

    private fun visibleSourceBounds(
        transform: AffineTransform,
        source: ImageSize,
        output: ImageSize,
    ): PixelRect {
        val scaleX = transform.scaleX.toDouble()
        val skewX = transform.skewX.toDouble()
        val translateX = transform.translateX.toDouble()
        val skewY = transform.skewY.toDouble()
        val scaleY = transform.scaleY.toDouble()
        val translateY = transform.translateY.toDouble()
        val determinant = scaleX * scaleY - skewX * skewY
        val outputCorners = listOf(
            0.0 to 0.0,
            output.width.toDouble() to 0.0,
            0.0 to output.height.toDouble(),
            output.width.toDouble() to output.height.toDouble(),
        )
        val sourceCorners = outputCorners.map { point ->
            val translatedX = point.first - translateX
            val translatedY = point.second - translateY
            val sourceX = (scaleY * translatedX - skewX * translatedY) / determinant
            val sourceY = (-skewY * translatedX + scaleX * translatedY) / determinant
            sourceX to sourceY
        }
        val left = floor(sourceCorners.minOf { it.first }).toInt().coerceIn(0, source.width)
        val top = floor(sourceCorners.minOf { it.second }).toInt().coerceIn(0, source.height)
        val right = ceil(sourceCorners.maxOf { it.first }).toInt().coerceIn(0, source.width)
        val bottom = ceil(sourceCorners.maxOf { it.second }).toInt().coerceIn(0, source.height)
        val horizontal = nonEmptyRange(left, right, source.width)
        val vertical = nonEmptyRange(top, bottom, source.height)
        return PixelRect(horizontal.first, vertical.first, horizontal.second, vertical.second)
    }

    private fun nonEmptyRange(start: Int, end: Int, limit: Int): Pair<Int, Int> =
        if (end > start) {
            start to end
        } else if (start == limit) {
            (limit - 1) to limit
        } else {
            start to (start + 1)
        }

    private fun rotation(turns: Int): LinearTransform = when (turns) {
        0 -> LinearTransform(1f, 0f, 0f, 1f)
        1 -> LinearTransform(0f, -1f, 1f, 0f)
        2 -> LinearTransform(-1f, 0f, 0f, -1f)
        else -> LinearTransform(0f, 1f, -1f, 0f)
    }

    private fun normalizeTurns(turns: Int): Int = ((turns % 4) + 4) % 4

    private data class LinearTransform(
        val scaleX: Float,
        val skewX: Float,
        val skewY: Float,
        val scaleY: Float,
    )
}
