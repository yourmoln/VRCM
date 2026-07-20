package io.github.vrcmteam.vrcm.presentation.screens.gallery.editor

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundedSourceReaderTest {
    @Test
    fun readsContentAtTheLimit() {
        val source = RecordingSource(totalBytes = 4)

        val bytes = readBoundedBytes(
            declaredSize = null,
            maxBytes = 4,
            openSource = { source },
        )

        assertContentEquals(ByteArray(4) { it.toByte() }, bytes)
        assertEquals(4, source.bytesRead)
        assertTrue(source.closed)
    }

    @Test
    fun rejectsContentThatExceedsTheLimit() {
        val source = RecordingSource(totalBytes = 100)

        assertFailsWith<PrintImageFailure.FileTooLarge> {
            readBoundedBytes(
                declaredSize = null,
                maxBytes = 4,
                openSource = { source },
            )
        }

        assertEquals(5, source.bytesRead)
        assertTrue(source.closed)
    }

    @Test
    fun rejectsKnownOversizedContentWithoutOpeningTheSource() {
        var sourceOpened = false

        assertFailsWith<PrintImageFailure.FileTooLarge> {
            readBoundedBytes(
                declaredSize = 5,
                maxBytes = 4,
                openSource = {
                    sourceOpened = true
                    RecordingSource(totalBytes = 0)
                },
            )
        }

        assertFalse(sourceOpened)
    }

    @Test
    fun cancellationCheckStopsFurtherReads() {
        val source = RecordingSource(totalBytes = 100, maxReadSize = 2)
        var checks = 0

        assertFailsWith<CancellationException> {
            readBoundedBytes(
                declaredSize = null,
                maxBytes = 10,
                openSource = { source },
                ensureActive = {
                    checks += 1
                    if (checks == 2) throw CancellationException("cancelled")
                },
            )
        }

        assertEquals(2, source.bytesRead)
        assertTrue(source.closed)
    }

    @Test
    fun closeFailureDoesNotReplaceFileTooLarge() {
        val closeFailure = IllegalStateException("close failed")
        val source = RecordingSource(
            totalBytes = 100,
            closeFailure = closeFailure,
        )

        val failure = assertFailsWith<PrintImageFailure.FileTooLarge> {
            readBoundedBytes(
                declaredSize = null,
                maxBytes = 4,
                openSource = { source },
            )
        }

        assertEquals(listOf(closeFailure), failure.suppressedExceptions)
    }
}

private class RecordingSource(
    private val totalBytes: Long,
    private val maxReadSize: Long = Long.MAX_VALUE,
    private val closeFailure: Throwable? = null,
) : RawSource {
    var bytesRead: Long = 0
        private set
    var closed: Boolean = false
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (bytesRead == totalBytes) return -1
        val readCount = minOf(byteCount, totalBytes - bytesRead, maxReadSize)
        sink.write(ByteArray(readCount.toInt()) { (bytesRead + it).toByte() })
        bytesRead += readCount
        return readCount
    }

    override fun close() {
        closed = true
        closeFailure?.let { throw it }
    }
}
