package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CropRenderPlannerTest {
    private val calculator = CropTransformCalculator()
    private val planner = CropRenderPlanner(calculator)

    @Test
    fun resetLandscapeCropMapsCenterAndVisibleSourceBounds() {
        val plan = planner.plan(
            CropRenderRequest(
                originalSize = ImageSize(2_400, 1_080),
                transform = CropTransform(),
                outputSize = ImageSize(1_920, 1_080),
            ),
        )

        assertPointEquals(FloatPoint(960f, 540f), plan.sourceToOutput.map(1_200f, 540f))
        assertEquals(PixelRect(240, 0, 2_160, 1_080), plan.visibleSourceBounds)
        assertEquals(ImageSize(1_920, 1_080), plan.outputSize)
    }

    @Test
    fun highZoomSelectsOnlySmallSourceRegion() {
        val source = ImageSize(6_000, 12_000)
        val bounds = planner.plan(
            CropRenderRequest(
                originalSize = source,
                transform = CropTransform(zoom = 3f, centerOffsetY = 0.25f),
                outputSize = ImageSize(1_920, 1_080),
            ),
        ).visibleSourceBounds

        assertTrue(bounds.width <= 2_100)
        assertTrue(bounds.height <= 1_200)
        assertInsideSource(bounds, source)
    }

    @Test
    fun negativeQuarterTurnsAreEquivalentToPositiveNormalizedTurns() {
        val source = ImageSize(4_032, 3_024)
        val output = ImageSize(1_920, 1_080)
        val negative = planner.plan(
            CropRenderRequest(
                originalSize = source,
                transform = CropTransform(
                    centerOffsetX = 0.1f,
                    centerOffsetY = -0.2f,
                    zoom = 1.6f,
                    quarterTurns = -1,
                    flipHorizontal = true,
                ),
                outputSize = output,
            ),
        )
        val positive = planner.plan(
            CropRenderRequest(
                originalSize = source,
                transform = CropTransform(
                    centerOffsetX = 0.1f,
                    centerOffsetY = -0.2f,
                    zoom = 1.6f,
                    quarterTurns = 3,
                    flipHorizontal = true,
                ),
                outputSize = output,
            ),
        )

        assertEquals(positive, negative)
    }

    @Test
    fun rotatedPannedAndFlippedCropMapsCenterUsingCalculatorTranslation() {
        val source = ImageSize(4_000, 3_000)
        val output = ImageSize(1_920, 1_080)
        val transform = CropTransform(
            centerOffsetX = 0.2f,
            centerOffsetY = -0.15f,
            zoom = 1.8f,
            quarterTurns = 1,
            flipHorizontal = true,
            flipVertical = true,
        )
        val geometry = calculator.geometry(source, output, transform)

        val plan = planner.plan(CropRenderRequest(source, transform, output))

        assertPointEquals(
            expected = FloatPoint(
                x = output.width / 2f + geometry.translationX,
                y = output.height / 2f + geometry.translationY,
            ),
            actual = plan.sourceToOutput.map(source.width / 2f, source.height / 2f),
        )
        assertInsideSource(plan.visibleSourceBounds, source)
    }

    @Test
    fun affineMappingMatchesPreviewTransformOrderForCornersAndCenter() {
        val source = ImageSize(3_840, 2_160)
        val output = ImageSize(1_920, 1_080)
        val transform = CropTransform(
            centerOffsetX = -0.18f,
            centerOffsetY = 0.12f,
            zoom = 1.7f,
            quarterTurns = 3,
            flipHorizontal = true,
            flipVertical = false,
        )
        val geometry = calculator.geometry(source, output, transform)
        val plan = planner.plan(CropRenderRequest(source, transform, output))
        val points = listOf(
            FloatPoint(0f, 0f),
            FloatPoint(source.width.toFloat(), 0f),
            FloatPoint(0f, source.height.toFloat()),
            FloatPoint(source.width.toFloat(), source.height.toFloat()),
            FloatPoint(source.width / 2f, source.height / 2f),
        )

        points.forEach { point ->
            assertPointEquals(
                expected = mapLikePreview(point, source, output, transform, geometry),
                actual = plan.sourceToOutput.map(point.x, point.y),
            )
        }
    }

    @Test
    fun extremeLegalPanIsClampedToNonEmptyInBoundsRegion() {
        val source = ImageSize(6_000, 4_000)
        val output = ImageSize(1_920, 1_080)

        val bounds = planner.plan(
            CropRenderRequest(
                originalSize = source,
                transform = CropTransform(
                    centerOffsetX = Float.MAX_VALUE,
                    centerOffsetY = -Float.MAX_VALUE,
                    zoom = 2.5f,
                    quarterTurns = 1,
                    flipVertical = true,
                ),
                outputSize = output,
            ),
        ).visibleSourceBounds

        assertInsideSource(bounds, source)
        assertTrue(bounds.width > 0)
        assertTrue(bounds.height > 0)
    }

    private fun mapLikePreview(
        point: FloatPoint,
        source: ImageSize,
        output: ImageSize,
        transform: CropTransform,
        geometry: RenderGeometry,
    ): FloatPoint {
        val turns = ((transform.quarterTurns % 4) + 4) % 4
        val unrotatedWidth = if (turns % 2 == 0) geometry.imageWidth else geometry.imageHeight
        val unrotatedHeight = if (turns % 2 == 0) geometry.imageHeight else geometry.imageWidth
        val flippedX = (point.x / source.width - 0.5f) * unrotatedWidth * geometry.scaleXSign
        val flippedY = (point.y / source.height - 0.5f) * unrotatedHeight * geometry.scaleYSign
        val rotated = when (turns) {
            0 -> FloatPoint(flippedX, flippedY)
            1 -> FloatPoint(-flippedY, flippedX)
            2 -> FloatPoint(-flippedX, -flippedY)
            else -> FloatPoint(flippedY, -flippedX)
        }
        return FloatPoint(
            x = output.width / 2f + geometry.translationX + rotated.x,
            y = output.height / 2f + geometry.translationY + rotated.y,
        )
    }

    private fun assertInsideSource(bounds: PixelRect, source: ImageSize) {
        assertTrue(bounds.left >= 0)
        assertTrue(bounds.top >= 0)
        assertTrue(bounds.right <= source.width)
        assertTrue(bounds.bottom <= source.height)
        assertTrue(bounds.right > bounds.left)
        assertTrue(bounds.bottom > bounds.top)
    }

    private fun assertPointEquals(expected: FloatPoint, actual: FloatPoint) {
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
    }
}
