package com.eignex.klause.bench.metric

import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFiles

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    @Test
    fun `compressed input reaches the solver as original bytes and is retired afterwards`() {
        val root = Files.createTempDirectory("solver-source").toFile()
        try {
            val body = "p cnf 2 1\n1 -2 0\n"
            val packed = CorpusFiles.compress(File(root, "input.cnf").apply { writeText(body) })
            var staged: File? = null

            val result = SolverInvocation.withSourceInput(packed, Budget(10_000)) { plain, remaining ->
                staged = plain
                assertEquals(body, plain.readText())
                assertTrue(remaining.timeoutMillis in 1..10_000)
                SolverInvocation.Result(true, null, 2L, proven = false,
                    stats = emptyMap(), rawOutput = "sat", command = plain.path, elapsedMs = 3L)
            }

            assertFalse(assertNotNull(staged).exists())
            assertEquals(body, CorpusFiles.readText(packed))
            assertEquals(3L, result.elapsedMs)
            assertEquals(2L, result.timeToBestMs)
            assertTrue("sourceDecodeMs" in result.stats && "sourceInvocationMs" in result.stats)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `exhausted input preparation supplies no solver verdict`() {
        val root = Files.createTempDirectory("solver-source").toFile()
        try {
            val packed = CorpusFiles.compress(File(root, "input.cnf").apply { writeText("p cnf 0 0\n") })
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
                    "'%%%klause-arm: label=mixed-max objective=3 continuousObjective=63.5 time=40'",
            ),
            SolverInvocation.Dialect.PB_COMPETITION,
        )

        assertEquals("9007199254740993", r.attribution[0].exactObjective)
        assertNull(r.attribution[0].continuousObjective)
        assertEquals("0", r.attribution[1].exactObjective)
        assertEquals(60.0, r.attribution[1].continuousObjective)
        assertEquals(-63.5, r.attribution[2].continuousObjective)
        assertEquals(63.5, r.attribution[3].continuousObjective)
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
            SolverInvocation.quarantines(stderr),
        )
        assertNull(SolverInvocation.quarantines("WARNING: Using incubator modules\n"))
    }

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
}
