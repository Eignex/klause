package com.eignex.klause.bench.metric

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test

class MpsSourceReceiptTest {
    @TempDir
    lateinit var directory: File
    private val stdout = "Read MPS: 1\nMIP has 2 vars and 1 constraints\nRead SOL: 1\n" +
        "Objective value computed by solver: 9\n\nIntegrality tolerance: 0\nLinear tolerance: 0\n" +
        "Objective tolerance: 0\n\nCheck SOL: Integrality 1 Constraints 1 Objective 1\n" +
        "Maximum violations: Integrality 0 Constraints 0 Objective 0\n"

    @ParameterizedTest
    @ValueSource(strings = ["Integrality", "Constraints", "Objective"])
    fun `successful exit should not admit failed checker booleans`(field: String) {
        val output = stdout.replace("$field 1", "$field 0")
        val process = MpsNativeProcess(
            emptyList(), 1, 0, null, true, 1, output, "",
            sha256(
                output.toByteArray(),
            ),
            sha256(byteArrayOf()),
        )

        val result = MpsSourceReceipt.interpret(process, 2, "0")

        assertFalse(result.integral && result.feasible && result.objective)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["partial", "duplicate", "corrupt", "census", "warning", "timeout", "cleanup", "hash", "data-limit"],
    )
    fun `incomplete or corrupt output should not establish a check`(variant: String) {
        val output = when (variant) {
            "partial" -> stdout.substringBefore("Maximum violations")
            "duplicate" -> stdout + "Check SOL: Integrality 1 Constraints 1 Objective 1\n"
            "corrupt" -> stdout.replace("Constraints 1", "Constraints true")
            "data-limit" -> "MIPLIB address KiB: 1 1\n" + stdout
            "census" -> stdout.replace("2 vars", "3 vars")
            else -> stdout
        }
        val error = if (variant == "warning") "unexpected variable<x> in solution file\n" else ""
        val process = MpsNativeProcess(
            emptyList(), 1, 0, if (variant == "timeout") "timeout" else null,
            variant != "cleanup", 1, output, error,
            if (variant == "hash") "wrong" else sha256(output.toByteArray()), sha256(error.toByteArray()),
        )

        assertFailsWith<IllegalArgumentException> { MpsSourceReceipt.interpret(process, 2, "0") }
    }

    @Test
    fun `Darwin resource wrapper should execute its supplied command`() {
        val command = MpsSourceReceipt.command("Mac OS X", listOf("/bin/echo", "checked argument with spaces"))

        val process = MpsSourceReceipt.run(command, directory)

        assertEquals(0, process.exit, process.stderr)
        assertTrue(process.stdout.startsWith("MIPLIB address KiB: "))
        assertTrue(process.stdout.endsWith("checked argument with spaces\n"))
        assertTrue(process.cleanupComplete)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `deadline should terminate and reap an owned checker`(nested: Boolean) {
        val command = if (nested) listOf("/bin/sh", "-c", "sleep 60 & echo \$!; wait")
            else listOf("/bin/sleep", "60")

        val process = MpsSourceReceipt.run(command, directory, 100)

        assertEquals("timeout", process.stop)
        assertTrue(process.cleanupComplete)
        assertFalse(ProcessHandle.of(process.pid).map { it.isAlive }.orElse(false))
        if (nested) {
            val child = process.stdout.trim().toLong()
            assertFalse(ProcessHandle.of(child).map { it.isAlive }.orElse(false))
        }
    }

    @Test
    fun `cancellation should terminate and reap an owned checker`() {
        val result = AtomicReference<MpsNativeProcess>()
        val worker = Thread { result.set(MpsSourceReceipt.run(listOf("/bin/sleep", "60"), directory)) }
        worker.start()
        val until = System.nanoTime() + 5_000_000_000
        while (!File(
                directory,
                "checker.stdout",
            ).exists() && worker.isAlive && System.nanoTime() < until
        ) {
            Thread.yield()
        }
        worker.interrupt()
        worker.join(6000)

        assertFalse(worker.isAlive)
        val process = assertNotNull(result.get())
        assertEquals("cancelled", process.stop)
        assertTrue(process.cleanupComplete)
    }
}
