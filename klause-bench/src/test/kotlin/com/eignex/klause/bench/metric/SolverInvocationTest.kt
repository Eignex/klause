package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.bench.source.SourceStaging
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolverInvocationTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initializeCodec() {
            ZstdOutputStreamNoFinalizer(ByteArrayOutputStream(), 1).use { it.write(ByteArray(0)) }
        }
    }

    @ParameterizedTest
    @CsvSource("auto,true,true", "auto,false,false", "true,true,true", "true,false,true",
        "false,true,false", "false,false,false")
    fun `explicit verbosity should control logging without changing solver parameters`(
        requested: String,
        companion: Boolean,
        expectedVerbose: Boolean,
    ) {
        val root = Files.createTempDirectory("solver-command").toFile()
        try {
            val executable = root.resolve("klause-cli").apply {
                writeText("fixture")
                setExecutable(true)
            }
            val source = root.resolve("source.cnf")
            val ref = ProblemRef("source", Format.DIMACS, ProblemSource.Prepared(source), Category.CSP,
                Expected.Unknown)
            val entry = ResolvedProblem(ref, lazy { error("command construction must not parse the source") })
            val parameters = if (companion) listOf("companion-integers=true", "source-incumbent-completion=true")
            else emptyList()
            val settings = SolverInvocation.Settings(params = parameters,
                verbose = requested.takeUnless { it == "auto" }?.toBooleanStrict())

            val command = SolverInvocation.klauseCommand(entry, settings, Budget(1000), false, source, executable)

            assertEquals(expectedVerbose, "-v" in command)
            assertEquals(parameters, command.windowed(2).filter { it.first() == "--param" }.map { it.last() })
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["cache", "temporary"])
    fun `compressed input reaches the solver as original bytes and is retired afterwards`(target: String) {
        val root = Files.createTempDirectory("solver-source").toFile()
        try {
            val body = "p cnf 2 1\n1 -2 0\n"
            val packed = File(root, "input.cnf.zst")
            ZstdOutputStreamNoFinalizer(packed.outputStream(), 1).use { it.write(body.toByteArray()) }
            var staged: File? = null

            val staging = SourceStaging.parse(target)
            val result = SolverInvocation.withSourceInput(packed, Budget(10_000), staging = staging) {
                    plain,
                    remaining,
                ->
                staged = plain
                assertEquals(body, plain.readText())
                assertEquals(staging.directory.canonicalFile, plain.parentFile.parentFile.canonicalFile)
                assertTrue(remaining.timeoutMillis in 1..10_000)
                SolverInvocation.Result(true, null, 2L, proven = false,
                    stats = emptyMap(), rawOutput = "sat", command = plain.path, elapsedMs = 3L)
            }

            assertFalse(assertNotNull(staged).exists())
            assertEquals(body, CorpusFiles.readText(packed))
            assertEquals(3L, result.elapsedMs)
            assertEquals(2L, result.timeToBestMs)
            assertTrue(listOf("sourceDecodeMs", "sourceCleanupMs", "sourceCliMs", "sourceInvocationMs")
                .all { it in result.stats })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `source preparation stops at its containing deadline before invoking the solver`() {
        val root = Files.createTempDirectory("solver-source").toFile()
        try {
            val packed = File(root, "input.cnf.zst")
            ZstdOutputStreamNoFinalizer(packed.outputStream(), 1).use { it.write(ByteArray(32 * 1024)) }
            var ticks = 0L

            val result = SolverInvocation.withSourceInput(packed, Budget(5), nanoTime = { ticks++ * 1_000_000 }) {
                    _,
                    _,
                ->
                error("deadline exhausted")
            }

            assertNull(result.feasible)
            assertEquals("true", result.stats["sourcePreparationBudgetExhausted"])
            assertEquals("0", result.stats["sourceCliMs"])
            assertTrue(requireNotNull(result.stats["sourceDecodeMs"]).toLong() >= 5L)
            assertTrue(requireNotNull(result.stats["sourceInvocationMs"]).toLong() >= 5L)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed invocations retain source timings and partial solver output`() {
        val root = Files.createTempDirectory("solver-source").toFile()
        try {
            val packed = File(root, "input.cnf.zst")
            ZstdOutputStreamNoFinalizer(packed.outputStream(), 1).use { it.write("p cnf 0 0\n".toByteArray()) }
            var staged: File? = null

            val failure = assertFailsWith<SolverInvocation.Failure> {
                SolverInvocation.withSourceInput(packed, Budget(10_000)) { plain, _ ->
                    staged = plain
                    throw SolverInvocation.Failure(SolverInvocation.Result(null, null, null, proven = false,
                        stats = mapOf("exit" to "2"), rawOutput = "partial", command = "cli", elapsedMs = 3), "failed")
                }
            }

            assertFalse(assertNotNull(staged).exists())
            assertEquals("partial", failure.result.rawOutput)
            assertEquals("2", failure.result.stats["exit"])
            assertEquals(3L, failure.result.elapsedMs)
            assertTrue(listOf("sourceDecodeMs", "sourceCleanupMs", "sourceCliMs", "sourceInvocationMs")
                .all { it in failure.result.stats })
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["cache", "cli"])
    fun `cancelled preparation retains timings without a solver verdict`(target: String) {
        try {
            Thread.currentThread().interrupt()

            val failure = assertFailsWith<SolverInvocation.Failure> {
                SolverInvocation.withSourceInput(File("missing.cnf.zst"), Budget(10_000),
                    staging = SourceStaging.parse(target), decoderIdentity = { error("cancelled identity") }) { _, _ ->
                    error("cancelled source")
                }
            }

            assertNull(failure.result.feasible)
            assertFalse(failure.result.proven)
            assertEquals("0", failure.result.stats["sourceCliMs"])
            assertTrue("sourceInvocationMs" in failure.result.stats)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `CLI delivery passes the original archive and charges decoder identity to preparation`() {
        val packed = File("original.cnf.zst")
        var nanos = 0L

        val result = SolverInvocation.withSourceInput(packed, Budget(1000), nanoTime = { nanos },
            staging = SourceStaging.CLI, decoderIdentity = { checkActive ->
                nanos = 3_000_000
                checkActive()
                mapOf("sourceDecoderSha256" to "identity")
            }) { input, remaining ->
            assertEquals(packed, input)
            assertEquals(997L, remaining.timeoutMillis)
            nanos += 12_000_000
            SolverInvocation.Result(null, null, null, proven = false,
                stats = emptyMap(), rawOutput = "unknown", command = "cli", elapsedMs = 10)
        }

        assertEquals("cli", result.stats["sourceStaging"])
        assertEquals("", result.stats["sourceStagingDirectory"])
        assertEquals("identity", result.stats["sourceDecoderSha256"])
        assertEquals("cli-frontend", result.stats["sourceDecodeLocation"])
        assertNull(result.stats["sourceDecodeMs"])
        assertEquals("3", result.stats["sourcePreparationMs"])
        assertEquals("0", result.stats["sourceCleanupMs"])
        assertEquals("12", result.stats["sourceCliMs"])
        assertEquals("15", result.stats["sourceInvocationMs"])
    }

    @Test
    fun `decoder identity exhaustion supplies no solver verdict`() {
        var nanos = 0L

        val result = SolverInvocation.withSourceInput(File("original.cnf.zst"), Budget(5), nanoTime = { nanos },
            staging = SourceStaging.CLI, decoderIdentity = { checkActive ->
                nanos = 5_000_000
                checkActive()
                emptyMap()
            }) { _, _ -> error("deadline exhausted") }

        assertNull(result.feasible)
        assertEquals("true", result.stats["sourcePreparationBudgetExhausted"])
        assertEquals("5", result.stats["sourcePreparationMs"])
        assertEquals("0", result.stats["sourceCliMs"])
        assertEquals("5", result.stats["sourceInvocationMs"])
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `source observation setup spends the containing allowance`(unavailable: Boolean) {
        var nanos = 0L

        val result = SolverInvocation.withSourceInput(File("input.cnf"), Budget(1000), nanoTime = { nanos },
            observeHarness = {
                val observation = SourceHarnessObservation { SourceHarnessCounters(nanos, threadId = 1) }
                nanos += 3_000_000
                if (unavailable) error("observation unavailable")
                observation
            },
        ) { _, remaining ->
            assertEquals(997L, remaining.timeoutMillis)
            nanos += 10_000_000
            SolverInvocation.Result(true, null, null, proven = false,
                stats = emptyMap(), rawOutput = "sat", command = "cli")
        }

        assertEquals("3", result.stats["sourceObservationSetupMs"])
        assertEquals("3", result.stats["sourcePreparationMs"])
        assertEquals("13", result.stats["sourceInvocationMs"])
        assertEquals(true, result.feasible)
    }

    @Test
    fun `final source sampling retains its whole invocation cost`() {
        var nanos = 0L
        var reads = 0

        val result = SolverInvocation.withSourceInput(File("input.cnf"), Budget(1000), nanoTime = { nanos },
            observeHarness = {
                SourceHarnessObservation {
                    reads++
                    if (reads == 3) nanos += 2_000_000
                    SourceHarnessCounters(nanos, threadId = 1)
                }
            },
        ) { _, _ ->
            nanos = 10_000_000
            SolverInvocation.Result(true, null, null, proven = false,
                stats = emptyMap(), rawOutput = "sat", command = "cli")
        }

        assertEquals("2", result.stats["sourceObservationFinishMs"])
        assertEquals("10", result.stats["sourceCliMs"])
        assertEquals("12", result.stats["sourceInvocationMs"])
    }

    @Test
    fun `preparation sampling spends the remaining CLI allowance`() {
        var nanos = 0L
        var reads = 0

        val result = SolverInvocation.withSourceInput(File("input.cnf"), Budget(1000), nanoTime = { nanos },
            observeHarness = {
                SourceHarnessObservation {
                    reads++
                    if (reads == 2) nanos += 2_000_000
                    SourceHarnessCounters(nanos, threadId = 1)
                }
            },
        ) { _, remaining ->
            assertEquals(998L, remaining.timeoutMillis)
            SolverInvocation.Result(true, null, null, proven = false,
                stats = emptyMap(), rawOutput = "sat", command = "cli")
        }

        assertEquals("2", result.stats["sourcePreparationMs"])
        assertEquals("2", result.stats["sourcePreparationThreadCpuMs"])
    }

    @Test
    fun `ordinary invocation omits optional harness counters`() {
        val result = SolverInvocation.withSourceInput(File("input.cnf"), Budget(1000)) { _, _ ->
            SolverInvocation.Result(true, null, null, proven = false,
                stats = emptyMap(), rawOutput = "sat", command = "cli")
        }

        assertTrue(result.stats.keys.none {
            it.contains("ThreadCpu") || it.contains("GcCount") ||
                it.contains("GcTime") || it.startsWith("sourceObservation")
        })
    }

    @Test
    fun `exhausted preparations retain harness observations without a solver verdict`() {
        var nanos = 0L

        val result = SolverInvocation.withSourceInput(File("input.cnf"), Budget(5), nanoTime = { nanos },
            observeHarness = {
                val observation = SourceHarnessObservation { SourceHarnessCounters(nanos, threadId = 1) }
                nanos = 5_000_000
                observation
            },
        ) { _, _ -> error("budget exhausted") }

        assertNull(result.feasible)
        assertEquals("true", result.stats["sourcePreparationBudgetExhausted"])
        assertEquals("5", result.stats["sourceHarnessThreadCpuMs"])
        assertEquals("0", result.stats["sourceCliMs"])
    }

    @Test
    fun `failed invocations retain harness observations with their failure result`() {
        var nanos = 0L

        val failure = assertFailsWith<SolverInvocation.Failure> {
            SolverInvocation.withSourceInput(File("input.cnf"), Budget(1000), nanoTime = { nanos },
                observeHarness = { SourceHarnessObservation { SourceHarnessCounters(nanos, threadId = 1) } },
            ) { _, _ ->
                nanos = 7_000_000
                throw SolverInvocation.Failure(SolverInvocation.Result(null, null, null, proven = false,
                    stats = emptyMap(), rawOutput = "partial", command = "cli"), "failed")
            }
        }

        assertEquals("7", failure.result.stats["sourceHarnessThreadCpuMs"])
        assertEquals("partial", failure.result.rawOutput)
    }

    @Test
    fun `plain inputs retain the whole source invocation cost`() {
        var nanos = 0L

        val result = SolverInvocation.withSourceInput(File("input.cnf"), Budget(1000), nanoTime = { nanos }) { _, _ ->
            nanos = 12_000_000
            SolverInvocation.Result(true, null, null, proven = false,
                stats = emptyMap(), rawOutput = "sat", command = "cli", elapsedMs = 10)
        }

        assertEquals("0", result.stats["sourceDecodeMs"])
        assertEquals("0", result.stats["sourceCleanupMs"])
        assertEquals("12", result.stats["sourceCliMs"])
        assertEquals("12", result.stats["sourceInvocationMs"])
        assertEquals(10L, result.elapsedMs)
    }

    @Test
    fun `exhausted input preparation supplies no solver verdict`() {
        val root = Files.createTempDirectory("solver-source").toFile()
        try {
            val packed = File(root, "input.cnf.zst")
            ZstdOutputStreamNoFinalizer(packed.outputStream(), 1).use { it.write("p cnf 0 0\n".toByteArray()) }
            val result = SolverInvocation.withSourceInput(packed, Budget(0)) { _, _ -> error("budget exhausted") }

            assertNull(result.feasible)
            assertFalse(result.proven)
            assertEquals("true", result.stats["sourcePreparationBudgetExhausted"])
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `successful stderr should remain separate from solver output`(carriageReturn: Boolean) {
        val ending = if (carriageReturn) "\\r\\n" else "\\n"

        val result = SolverInvocation.invoke(
            listOf("sh", "-c", "printf '%s$ending' 'prepared model' >&2; printf '%s\\n' '----------'"),
            SolverInvocation.Dialect.MINIZINC,
        )

        assertEquals("prepared model\n", result.stderr)
        assertTrue(result.stderrComplete)
        assertEquals("----------\n", result.rawOutput)
        assertEquals(true, result.feasible)
    }

    @Test
    fun `oversized stderr should retain an incomplete bounded prefix`() {
        val result = SolverInvocation.invoke(
            listOf("sh", "-c", "printf '%20000s' '' >&2; printf '%s\\n' '----------'"),
            SolverInvocation.Dialect.MINIZINC,
        )

        val text = assertNotNull(result.stderr)
        assertTrue(text.isNotEmpty() && text.length <= 16 * 1024)
        assertTrue(text.all { it == ' ' })
        assertFalse(result.stderrComplete)
        assertEquals("----------\n", result.rawOutput)
    }

    @Test
    fun `exact FlatZinc objective comments should override decimal display`() {
        val lines = "_objective = 0.3333333333333333;\n% klause-exact: _objective = 1/3;\n----------"

        val result = SolverInvocation.invoke(
            listOf("sh", "-c", "printf '%s\\n' '$lines'"),
            SolverInvocation.Dialect.MINIZINC,
        )

        assertEquals("1/3", result.exactObjective)
    }

    @Test
    fun `portfolio diagnostics should survive protocol parsing`() {
        val diagnostic = """{"armId":7,"phase":"slice","message":"row \"R\"\u000aarm failure"}"""
        for (dialect in SolverInvocation.Dialect.entries) {
            val lines = when (dialect) {
                SolverInvocation.Dialect.MINIZINC -> "%%%mzn-stat: armFailure.failed=$diagnostic\n----------"
                SolverInvocation.Dialect.PB_COMPETITION -> "c armFailure.failed=$diagnostic\ns SATISFIABLE"
                SolverInvocation.Dialect.SMT_LIB -> "; armFailure.failed=$diagnostic\nsat"
            }

            val result = SolverInvocation.invoke(listOf("sh", "-c", "printf '%s\\n' '$lines'"), dialect)

            assertEquals(true, result.feasible)
            assertEquals(diagnostic, result.stats["armFailure.failed"])
        }
    }

    @Test
    fun `objective precision should survive parsing for each protocol`() {
        val objectives = listOf("9223372036854775808", "9007199254740993", "1/3", "-1/3", "1e400")
        for (dialect in SolverInvocation.Dialect.entries) {
            for (objective in objectives) {
                val lines = when (dialect) {
                    SolverInvocation.Dialect.MINIZINC -> "_objective = $objective;\n----------\n=========="
                    SolverInvocation.Dialect.PB_COMPETITION -> "o $objective\ns OPTIMUM FOUND"
                    SolverInvocation.Dialect.SMT_LIB -> "; objective=$objective\nsat\n; optimizationStatus=optimal"
                }
                val result = SolverInvocation.invoke(listOf("sh", "-c", "printf '%s\\n' '$lines'"), dialect)

                assertEquals(ExactObjective.parse(objective).toString(), result.exactObjective)
                assertEquals(true, result.feasible)
                assertTrue(result.proven)
            }
        }
    }

    @Test
    fun `attribution arrival should be measured on the subprocess clock`() {
        val r = SolverInvocation.invoke(
            listOf("sh", "-c", "echo '%%%klause-arm: label=bt objective=7 time=123456'; echo '----------'"),
            SolverInvocation.Dialect.MINIZINC,
        )

        val improvement = r.attribution.single()
        assertEquals(123456L, improvement.elapsedMs)
        assertTrue(assertNotNull(improvement.processElapsedMs) in 0..assertNotNull(r.elapsedMs))
    }

    @Test
    fun `a missing klause dist should be reported as a defect naming the install task`() {
        val defect = SolverInvocation.klauseCliDefect(File("does-not-exist/klause-cli"))
        assertNotNull(defect)
        assertTrue("installJvmDist" in defect, defect)
    }

    @Test
    fun `a klause dist that cannot start should be reported as a defect`() {
        assertNotNull(SolverInvocation.klauseCliDefect(File("/usr/bin/false")))
    }

    @Test
    fun `a klause dist that answers version should not be a defect`() {
        assertNull(SolverInvocation.klauseCliDefect(File("/usr/bin/true")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1"])
    fun `version preflight should leave solve recording options private to the solve`(preflight: String) {
        val solve = "-Xmx3g -XX:StartFlightRecording=filename=solve.jfr -XX:LogFile=solve.xml"
        val environment = mapOf(
            "KLAUSE_CLI_OPTS" to solve,
            "KLAUSE_CLI_PREFLIGHT_OPTS" to preflight,
            "JAVA_HOME" to "/runtime",
        )

        val command = SolverInvocation.cliVersionCommand(File("/installed/klause-cli"), environment)

        assertEquals(listOf("/installed/klause-cli", "--version"), command.command())
        assertEquals(preflight, command.environment()["KLAUSE_CLI_OPTS"])
        assertEquals("/runtime", command.environment()["JAVA_HOME"])
        assertEquals(solve, environment["KLAUSE_CLI_OPTS"])
    }

    @Test
    fun `version preflight should retain ordinary options without an explicit override`() {
        val environment = mapOf("KLAUSE_CLI_OPTS" to "-Xmx64m", "JAVA_HOME" to "/runtime")

        val command = SolverInvocation.cliVersionCommand(File("/installed/klause-cli"), environment)

        assertEquals(environment, command.environment())
    }

    @Test
    fun `subprocess exit codes should remain visible after verdict publication`() {
        for (dialect in SolverInvocation.Dialect.entries) {
            val verdict = when (dialect) {
                SolverInvocation.Dialect.MINIZINC -> "----------"
                SolverInvocation.Dialect.PB_COMPETITION -> "s SATISFIABLE"
                SolverInvocation.Dialect.SMT_LIB -> "sat"
            }
            for (exit in listOf(0, 7)) {
                val result = SolverInvocation.invoke(
                    listOf("sh", "-c", "printf '%s\\n' '$verdict'; exit $exit"),
                    dialect,
                )

                assertEquals(exit.toString(), result.stats["exit"])
            }
        }
    }

    @Test
    fun `a declined model should be undecided rather than a failed run`() {
        val r = SolverInvocation.invoke(
            listOf(
                "sh",
                "-c",
                "echo 'klause MPS: optimization over a continuous objective is unsupported' >&2; exit 2",
            ),
            SolverInvocation.Dialect.PB_COMPETITION,
        )

        assertNull(r.feasible)
        assertEquals("MPS: optimization over a continuous objective is unsupported", r.stats["unsupported"])
    }

    @Test
    fun `a MiniZinc UNSATISFIABLE verdict should be a proof of infeasibility`() {
        val r = SolverInvocation.invoke(
            listOf("sh", "-c", "echo '=====UNSATISFIABLE====='; echo '%%%mzn-core: [x > 8, x < 3]'"),
            SolverInvocation.Dialect.MINIZINC,
        )

        assertEquals(listOf(false, true), listOf(r.feasible, r.proven))
    }

    @Test
    fun `flattening infeasibility should retain wall clock without an incumbent time`() {
        val r = SolverInvocation.invoke(
            listOf("sh", "-c", "printf '%s\\n' '%%%mzn-stat: flatTime=0.14' '=====UNSATISFIABLE====='"),
            SolverInvocation.Dialect.MINIZINC,
        )

        assertTrue(assertNotNull(r.elapsedMs) >= 0)
        assertNull(r.timeToBestMs)
        assertNull(r.timeToFirstFeasibleMs)
        assertNull(r.stats["solveTime"])
    }

    @Test
    fun `subprocess completion should retain the earlier incumbent timestamp`() {
        val r = SolverInvocation.invoke(
            listOf("sh", "-c", "echo '----------'; sleep 0.02; echo '=========='"),
            SolverInvocation.Dialect.MINIZINC,
        )

        assertTrue(assertNotNull(r.elapsedMs) > assertNotNull(r.timeToBestMs))
        assertEquals(r.timeToBestMs, r.timeToFirstFeasibleMs)
    }

    @Test
    fun `a crash that also printed a refusal line should still raise`() {
        assertFailsWith<IllegalStateException> {
            SolverInvocation.invoke(
                listOf(
                    "sh",
                    "-c",
                    "echo 'klause MPS: nope' >&2; echo 'java.lang.IllegalStateException: boom' >&2; exit 1",
                ),
                SolverInvocation.Dialect.PB_COMPETITION,
            )
        }
    }

    @Test
    fun `a rational competition objective should retain incumbent and optimum status`() {
        val r = SolverInvocation.invoke(
            listOf("sh", "-c", "printf '%s\\n' 'o -1/3' 's OPTIMUM FOUND'"),
            SolverInvocation.Dialect.PB_COMPETITION,
        )

        assertTrue(r.feasible == true)
        assertTrue(r.proven)
        assertEquals(-1.0 / 3.0, r.objective)
        assertNotNull(r.timeToBestMs)
        assertEquals("o -1/3\ns OPTIMUM FOUND\n", r.rawOutput)
    }

    @Test
    fun `an SMT optimum should require explicit status and a finite objective`() {
        val r = SolverInvocation.invoke(
            listOf("sh", "-c", "printf '%s\\n' '; objective=9' '; objective=7' 'sat' '; optimizationStatus=optimal'"),
            SolverInvocation.Dialect.SMT_LIB,
        )

        assertEquals(true, r.feasible)
        assertEquals(7.0, r.objective)
        assertTrue(r.proven)
        assertNotNull(r.timeToBestMs)
        assertNotNull(r.timeToFirstFeasibleMs)
    }

    @ParameterizedTest
    @ValueSource(strings = ["MINIZINC", "PB_COMPETITION", "SMT_LIB"])
    fun `numerical result metadata should retain authority and original objective units`(dialect: String) {
        val mode = SolverInvocation.Dialect.valueOf(dialect)
        val prefix = when (mode) {
            SolverInvocation.Dialect.MINIZINC -> "% "
            SolverInvocation.Dialect.PB_COMPETITION -> "c "
            SolverInvocation.Dialect.SMT_LIB -> "; "
        }
        val metadata = linkedMapOf(
            "lpDeductionAuthority" to "numerical",
            "numericalObjectiveTolerance" to "1.0E-6",
            "numericalObjectiveBoundDirection" to "lower",
            "numericalObjectiveBound" to "3.25",
            "numericalRelativeGap" to "0.125",
            "optimizationStatus" to "tolerance-optimum",
        )
        val stdout = metadata.entries.joinToString("\n") { prefix + it.key + "=" + it.value } + "\n"

        val result = SolverInvocation.invoke(listOf("fake"), mode,
            startProcess = { SolverInvocationFakeProcess("".byteInputStream(), stdout = stdout) })

        metadata.forEach { (key, value) -> assertEquals(value, result.stats[key], key) }
        assertNull(result.feasible)
        assertFalse(result.proven)
    }

    @Test
    fun `plain SMT sat should provide no optimality proof`() {
        val r = SolverInvocation.invoke(listOf("sh", "-c", "echo sat"), SolverInvocation.Dialect.SMT_LIB)

        assertEquals(true, r.feasible)
        assertNull(r.objective)
        assertFalse(r.proven)
    }

    @Test
    fun `SMT partial and unbounded objectives should remain distinct from proved optima`() {
        for (status in listOf("best-found", "unbounded", "unknown", "satisfiable", "unrecognized")) {
            val r = SolverInvocation.invoke(
                listOf("sh", "-c", "printf '%s\\n' '; objective=-1/3' 'sat' '; optimizationStatus=$status'"),
                SolverInvocation.Dialect.SMT_LIB,
            )

            assertEquals(true, r.feasible, status)
            assertEquals(-1.0 / 3.0, r.objective, status)
            assertFalse(r.proven, status)
            assertEquals(status, r.stats["optimizationStatus"])
        }
    }

    @Test
    fun `SMT optimization metadata alone should not prove an optimum`() {
        val r = SolverInvocation.invoke(
            listOf("sh", "-c", "printf '%s\\n' '; objective=7' '; optimizationStatus=optimal'"),
            SolverInvocation.Dialect.SMT_LIB,
        )

        assertEquals(7.0, r.objective)
        assertFalse(r.proven)
    }

    @Test
    fun `SMT malformed objectives should receive no optimum credit`() {
        for (objective in listOf("", "NaN", "Infinity", "1/0")) {
            val r = SolverInvocation.invoke(
                listOf("sh", "-c", "printf '%s\\n' '; objective=$objective' 'sat' '; optimizationStatus=optimal'"),
                SolverInvocation.Dialect.SMT_LIB,
            )

            assertEquals(true, r.feasible, objective)
            assertNull(r.objective, objective)
            assertFalse(r.proven, objective)
        }
    }

    @Test
    fun `SMT unsat should retain its infeasibility proof`() {
        val r = SolverInvocation.invoke(
            listOf("sh", "-c", "printf '%s\\n' 'unsat' '; optimizationStatus=unsatisfiable'"),
            SolverInvocation.Dialect.SMT_LIB,
        )

        assertEquals(false, r.feasible)
        assertNull(r.objective)
        assertTrue(r.proven)
    }

    @ParameterizedTest
    @ValueSource(strings = ["unsat", "unknown", "sat"])
    fun `the latest SMT response replaces earlier feasibility and optimization claims`(status: String) {
        val result = SolverInvocation.invoke(listOf("fake"), SolverInvocation.Dialect.SMT_LIB,
            startProcess = {
                SolverInvocationFakeProcess("".byteInputStream(),
                    stdout = "; objective=7\nsat\n; optimizationStatus=optimal\n$status\n")
            })

        assertEquals(when (status) {
            "sat" -> true
            "unsat" -> false
            else -> null
        }, result.feasible)
        assertEquals(status == "unsat", result.proven)
        assertNull(result.objective)
        assertNull(result.exactObjective)
        assertEquals("2", result.stats["smtQueryResponses"])
        assertNull(result.stats["optimizationStatus"])
        assertNull(result.stats["objective"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["x\nunsat\ny", "x\n; objective=7\n; optimizationStatus=optimal\ny",
        "x\runsat\ry", "x\r\n; objective=7\r\n; optimizationStatus=optimal\r\ny"])
    fun `quoted SMT model names cannot fabricate verdicts or optimization observations`(name: String) {
        val model = "((define-fun |$name| () Int 3))"
        val result = SolverInvocation.invoke(listOf("fake"), SolverInvocation.Dialect.SMT_LIB,
            startProcess = { SolverInvocationFakeProcess("".byteInputStream(), stdout = "sat\n$model\n") })

        assertEquals(true, result.feasible)
        assertFalse(result.proven)
        assertNull(result.objective)
        assertNull(result.stats["optimizationStatus"])
        assertEquals("1", result.stats["smtQueryResponses"])
        assertEquals("sat\n$model\n", result.rawOutput)
        assertEquals(model, SolveEvidence.finalWitness(Format.SMTLIB, result.rawOutput))
    }

    @Test
    fun `competition objectives should parse decimal exponent and large finite fractions`() {
        val huge = "1" + "0".repeat(400)
        val cases = listOf("-2.5" to -2.5, "1e2" to 100.0, "+1/-4" to -0.25, "$huge/$huge" to 1.0)

        cases.forEach { (source, expected) ->
            assertEquals(expected, SolverInvocation.parsePbObjective(source), source)
        }
    }

    @Test
    fun `competition objectives should reject malformed and nonfinite fractions`() {
        val cases = listOf("1/0", "1//2", "1.0/2", "NaN", "Infinity", "1e999", "1${"0".repeat(400)}/1")

        cases.forEach { source ->
            assertNull(SolverInvocation.parsePbObjective(source), source)
        }
    }

    @Test
    fun `a subprocess killed by the hard timeout should be recorded as undecided`() {
        val r = SolverInvocation.invoke(listOf("sleep", "5"), SolverInvocation.Dialect.MINIZINC, hardTimeoutMs = 100)
        assertNull(r.feasible)
        assertFalse(r.proven)
        assertEquals("hard-timeout", r.stats["killed"])
    }

    @Test
    fun `a subprocess that fails without being killed should still raise`() {
        assertFailsWith<IllegalStateException> {
            SolverInvocation.invoke(listOf("false"), SolverInvocation.Dialect.MINIZINC)
        }
    }

    @Test
    fun `arm telemetry should parse exact and continuous objective channels`() {
        val r = SolverInvocation.invoke(
            listOf(
                "sh",
                "-c",
                "printf '%s\\n' " +
                    "'%%%klause-arm: label=integer objective=9007199254740993 time=10' " +
                    "'%%%klause-arm: label=continuous objective=0 continuousObjective=60.0 time=20' " +
                    "'%%%klause-arm: label=mixed-min objective=-3 continuousObjective=-63.5 time=30' " +
                    "'%%%klause-arm: label=mixed-max objective=3 continuousObjective=63.5 time=40' " +
                    "'%%%klause-arm: label=source objective=-27021597764222978/3 time=50' " +
                    "'%%%klause-arm: label=invalid objective=1/0 time=60'",
            ),
            SolverInvocation.Dialect.PB_COMPETITION,
        )

        assertEquals("9007199254740993", r.attribution[0].exactObjective)
        assertNull(r.attribution[0].continuousObjective)
        assertEquals("0", r.attribution[1].exactObjective)
        assertEquals(60.0, r.attribution[1].continuousObjective)
        assertEquals(-63.5, r.attribution[2].continuousObjective)
        assertEquals(63.5, r.attribution[3].continuousObjective)
        assertEquals("-27021597764222978/3", r.attribution[4].exactObjective)
        assertEquals(5, r.attribution.size)
    }

    @Test
    fun `calibration timing should find the best-valued incumbent when attribution arrives out of order`() {
        // Concurrent arms can print a worse incumbent after a better one; the middle entry is the best.
        val r = SolverInvocation.Result(
            feasible = true,
            objective = null,
            timeToBestMs = 100,
            proven = false,
            stats = emptyMap(),
            attribution = listOf(
                Attribution("first", exactObjective = "5", elapsedMs = 10, processElapsedMs = 110),
                Attribution("true-best", exactObjective = "-3", elapsedMs = 20, processElapsedMs = 120),
                Attribution("late-but-worse", exactObjective = "2", elapsedMs = 30, processElapsedMs = 130),
            ),
            rawOutput = "",
            command = "",
        )

        assertEquals(10L to 20L, SolveMetric.timings(r, maximize = false))
        assertEquals(110L to 120L, SolveMetric.processTimings(r, maximize = false))
    }

    @Test
    fun `calibration timing should compare on the continuous channel once any entry carries one`() {
        val r = SolverInvocation.Result(
            feasible = true,
            objective = null,
            timeToBestMs = 100,
            proven = false,
            stats = emptyMap(),
            attribution = listOf(
                Attribution("integer", exactObjective = "9007199254740993", elapsedMs = 10),
                Attribution("continuous", exactObjective = "0", continuousObjective = 60.0, elapsedMs = 20),
                Attribution("mixed", exactObjective = "-3", continuousObjective = -63.5, elapsedMs = 30),
            ),
            rawOutput = "",
            command = "",
        )

        assertEquals(10L to 30L, SolveMetric.timings(r, maximize = false))
    }

    @Test
    fun `quarantine warning parsing should keep every stderr warning and nothing else`() {
        val stderr = "WARNING: Using incubator modules\n" +
            "% WARNING: portfolio arm ls/a quarantined: int 3 = 7 conflicts\n" +
            "% WARNING: portfolio arm bt/b quarantined: claimed infeasible against a verified incumbent\n"

        assertEquals(
            "ls/a quarantined: int 3 = 7 conflicts | bt/b quarantined: claimed infeasible against a verified incumbent",
            StderrDiagnostics().apply {
                stderr.forEach(::accept)
                finish()
            }.quarantines,
        )
        val unrelated = StderrDiagnostics().apply {
            "WARNING: Using incubator modules\n".forEach(::accept)
            finish()
        }
        assertNull(unrelated.quarantines)
    }
}
