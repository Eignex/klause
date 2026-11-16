package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolverInvocationCaptureTest {
    @Test
    fun `a failed process retains its complete stdout and stderr artifacts`() {
        val directory = Files.createTempDirectory("cli-capture-").toFile()
        try {
            assertFailsWith<IllegalStateException> {
                SolverInvocation.invoke(
                    listOf("sh", "-c", "echo partial; echo failed >&2; exit 7"),
                    SolverInvocation.Dialect.MINIZINC,
                    captureDirectory = directory,
                )
            }
            assertEquals("partial\n", directory.resolve("cli.stdout").readText())
            assertEquals("failed\n", directory.resolve("cli.stderr").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a hard timeout retains its incumbent and termination reason`() {
        val result = SolverInvocation.invoke(
            listOf("sh", "-c", "echo sat; exec sleep 5"),
            SolverInvocation.Dialect.SMT_LIB,
            hardTimeoutMs = 100,
        )
        assertEquals(true, result.feasible)
        assertEquals("hard-timeout", result.stats["killed"])
        assertFalse(result.proven)
    }

    @Test
    fun `a fake process retains full raw stderr and a separate completion marker`() {
        val directory = Files.createTempDirectory("cli-stderr-").toFile()
        val trace = "x".repeat(20_000) + "\r\n% WARNING: portfolio arm ls/a quarantined: conflict\n"
        try {
            val result = SolverInvocation.invoke(listOf("fake"), SolverInvocation.Dialect.SMT_LIB,
                captureDirectory = directory,
                startProcess = { SolverInvocationFakeProcess(trace.byteInputStream(), stdout = "sat\n") })

            assertEquals(trace, directory.resolve("cli.stderr").readText())
            assertEquals("ls/a quarantined: conflict", result.stats[SolverInvocation.QUARANTINED])
            assertEquals(StderrCapture.RECORD_CAP, assertNotNull(result.stderr).length)
            assertFalse(result.stderrComplete)
            assertTrue(assertNotNull(result.stderrCapture).artifactComplete)
            assertEquals(result.stderrCapture,
                Reports.json.decodeFromString<StderrCaptureStatus>(directory.resolve("cli.stderr.json").readText()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a fake process refusal beyond the prefix remains unsupported`() {
        val trace = "x".repeat(20_000) + "\nklause MPS: declined\n"

        val result = SolverInvocation.invoke(listOf("fake"), SolverInvocation.Dialect.PB_COMPETITION,
            startProcess = { SolverInvocationFakeProcess(trace.byteInputStream(), exit = 2) })

        assertEquals("MPS: declined", result.stats["unsupported"])
        assertEquals("2", result.stats["exit"])
        assertFalse(result.stderrComplete)
        assertTrue(assertNotNull(result.stderrCapture).eof)
    }

    @ParameterizedTest
    @ValueSource(strings = ["java.lang.IllegalStateException: boom", "\t at solver.solve(Solver.kt:1)"])
    fun `a fake process crash beyond the prefix retains failed capture evidence`(diagnostic: String) {
        val directory = Files.createTempDirectory("cli-stderr-").toFile()
        val trace = "klause MPS: declined\n" + " ".repeat(20_000) + diagnostic
        try {
            val failure = assertFailsWith<SolverInvocation.Failure> {
                SolverInvocation.invoke(listOf("fake"), SolverInvocation.Dialect.PB_COMPETITION,
                    captureDirectory = directory,
                    startProcess = { SolverInvocationFakeProcess(trace.byteInputStream(), exit = 7) })
            }

            assertNull(failure.result.stats["unsupported"])
            assertEquals("7", failure.result.stats["exit"])
            assertEquals(trace, directory.resolve("cli.stderr").readText())
            assertTrue(assertNotNull(failure.result.stderrCapture).artifactComplete)
            assertEquals(failure.result.stderrCapture,
                Reports.json.decodeFromString<StderrCaptureStatus>(directory.resolve("cli.stderr.json").readText()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a fake process keeps draining after artifact creation fails`() {
        val directory = Files.createTempDirectory("cli-stderr-").toFile()
        directory.resolve("cli.stderr").mkdir()
        try {
            val result = SolverInvocation.invoke(listOf("fake"), SolverInvocation.Dialect.PB_COMPETITION,
                captureDirectory = directory,
                startProcess = { SolverInvocationFakeProcess("klause MPS: declined\n".byteInputStream(), exit = 2) })

            assertEquals("MPS: declined", result.stats["unsupported"])
            assertTrue(result.stderrComplete)
            val status = assertNotNull(result.stderrCapture)
            assertTrue(status.eof)
            assertNotNull(status.artifactFailure)
            assertFalse(status.artifactComplete)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a killed fake process cannot claim a complete stderr artifact`() {
        val directory = Files.createTempDirectory("cli-stderr-").toFile()
        try {
            val result = SolverInvocation.invoke(listOf("fake"), SolverInvocation.Dialect.SMT_LIB,
                captureDirectory = directory,
                startProcess = { SolverInvocationFakeProcess("partial\n".byteInputStream(), timeout = true) })

            assertEquals("hard-timeout", result.stats["killed"])
            assertFalse(result.stderrComplete)
            assertTrue(assertNotNull(result.stderrCapture).processKilled)
            assertFalse(result.stderrCapture.artifactComplete)
            assertEquals(result.stderrCapture,
                Reports.json.decodeFromString<StderrCaptureStatus>(directory.resolve("cli.stderr.json").readText()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an incomplete fake process read cannot turn an earlier refusal into unsupported`() {
        val stream = object : ByteArrayInputStream("klause MPS: declined\n".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (available() == 0) throw IOException("read failed")
                return super.read(buffer, offset, length)
            }
        }

        val failure = assertFailsWith<SolverInvocation.Failure> {
            SolverInvocation.invoke(listOf("fake"), SolverInvocation.Dialect.PB_COMPETITION,
                startProcess = { SolverInvocationFakeProcess(stream, exit = 2) })
        }

        assertNull(failure.result.stats["unsupported"])
        assertFalse(failure.result.stderrComplete)
        assertNotNull(assertNotNull(failure.result.stderrCapture).readFailure)
    }

    @Test
    fun `a completion marker failure remains visible in the solve metadata`() {
        val directory = Files.createTempDirectory("cli-stderr-").toFile()
        try {
            val result = SolverInvocation.invoke(listOf("fake"), SolverInvocation.Dialect.SMT_LIB,
                captureDirectory = directory,
                startProcess = {
                    directory.resolve("cli.stderr.json").mkdir()
                    SolverInvocationFakeProcess("complete\n".byteInputStream())
                })

            assertTrue(result.stderrComplete)
            assertNotNull(assertNotNull(result.stderrCapture).metadataFailure)
            assertTrue(result.stderrCapture.artifactComplete)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `stdout capture failure after process exit does not claim a forced kill`() {
        val directory = Files.createTempDirectory("cli-stderr-").toFile()
        directory.resolve("cli.stdout").mkdir()
        try {
            val failure = assertFailsWith<SolverInvocation.Failure> {
                SolverInvocation.invoke(listOf("fake"), SolverInvocation.Dialect.SMT_LIB,
                    captureDirectory = directory,
                    startProcess = { SolverInvocationFakeProcess("complete\n".byteInputStream()) })
            }

            assertNull(failure.result.stats["killed"])
            assertEquals("0", failure.result.stats["exit"])
            assertFalse(assertNotNull(failure.result.stderrCapture).processKilled)
            assertTrue(failure.result.stderrCapture.artifactComplete)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `stdout closed by a hard timeout preserves the kill reason and partial result`() {
        val killed = CountDownLatch(1)
        val stdout = object : InputStream() {
            override fun read(): Int {
                check(killed.await(100, TimeUnit.MILLISECONDS))
                throw IOException("pipe closed by kill")
            }
        }

        val result = SolverInvocation.invoke(listOf("fake"), SolverInvocation.Dialect.SMT_LIB,
            startProcess = {
                SolverInvocationFakeProcess("partial\n".byteInputStream(), timeout = true,
                    stdoutStream = stdout, onDestroy = { killed.countDown() })
            })

        assertEquals("hard-timeout", result.stats["killed"])
        assertTrue(assertNotNull(result.stats["outputCaptureFailure"]).contains("pipe closed by kill"))
        assertNull(result.feasible)
        assertFalse(result.stderrComplete)
        assertTrue(assertNotNull(result.stderrCapture).processKilled)
    }
}

internal class SolverInvocationFakeProcess(
    private val stderr: InputStream,
    stdout: String = "",
    private val exit: Int = 0,
    private val timeout: Boolean = false,
    private val stdoutStream: InputStream = ByteArrayInputStream(stdout.toByteArray()),
    private val onDestroy: () -> Unit = {},
) : Process() {
    @Volatile private var destroyed = false
    override fun getInputStream(): InputStream = stdoutStream
    override fun getErrorStream(): InputStream = stderr
    override fun getOutputStream(): ByteArrayOutputStream = ByteArrayOutputStream()
    override fun waitFor(): Int = exit
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !this.timeout || destroyed
    override fun exitValue(): Int = if (isAlive()) throw IllegalThreadStateException() else exit
    override fun isAlive(): Boolean = timeout && !destroyed
    override fun descendants(): Stream<ProcessHandle> = Stream.empty()
    override fun destroy() {
        destroyed = true
        onDestroy()
    }
    override fun destroyForcibly(): Process = apply { destroy() }
}
