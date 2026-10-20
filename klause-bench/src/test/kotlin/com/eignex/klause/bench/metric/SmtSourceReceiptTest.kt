package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class SmtSourceReceiptTest {
    @TempDir
    lateinit var directory: File

    @ParameterizedTest
    @CsvSource("1,valid", "2,invalid")
    @EnabledOnOs(OS.LINUX)
    @EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
    fun `Dolmen should check the exact model against the original assertion`(value: String, status: String) {
        val source = "(set-logic QF_LIA)\n(declare-const x Int)\n(assert (= x 1))\n(check-sat)\n".toByteArray()
        val model = "(\n(define-fun x () Int $value)\n)"
        val raw = "sat\n$model\n"
        val tool = SmtSourceTools.provision(SmtSourceValidation.GROUND, directory)
        val record = SolveRecord("original", "klause", "cp", 1, "free", 3, 10_000,
            "satisfy", false, true, null, 5, proven = false, stats = mapOf("exit" to "0"),
            gitSha = "producer", timestamp = "t", command = "cli original.smt2", finalWitness = model,
            sourceHashes = mapOf("model" to sha256(source)), sourceOutputSha256 = sha256(raw.toByteArray()),
            validationPolicy = "smt-original-polynomial-claims-v3")
        val producerBytes = (Reports.json.encodeToString(record) + "\n").toByteArray()

        val result = SmtSourceValidation.validateOriginal(source, record, raw, File(directory, "check"),
            tool, producerBytes)

        assertEquals(status, result.validation.status)
        assertEquals(model.takeIf { status == "valid" }, result.validation.checkedWitness)
        assertEquals("0", result.stats["sourceValidationSolverQueries"])
        assertFalse(SmtSourceValidation.apply(record, result).proven)
        assertEquals(source.decodeToString(), File(directory, "check/source.smt2").readText())
        assertEquals("sat\n$model\n", File(directory, "check/response.rsmt2").readText())
        assertContentEquals(producerBytes, File(directory, "check/record.json").readBytes())
    }

    @ParameterizedTest
    @CsvSource("false,valid", "true,unknown")
    @EnabledOnOs(OS.LINUX)
    @EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
    fun `reference should retain only an agreeing original refutation`(assertion: String, status: String) {
        val source = "(set-logic QF_LIA)\n(assert $assertion)\n(check-sat)\n".toByteArray()
        val raw = "unsat\n"
        val tool = SmtSourceTools.provision(SmtSourceValidation.REFUTATION, directory)
        val record = SolveRecord("original", "klause", "cp", 1, "free", 3, 10_000,
            "satisfy", false, false, null, null, proven = true, stats = mapOf("exit" to "0"),
            gitSha = "producer", timestamp = "t", command = "cli original.smt2",
            sourceHashes = mapOf("model" to sha256(source)), sourceOutputSha256 = sha256(raw.toByteArray()),
            validationPolicy = SMT_SOURCE_POLICY)

        val result = SmtSourceValidation.validateOriginal(source, record, raw, File(directory, "check"),
            tool)

        assertEquals(status, result.validation.status)
        assertEquals(status == "valid", SmtSourceValidation.apply(record, result).proven)
        assertEquals("1", result.stats["sourceValidationSolverQueries"])
        assertEquals("false", result.stats["sourceValidationProofChecked"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["timeout", "cleanup", "parser", "extra-output", "foreign-error"])
    fun `incomplete or unsupported tool responses should withhold acceptance`(failure: String) {
        val process = SmtToolProcess(listOf("dolmen"), if (failure == "parser") 3 else 0, 1,
            failure == "timeout", failure != "cleanup", if (failure == "extra-output") "unknown" else "",
            if (failure in setOf("parser", "foreign-error")) "E:parsing-error\n" else "",
            SourceToolMemory(SourceToolMemory.LINUX, SourceToolMemory.LIMIT, true, false))

        assertEquals("unknown", SmtSourceValidation.interpret(SmtSourceValidation.GROUND, process))
    }

    @ParameterizedTest
    @ValueSource(strings = ["sat", "unknown", "unsat\nunsat", "unsat\n(error bad)"])
    fun `reference answers other than one complete unsat should withhold refutation`(answer: String) {
        val process = SmtToolProcess(listOf("z3"), 0, 1, false, true, answer, "",
            SourceToolMemory(SourceToolMemory.LINUX, SourceToolMemory.LIMIT, true, false))

        assertEquals("unknown", SmtSourceValidation.interpret(SmtSourceValidation.REFUTATION, process))
    }

    @Test
    fun `changed external executable should not enter checking`() {
        val binary = File(directory, "tool").apply { writeText("changed"); setExecutable(true, true) }

        assertFailsWith<IllegalArgumentException> {
            SmtSourceValidation.Tool(binary.absoluteFile, "0".repeat(64)).verify()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `failed checking should release only its owned executable`(owned: Boolean) {
        val binary = File(directory, "tool").apply { writeText("opaque executable bytes") }
        val tool = SmtSourceValidation.Tool(binary, sha256(binary.readBytes()), owned = owned)

        assertFailsWith<UnsupportedOperationException> {
            tool.use { throw UnsupportedOperationException("checking failed") }
        }

        assertEquals(!owned, binary.exists())
    }

    @ParameterizedTest
    @ValueSource(strings = ["source", "stdout", "model"])
    fun `changed producer inputs should be rejected before invoking the tool`(input: String) {
        val source = "(set-logic QF_LIA)\n(assert true)\n(check-sat)\n".toByteArray()
        val model = "(\n)"
        val raw = "sat\n$model\n"
        val record = SolveRecord("original", "klause", "cp", 1, "free", 3, 10_000,
            "satisfy", false, true, null, 5, proven = false, stats = mapOf("exit" to "0"),
            gitSha = "producer", timestamp = "t", command = "cli original.smt2",
            finalWitness = if (input == "model") "(model)" else model,
            sourceHashes = mapOf("model" to sha256(source)), sourceOutputSha256 = sha256(raw.toByteArray()))

        val failure = assertFailsWith<IllegalArgumentException> {
            SmtSourceValidation.validateOriginal(
                if (input == "source") source + 10.toByte() else source,
                record,
                if (input == "stdout") raw + "; changed\n" else raw,
                directory,
                SmtSourceValidation.Tool(File(directory, "absent").absoluteFile, "0".repeat(64)),
            )
        }

        assertEquals(true, failure.message.orEmpty().contains("differs"))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `tool timeout should withhold a completed response`() {
        val input = Files.createFile(directory.toPath().resolve("input")).toFile()

        val process = SmtSourceReceipt.run(listOf("/bin/sleep", "60"), input, directory, 100)

        assertEquals(true, process.timedOut)
        assertEquals("unknown", SmtSourceValidation.interpret(SmtSourceValidation.GROUND, process))
    }

    @ParameterizedTest
    @CsvSource("linux,valid", "darwin,valid", "missing,unknown", "incomplete,unknown", "exceeded,unknown",
        "kernel-missing,unknown", "kernel-over-limit,unknown", "sample-over-limit,unknown",
        "sample-missing,unknown", "timing-missing,unknown", "foreign-policy,unknown")
    fun `memory qualification is required before accepting a completed tool response`(case: String, status: String) {
        val measured = SourceToolMemory(SourceToolMemory.DARWIN, SourceToolMemory.LIMIT, true, false,
            samplePeakBytes = 1024, kernelPeakBytes = 2048, samples = 1, timingSha256 = "0".repeat(64))
        val memory = when (case) {
            "linux" -> SourceToolMemory(SourceToolMemory.LINUX, SourceToolMemory.LIMIT, true, false)
            "darwin" -> measured
            "missing" -> null
            "incomplete" -> measured.copy(complete = false)
            "exceeded" -> measured.copy(exceeded = true)
            "kernel-missing" -> measured.copy(kernelPeakBytes = null)
            "kernel-over-limit" -> measured.copy(kernelPeakBytes = SourceToolMemory.LIMIT + 1)
            "sample-over-limit" -> measured.copy(samplePeakBytes = SourceToolMemory.LIMIT + 1)
            "sample-missing" -> measured.copy(samplePeakBytes = null)
            "timing-missing" -> measured.copy(timingSha256 = null)
            else -> measured.copy(policy = "tool-managed")
        }
        val process = SmtToolProcess(listOf("dolmen"), 0, 1, false, true, "", "", memory)

        assertEquals(status, SmtSourceValidation.interpret(SmtSourceValidation.GROUND, process))
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "malformed", "over-limit"])
    fun `incomplete or excessive kernel high water withholds memory qualification`(case: String) {
        for (terminalSample in listOf(false, true)) {
            val memory = DarwinToolMemory(directory, SourceToolMemory.LIMIT)
            val line = "2048  maximum resident set size\n"
            when (case) {
                "duplicate" -> memory.timingFile.writeText(line + line)
                "malformed" -> memory.timingFile.writeText("unknown maximum resident set size\n")
                "over-limit" -> memory.timingFile.writeText("${SourceToolMemory.LIMIT + 1} maximum resident set size\n")
            }
            if (terminalSample) assertTrue(memory.recordSample("1 0\n", setOf(1L), { emptySet() }))

            val result = memory.finish()

            assertFalse(result.qualified())
            assertEquals(case == "over-limit", result.exceeded)
        }
    }

    @Test
    fun `a fast child retains its kernel high water without inventing a sample`() {
        val memory = DarwinToolMemory(directory, SourceToolMemory.LIMIT)
        val bytes = "  0.01 real  0.00 user  0.00 sys\n    2048  maximum resident set size\n".toByteArray()
        memory.timingFile.writeBytes(bytes)

        val result = memory.finish()

        assertEquals(true, result.qualified())
        assertEquals(2048L, result.kernelPeakBytes)
        assertEquals(0, result.samples)
        assertEquals(null, result.samplePeakBytes)
        assertEquals(sha256(bytes), result.timingSha256)
    }

    @Test
    fun `an exiting zero RSS observation retains the preceding positive sample`() {
        val memory = DarwinToolMemory(directory, SourceToolMemory.LIMIT)
        memory.timingFile.writeText("2048 maximum resident set size\n")
        assertTrue(memory.recordSample("1 1\n", setOf(1L), { setOf(1L) }))
        var waited = false

        assertTrue(memory.recordSample("1 0\n", setOf(1L), { setOf(1L) }) {
            waited = true
            emptySet()
        })
        val result = memory.finish()

        assertTrue(waited)
        assertTrue(result.qualified())
        assertEquals(1, result.samples)
        assertEquals(1024L, result.samplePeakBytes)
        assertEquals(2048L, result.kernelPeakBytes)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `terminal zero RSS relies on kernel high water without inventing a sample`(initiallyLive: Boolean) {
        val memory = DarwinToolMemory(directory, SourceToolMemory.LIMIT)
        memory.timingFile.writeText("2048 maximum resident set size\n")

        assertTrue(memory.recordSample("1 0\n", setOf(1L), {
            if (initiallyLive) setOf(1L) else emptySet()
        }) { emptySet() })
        val result = memory.finish()

        assertTrue(result.qualified())
        assertEquals(0, result.samples)
        assertEquals(null, result.samplePeakBytes)
        assertEquals(2048L, result.kernelPeakBytes)
    }

    @Test
    fun `zero RSS from a live process withholds qualification`() {
        val memory = DarwinToolMemory(directory, SourceToolMemory.LIMIT)
        memory.timingFile.writeText("2048 maximum resident set size\n")

        assertFalse(memory.recordSample("1 0\n", setOf(1L), { setOf(1L) }))

        assertFalse(memory.finish().qualified())
    }

    @Test
    fun `a missing live process cannot be hidden by a later exit`() {
        val memory = DarwinToolMemory(directory, SourceToolMemory.LIMIT)
        memory.timingFile.writeText("2048 maximum resident set size\n")
        var waited = false

        assertFalse(memory.recordSample("1 0\n", setOf(1L, 2L), { setOf(1L, 2L) }) {
            waited = true
            emptySet()
        })

        assertFalse(waited)
        assertFalse(memory.finish().qualified())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "bad 0", "1", "1 -1", "1 0 extra", "2 0", "1 1\n1 0", "1 9223372036854775807"])
    fun `failed RSS observations retain rejection after terminal zero RSS`(text: String) {
        val memory = DarwinToolMemory(directory, SourceToolMemory.LIMIT)
        memory.timingFile.writeText("2048 maximum resident set size\n")
        assertTrue(memory.recordSample("1 1\n", setOf(1L), { setOf(1L) }))
        assertFalse(memory.recordSample(text, setOf(1L), { setOf(1L) }))

        assertTrue(memory.recordSample("1 0\n", setOf(1L), { emptySet() }))

        val result = memory.finish()
        assertFalse(result.qualified())
        assertEquals(1, result.samples)
        assertEquals(1024L, result.samplePeakBytes)
    }

    @Test
    fun `an excessive observation remains excessive after terminal zero RSS`() {
        val memory = DarwinToolMemory(directory, SourceToolMemory.LIMIT)
        memory.timingFile.writeText("2048 maximum resident set size\n")
        assertFalse(memory.recordSample("1 4194305\n", setOf(1L), { setOf(1L) }))

        assertFalse(memory.recordSample("1 0\n", setOf(1L), { emptySet() }))

        val result = memory.finish()
        assertFalse(result.qualified())
        assertTrue(result.exceeded)
        assertEquals(4294968320L, result.samplePeakBytes)
    }
}
