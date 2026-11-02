package com.eignex.klause.bench.runner

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiniZincIdentityQueryTest {
    @Test
    fun `a successful query retains its option and complete output`() {
        val receipts = mutableListOf<MiniZincIdentityEvidence>()
        val query = MiniZincIdentityQuery(start = { _, log ->
            log.writeText("compiler version\n")
            Child()
        }, retain = receipts::add)

        val output = query.query(File("minizinc"), "--version")

        assertEquals("compiler version\n", output)
        assertEquals(listOf("starting", "process-started", "completed"), receipts.map { it.outcome })
        assertEquals("--version", receipts.last().phase)
        assertTrue(receipts.last().diagnosticComplete)
    }

    @Test
    fun `startup spends the query allowance before waiting`() {
        val child = Child(alive = true)
        val receipts = mutableListOf<MiniZincIdentityEvidence>()
        var now = 0L
        val query = MiniZincIdentityQuery(timeoutNs = 10, clock = { now }, start = { _, _ ->
            now = 11
            child
        }, retain = receipts::add)

        val failure = assertFailsWith<MiniZincIdentityFailure> { query.query(File("minizinc"), "--config-dirs") }

        assertEquals("timed-out", failure.evidence.outcome)
        assertEquals(11L, failure.evidence.startupNs)
        assertEquals(listOf(TimeUnit.SECONDS.toNanos(2)), child.waitAllowances)
        assertTrue(child.killed)
        assertFalse(failure.evidence.aliveAfterCleanup)
    }

    @Test
    fun `a timed out query retains partial output and the killed exit`() {
        val child = Child(alive = true)
        val query = MiniZincIdentityQuery(start = { _, log ->
            log.writeText("partial config")
            child
        }, retain = {})

        val failure = assertFailsWith<MiniZincIdentityFailure> { query.query(File("minizinc"), "--config-dirs") }

        assertEquals("timed-out", failure.evidence.outcome)
        assertEquals("partial config", failure.evidence.diagnostic)
        assertEquals(137, failure.evidence.exitCode)
        assertFalse(failure.evidence.diagnosticComplete)
    }

    @Test
    fun `an interrupted wait kills the child and restores cancellation`() {
        val child = Child(alive = true, interrupted = true)
        val query = MiniZincIdentityQuery(start = { _, _ -> child }, retain = {})
        try {
            val failure = assertFailsWith<MiniZincIdentityFailure> { query.query(File("minizinc"), "--version") }

            assertTrue(child.killed)
            assertTrue(failure.evidence.cancelled)
            assertEquals("cancelled", failure.evidence.outcome)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a start failure deletes its temporary output and retains its phase`() {
        var output: File? = null
        val query = MiniZincIdentityQuery(start = { _, log ->
            output = log
            error("cannot start")
        }, retain = {})

        val failure = assertFailsWith<MiniZincIdentityFailure> { query.query(File("minizinc"), "--version") }

        assertEquals("start-failed", failure.evidence.outcome)
        assertEquals("cannot start", failure.evidence.diagnostic)
        assertFalse(requireNotNull(output).exists())
    }

    @Test
    fun `a failed query cannot return a partial identity`() {
        val query = MiniZincIdentityQuery(start = { _, log ->
            log.writeText("version from a failed process")
            Child(exit = 1)
        }, retain = {})

        val failure = assertFailsWith<MiniZincIdentityFailure> { query.query(File("minizinc"), "--version") }

        assertEquals("exited", failure.evidence.outcome)
        assertEquals(1, failure.evidence.exitCode)
    }

    @Test
    fun `cancellation before startup never starts a compiler`() {
        var started = false
        val query = MiniZincIdentityQuery(start = { _, _ ->
            started = true
            Child()
        }, retain = {})
        Thread.currentThread().interrupt()
        try {
            val failure = assertFailsWith<MiniZincIdentityFailure> { query.query(File("minizinc"), "--version") }

            assertFalse(started)
            assertTrue(failure.evidence.cancelled)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `truncated output cannot establish an identity`() {
        val query = MiniZincIdentityQuery(start = { _, log ->
            log.writeText("v".repeat(16385))
            Child()
        }, retain = {})

        val failure = assertFailsWith<MiniZincIdentityFailure> { query.query(File("minizinc"), "--version") }

        assertEquals("output-truncated", failure.evidence.outcome)
        assertEquals(16385L, failure.evidence.diagnosticBytes)
        assertEquals(16384, failure.evidence.diagnostic.length)
        assertFalse(failure.evidence.diagnosticComplete)
    }

    @Test
    fun `a process that survives cleanup retains an unavailable exit`() {
        val child = Child(alive = true, refusesKill = true)
        val query = MiniZincIdentityQuery(start = { _, _ -> child }, retain = {})

        val failure = assertFailsWith<MiniZincIdentityFailure> { query.query(File("minizinc"), "--version") }

        assertTrue(failure.evidence.aliveAfterCleanup)
        assertEquals(null, failure.evidence.exitCode)
        assertTrue(requireNotNull(failure.evidence.cleanupFailure).contains("kill refused"))
    }

    @Test
    fun `empty output cannot establish an identity`() {
        val query = MiniZincIdentityQuery(start = { _, _ -> Child() }, retain = {})

        val failure = assertFailsWith<MiniZincIdentityFailure> { query.query(File("minizinc"), "--version") }

        assertEquals("empty-output", failure.evidence.outcome)
    }

    private class Child(
        private var alive: Boolean = false,
        private val interrupted: Boolean = false,
        private val exit: Int = 0,
        private val refusesKill: Boolean = false,
    ) : Process() {
        var killed = false
        val waitAllowances = mutableListOf<Long>()
        override fun isAlive() = alive
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitAllowances += unit.toNanos(timeout)
            if (interrupted && !killed) throw InterruptedException("cancelled")
            return !alive
        }
        override fun waitFor(): Int = exitValue()
        override fun exitValue(): Int {
            check(!alive)
            return if (killed) 137 else exit
        }
        override fun destroy() { destroyForcibly() }
        override fun destroyForcibly(): Process {
            check(!refusesKill) { "kill refused" }
            killed = true
            alive = false
            return this
        }
        override fun descendants(): Stream<ProcessHandle> = Stream.empty()
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    }
}
