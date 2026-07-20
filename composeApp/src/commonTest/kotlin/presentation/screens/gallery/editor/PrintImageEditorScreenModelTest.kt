package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import androidx.compose.ui.graphics.ImageBitmap
import io.github.vrcmteam.vrcm.network.api.prints.data.PrintData
import io.github.vrcmteam.vrcm.service.PrintUploader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PrintImageEditorScreenModelTest {
    @Test
    fun repeatedUploadWhileProcessingStartsOneJob() = runBlocking {
        val gate = CompletableDeferred<Result<ByteArray>>()
        val processor = FakePrintImageProcessor { _, _ -> gate.await() }
        val uploader = FakePrintUploader { _, _ -> Result.success(PrintData("print")) }
        val model = createModel(processor, uploader)

        model.upload()
        model.upload()

        assertEquals(EditorPhase.Processing, model.state.value.phase)
        assertEquals(1, processor.renderCount)
        gate.complete(Result.success(PNG_BYTES))
        yield()

        assertEquals(1, uploader.uploadCount)
    }

    @Test
    fun processingFailureReturnsToReadyAndKeepsTransform() = runBlocking {
        val failure = PrintImageFailure.RenderFailed(IllegalStateException("render"))
        val processor = FakePrintImageProcessor { _, _ -> Result.failure(failure) }
        val uploader = FakePrintUploader { _, _ -> Result.success(PrintData("unused")) }
        val model = createModel(processor, uploader)
        model.panAndZoom(VIEWPORT, panX = 40f, panY = 0f, zoomChange = 1.5f)
        val edited = model.state.value.transform

        model.upload()
        yield()

        assertEquals(EditorPhase.Ready, model.state.value.phase)
        assertEquals(edited, model.state.value.transform)
        assertIs<EditorError.Processing>(model.state.value.error)
        assertEquals(0, uploader.uploadCount)
    }

    @Test
    fun networkRetryReusesRenderedPng() = runBlocking {
        val processor = FakePrintImageProcessor { _, _ -> Result.success(PNG_BYTES) }
        var attempt = 0
        val uploader = FakePrintUploader { _, _ ->
            attempt++
            if (attempt == 1) Result.failure(IllegalStateException("offline"))
            else Result.success(PrintData("print"))
        }
        val model = createModel(processor, uploader)
        val uploaded = async(start = CoroutineStart.UNDISPATCHED) { model.events.first() }

        model.upload()
        yield()
        assertEquals(EditorPhase.Ready, model.state.value.phase)
        assertIs<EditorError.Upload>(model.state.value.error)

        model.upload()
        yield()

        assertEquals(1, processor.renderCount)
        assertEquals(2, uploader.uploadCount)
        assertEquals(EditorEvent.Uploaded, uploaded.await())
    }

    @Test
    fun editingAfterNetworkFailureInvalidatesRenderedPng() = runBlocking {
        val processor = FakePrintImageProcessor { _, _ -> Result.success(PNG_BYTES) }
        var attempt = 0
        val uploader = FakePrintUploader { _, _ ->
            attempt++
            if (attempt == 1) Result.failure(IllegalStateException("offline"))
            else Result.success(PrintData("print"))
        }
        val model = createModel(processor, uploader)

        model.upload()
        yield()
        model.panAndZoom(VIEWPORT, panX = 30f, panY = 0f, zoomChange = 1f)
        model.upload()
        yield()

        assertEquals(2, processor.renderCount)
        assertEquals(2, uploader.uploadCount)
    }

    @Test
    fun successfulUploadUsesPngFileNameAndEmitsCompletion() = runBlocking {
        val processor = FakePrintImageProcessor { _, _ -> Result.success(PNG_BYTES) }
        val uploader = FakePrintUploader { _, _ -> Result.success(PrintData("print")) }
        val model = createModel(processor, uploader)
        val uploaded = async(start = CoroutineStart.UNDISPATCHED) { model.events.first() }

        model.upload()
        yield()

        assertEquals(EditorEvent.Uploaded, uploaded.await())
        assertTrue(requireNotNull(uploader.lastFileName).startsWith("print-"))
        assertTrue(requireNotNull(uploader.lastFileName).endsWith(".png"))
    }

    private fun createModel(
        processor: PrintImageProcessor,
        uploader: PrintUploader,
    ) = PrintImageEditorScreenModel(
        source = SelectedImage("source.jpg", byteArrayOf(1)),
        prepared = PreparedImage(ImageBitmap(16, 9), ImageSize(1_920, 1_080)),
        calculator = CropTransformCalculator(),
        processor = processor,
        uploader = uploader,
        workerDispatcher = Dispatchers.Unconfined,
        nowMillis = { 123L },
    )

    private companion object {
        val VIEWPORT = ImageSize(1_600, 900)
        val PNG_BYTES = byteArrayOf(1, 2, 3)
    }
}

private class FakePrintImageProcessor(
    private val renderBlock: suspend (SelectedImage, CropTransform) -> Result<ByteArray>,
) : PrintImageProcessor {
    var renderCount = 0

    override suspend fun prepare(source: SelectedImage): Result<PreparedImage> =
        error("prepare is not used by the editor model")

    override suspend fun render(
        source: SelectedImage,
        transform: CropTransform,
    ): Result<ByteArray> {
        renderCount++
        return renderBlock(source, transform)
    }
}

private class FakePrintUploader(
    private val uploadBlock: suspend (ByteArray, String) -> Result<PrintData>,
) : PrintUploader {
    var uploadCount = 0
    var lastFileName: String? = null

    override suspend fun upload(imageBytes: ByteArray, fileName: String): Result<PrintData> {
        uploadCount++
        lastFileName = fileName
        return uploadBlock(imageBytes, fileName)
    }
}
