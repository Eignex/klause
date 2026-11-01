package com.eignex.klause.bench.metric

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StderrCaptureTest {
    @Test
    fun `a long unterminated line retains only the bounded prefix`() {
        var remaining = 128 * 1024
        val input = object : InputStream() {
            override fun read(): Int = error("bulk reads required")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= 8192)
                if (remaining == 0) return -1
                val count = minOf(length, remaining)
                buffer.fill('x'.code.toByte(), offset, offset + count)
                remaining -= count
                return count
            }
        }

        val result = StderrCapture(input).start().await(100, processKilled = false)

        assertEquals("x".repeat(StderrCapture.RECORD_CAP), result.text)
        assertEquals(128 * 1024L, result.status.bytesRead)
        assertTrue(result.status.recordTruncated)
        assertTrue(result.status.eof)
        assertFalse(result.complete)
        assertFalse(result.status.artifactRequested)
    }

    @Test
    fun `the artifact preserves raw bytes beyond the normalized prefix`() {
        val bytes = ("é\r\n" + "x".repeat(20_000) + "\rfinal").toByteArray()
        val artifact = ByteArrayOutputStream()

        val result = StderrCapture(ByteArrayInputStream(bytes)) { artifact }.start().await(100, processKilled = false)

        assertContentEquals(bytes, artifact.toByteArray())
        assertTrue(result.text.startsWith("é\n"))
        assertEquals(StderrCapture.RECORD_CAP, result.text.length)
        assertTrue(result.status.artifactComplete)
        assertFalse(result.complete)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r", "\r\n", ""])
    fun `a refusal split across reads is classified past the prefix`(ending: String) {
        val prefix = "x".repeat(20_000)
        val bytes = (prefix + "\nklause MPS: declined" + ending).toByteArray()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = if (pos < prefix.length) minOf(length, prefix.length - pos) else 1
                return super.read(buffer, offset, count)
            }
        }

        val result = StderrCapture(input).start().await(100, processKilled = false)

        assertEquals("MPS: declined", result.refusal)
        assertFalse(result.text.contains("klause"))
        assertTrue(result.status.streamComplete)
    }

    @ParameterizedTest
    @ValueSource(strings = ["java.lang.IllegalStateException: boom", "\t at solver.solve(Solver.kt:1)"])
    fun `late crash diagnostics override an earlier refusal even on long lines`(diagnostic: String) {
        val bytes = ("klause MPS: declined\n" + " ".repeat(20_000) + diagnostic).toByteArray()

        val result = StderrCapture(ByteArrayInputStream(bytes)).start().await(100, processKilled = false)

        assertNull(result.refusal)
        assertTrue(result.status.streamComplete)
        assertFalse(result.text.contains(diagnostic))
    }

    @Test
    fun `quarantine diagnostics beyond the prefix have a separate bound`() {
        val warning = "% WARNING: portfolio arm ls/a quarantined: conflict\n"
        val bytes = ("x".repeat(20_000) + "\n" + warning.repeat(200)).toByteArray()

        val result = StderrCapture(ByteArrayInputStream(bytes)).start().await(100, processKilled = false)

        assertTrue(assertNotNull(result.quarantines).startsWith("ls/a quarantined: conflict"))
        assertEquals(StderrCapture.DIAGNOSTIC_CAP, result.quarantines.length)
        assertTrue(result.status.diagnosticsTruncated)
        assertTrue(result.status.streamComplete)
    }

    @Test
    fun `an oversized refusal reason is explicitly truncated`() {
        val bytes = ("klause MPS: " + "x".repeat(20_000)).toByteArray()

        val result = StderrCapture(ByteArrayInputStream(bytes)).start().await(100, processKilled = false)

        assertTrue(assertNotNull(result.refusal).startsWith("MPS: "))
        assertTrue(result.refusal.length <= StderrCapture.DIAGNOSTIC_CAP)
        assertTrue(result.status.diagnosticsTruncated)
    }

    @ParameterizedTest
    @ValueSource(strings = ["open", "write", "flush", "close"])
    fun `artifact failures preserve draining and diagnostic classification`(stage: String) {
        val input = ByteArrayInputStream("klause MPS: declined\n".toByteArray())
        val artifact = object : OutputStream() {
            override fun write(value: Int) { if (stage == "write") throw IOException("write failed") }
            override fun flush() { if (stage == "flush") throw IOException("flush failed") }
            override fun close() { if (stage == "close") throw IOException("close failed") }
        }

        val result = StderrCapture(input) {
            if (stage == "open") throw IOException("open failed")
            artifact
        }.start().await(100, processKilled = false)

        assertEquals("MPS: declined", result.refusal)
        assertTrue(result.complete)
        assertTrue(result.status.eof)
        assertFalse(result.status.artifactComplete)
        assertTrue(assertNotNull(result.status.artifactFailure).contains("$stage failed"))
    }

    @Test
    fun `a failed read cannot certify a refusal or complete artifact`() {
        val input = object : ByteArrayInputStream("klause MPS: declined\n".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (available() == 0) throw IOException("read failed")
                return super.read(buffer, offset, length)
            }
        }

        val result = StderrCapture(input) { ByteArrayOutputStream() }.start().await(100, processKilled = false)

        assertNull(result.refusal)
        assertFalse(result.complete)
        assertFalse(result.status.eof)
        assertFalse(result.status.artifactComplete)
        assertTrue(assertNotNull(result.status.readFailure).contains("read failed"))
        assertEquals("klause MPS: declined\n", result.text)
    }

    @Test
    fun `a blocked reader returns incomplete metadata within the drain allowance`() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                release.await()
                return -1
            }
            override fun close() {
                release.countDown()
                closed.countDown()
            }
        }
        val capture = StderrCapture(input).start()
        try {
            assertTrue(reading.await(100, TimeUnit.MILLISECONDS))

            val result = capture.await(1, processKilled = false)

            assertTrue(result.status.drainTimedOut)
            assertFalse(result.complete)
            assertNull(result.refusal)
            assertTrue(closed.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `EOF after a kill does not establish complete capture`() {
        val result = StderrCapture(ByteArrayInputStream("partial".toByteArray())) { ByteArrayOutputStream() }
            .start().await(100, processKilled = true)

        assertTrue(result.status.eof)
        assertTrue(result.status.processKilled)
        assertFalse(result.status.artifactComplete)
        assertFalse(result.complete)
    }
}
