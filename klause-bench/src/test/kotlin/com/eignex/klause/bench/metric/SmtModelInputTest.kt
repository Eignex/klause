package com.eignex.klause.bench.metric

import com.eignex.klause.bench.runner.Budget
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.InterruptedIOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmtModelInputTest {
    @Test
    fun `single query staging preserves original bytes and requests its model before exit`() {
        val root = Files.createTempDirectory("smt-input-test").toFile()
        val source = "; (check-sat)\n(set-logic QF_LIA)\n" +
            "(set-info :notes \"quoted \"\"(check-sat)\"\"\")\n" +
            "(declare-const |x (check-sat)| Int)\n(assert (= |x (check-sat)| 3))\n(check-sat)\n(exit)\n"
        val original = root.resolve("input.smt2").apply { writeText(source) }
        var staged: File? = null
        try {
            val result = SolverInvocation.withSourceInput(original, Budget(1000),
                prepareInput = { input, checkpoint, solve -> SmtModelInput.withInput(input, checkpoint, solve) }) {
                    file,
                    remaining,
                ->
                staged = file
                assertTrue(remaining.timeoutMillis in 1..1000)
                assertEquals("(set-option :produce-models true)\n" +
                    source.replace("\n(check-sat)\n", "\n(check-sat)\n(get-model)\n\n"), file.readText())
                SolverInvocation.Result(true, null, null, proven = false, stats = emptyMap(),
                    stdout = SolverOutput.Text("sat\n(\n)\n"), command = file.path)
            }

            assertEquals(source, original.readText())
            assertFalse(assertNotNull(staged).exists())
            assertEquals("requested", result.stats["smtModelRequest"])
            assertNotNull(result.stats["sourcePreparationMs"])
            assertEquals(sha256(source.toByteArray()), result.compiledInputHashes["smtOriginal"])
            assertTrue(result.compiledInputHashes["smtOriginal"] != result.compiledInputHashes["smtEffective"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `existing model requests and production options remain byte identical`() {
        val source = "(set-option :produce-models true)\n(check-sat)\n(get-model)\n(exit)"

        assertEquals(source, SmtModelInput.augment(source))
    }

    @Test
    fun `model staging polls its containing allowance before launching the solver`() {
        val root = Files.createTempDirectory("smt-input-test").toFile()
        val original = root.resolve("input.smt2").apply { writeText("(check-sat)\n") }
        var checkpoints = 0
        try {
            assertFailsWith<InterruptedIOException> {
                SmtModelInput.withInput(original, {
                    if (++checkpoints == 2) throw InterruptedIOException("stopped")
                }) { error("solver must not launch") }
            }

            assertEquals(2, checkpoints)
            assertEquals("(check-sat)\n", original.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "(set-option :produce-models false) (check-sat)",
        "(check-sat) (check-sat)",
        "(push 1) (check-sat)",
        "(check-sat-assuming (true))",
        "(check-sat) (assert false)",
        "(set-option :regular-output-channel \"result.txt\") (check-sat)",
    ])
    fun `unsupported augmentation runs the original input and records why`(source: String) {
        val root = Files.createTempDirectory("smt-input-test").toFile()
        val original = root.resolve("input.smt2").apply { writeText(source) }
        try {
            val result = SmtModelInput.withInput(original, {}) { file ->
                assertEquals(original, file)
                SolverInvocation.Result(false, null, null, proven = true, stats = emptyMap(),
                    stdout = SolverOutput.Text("unsat\n"), command = file.path)
            }

            assertEquals(false, result.feasible)
            assertTrue(result.proven)
            assertEquals("unsupported", result.stats["smtModelRequest"])
            assertEquals(result.compiledInputHashes["smtOriginal"], result.compiledInputHashes["smtEffective"])
            assertTrue(result.stats.getValue("smtModelRequestReason").isNotBlank())
            assertEquals(source, original.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed staged invocations retire the wrapper and retain both identities`() {
        val root = Files.createTempDirectory("smt-input-test").toFile()
        val original = root.resolve("input.smt2").apply { writeText("(check-sat)\n") }
        var staged: File? = null
        try {
            val failure = assertFailsWith<SolverInvocation.Failure> {
                SmtModelInput.withInput(original, {}) { file ->
                    staged = file
                    throw SolverInvocation.Failure(SolverInvocation.Result(null, null, null, proven = false,
                        stats = emptyMap(), stdout = SolverOutput.Text("partial"), command = file.path), "failed")
                }
            }

            assertFalse(assertNotNull(staged).exists())
            assertEquals(SolverOutput.Text("partial"), failure.result.stdout)
            assertEquals(setOf("smtOriginal", "smtEffective"), failure.result.compiledInputHashes.keys)
            assertEquals("(check-sat)\n", original.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
