package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolverInvocationTest {

    @Test
    fun `a missing klause dist is reported as a defect naming the install task`() {
        val defect = SolverInvocation.klauseCliDefect(File("does-not-exist/klause-cli"))
        assertNotNull(defect)
        assertTrue("installJvmDist" in defect, "the defect should say how to fix it, got: $defect")
    }

    @Test
    fun `a klause dist that cannot start is reported as a defect`() {
        assertNotNull(SolverInvocation.klauseCliDefect(File("/usr/bin/false")))
    }

    @Test
    fun `a klause dist that answers version is no defect`() {
        assertNull(SolverInvocation.klauseCliDefect(File("/usr/bin/true")))
    }

    @Test
    fun `a model the solver declines is undecided rather than a failed run`() {
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
    fun `a MiniZinc UNSATISFIABLE verdict is a proof of infeasibility`() {
        val r = SolverInvocation.invoke(
            listOf("sh", "-c", "echo '=====UNSATISFIABLE====='; echo '%%%mzn-core: [x > 8, x < 3]'"),
            SolverInvocation.Dialect.MINIZINC,
        )

        assertEquals(listOf(false, true), listOf(r.feasible, r.proven))
    }

    @Test
    fun `flattening infeasibility retains wall clock without an incumbent time`() {
        val r = SolverInvocation.invoke(
            listOf("sh", "-c", "printf '%s\\n' '%%%mzn-stat: flatTime=0.14' '=====UNSATISFIABLE====='"),
            SolverInvocation.Dialect.MINIZINC,
        )

        assertTrue(assertNotNull(r.elapsedMs) >= 0)
        assertNull(r.timeToBestMs)
        assertNull(r.timeToFirstFeasibleMs)
        assertNull(r.stats["solveTime"])
        assertEquals(r.elapsedMs, r.referenceElapsedMs(60_000))
    }

    @Test
    fun `subprocess completion retains the earlier incumbent timestamp`() {
        val r = SolverInvocation.invoke(
            listOf("sh", "-c", "echo '----------'; sleep 0.02; echo '=========='"),
            SolverInvocation.Dialect.MINIZINC,
        )

        assertTrue(assertNotNull(r.elapsedMs) > assertNotNull(r.timeToBestMs))
        assertEquals(r.timeToBestMs, r.timeToFirstFeasibleMs)
    }

    @Test
    fun `reference proofs use solve time then wall clock then legacy budget`() {
        val r = SolverInvocation.Result(false, null, null, proven = true, stats = emptyMap(), rawOutput = "", command = "")
        val cases = listOf(
            r to 60_000L,
            r.copy(elapsedMs = 140) to 140L,
            r.copy(elapsedMs = 140, stats = mapOf("solveTime" to "0.02")) to 20L,
            r.copy(elapsedMs = 140, stats = mapOf("solveTime" to "NaN")) to 140L,
            r.copy(elapsedMs = 140, stats = mapOf("solveTime" to "-1")) to 140L,
        )

        cases.forEach { (result, expected) -> assertEquals(expected, result.referenceElapsedMs(60_000)) }
    }

    @Test
    fun `reference witnesses use incumbent time and undecided runs use budget`() {
        val r = SolverInvocation.Result(
            true, null, 12, timeToFirstFeasibleMs = 10, elapsedMs = 140,
            proven = false, stats = emptyMap(), rawOutput = "", command = "",
        )

        assertEquals(10, r.referenceElapsedMs(60_000))
        assertEquals(60_000, r.copy(feasible = null).referenceElapsedMs(60_000))
    }

    @Test
    fun `a crash that also printed a refusal line still raises`() {
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
    fun `a rational competition objective retains incumbent and optimum status`() {
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
    fun `competition objectives parse decimal exponent and large finite fractions`() {
        val huge = "1" + "0".repeat(400)
        val cases = listOf("-2.5" to -2.5, "1e2" to 100.0, "+1/-4" to -0.25, "$huge/$huge" to 1.0)

        cases.forEach { (source, expected) ->
            assertEquals(expected, SolverInvocation.parsePbObjective(source), source)
        }
    }

    @Test
    fun `competition objectives reject malformed and nonfinite fractions`() {
        val cases = listOf("1/0", "1//2", "1.0/2", "NaN", "Infinity", "1e999", "1${"0".repeat(400)}/1")

        cases.forEach { source ->
            assertNull(SolverInvocation.parsePbObjective(source), source)
        }
    }

    @Test
    fun `a subprocess killed by the hard timeout is recorded as an undecided run`() {
        val r = SolverInvocation.invoke(listOf("sleep", "5"), SolverInvocation.Dialect.MINIZINC, hardTimeoutMs = 100)
        assertNull(r.feasible)
        assertFalse(r.proven)
        assertEquals("hard-timeout", r.stats["killed"])
    }

    @Test
    fun `a subprocess that fails without being killed still raises`() {
        assertFailsWith<IllegalStateException> {
            SolverInvocation.invoke(listOf("false"), SolverInvocation.Dialect.MINIZINC)
        }
    }

    @Test
    fun `arm telemetry parses exact and continuous objective channels`() {
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
    fun `persisted arm telemetry decodes legacy numeric objectives`() {
        val legacy = """{"label":"legacy","objective":27500000.0,"elapsedMs":15}"""

        val decoded = Reports.json.decodeFromString<Attribution>(legacy)
        val encoded = Reports.json.encodeToString(
            Attribution(
                label = "wide",
                objective = 9_007_199_254_740_993L.toDouble(),
                exactObjective = "9007199254740993",
                continuousObjective = 9_007_199_254_740_994.0,
                elapsedMs = 20,
            ),
        )

        assertEquals(27_500_000.0, decoded.objective)
        assertNull(decoded.exactObjective)
        assertTrue("\"exactObjective\": \"9007199254740993\"" in encoded, encoded)
        assertTrue("\"continuousObjective\": 9.007199254740994E15" in encoded, encoded)
    }

    @Test
    fun `calibration timing finds the best-valued incumbent even when attribution arrives out of order`() {
        // A concurrent portfolio's shared-bound CAS and its attribution-emit lock are separate critical
        // sections, so a worse incumbent's line can print after a better one's (klause.portfolio.
        // Portfolio.fold) — the middle entry here is the true best, not the last.
        val r = SolverInvocation.Result(
            feasible = true,
            objective = null,
            timeToBestMs = 100,
            proven = false,
            stats = emptyMap(),
            attribution = listOf(
                Attribution("first", exactObjective = "5", elapsedMs = 10),
                Attribution("true-best", exactObjective = "-3", elapsedMs = 20),
                Attribution("late-but-worse", exactObjective = "2", elapsedMs = 30),
            ),
            rawOutput = "",
            command = "",
        )

        assertEquals(10L to 20L, SolveMetric.timings(r, maximize = false))
    }

    @Test
    fun `calibration timing compares on the continuous channel once any entry carries one`() {
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
    fun `every quarantine warning on stderr is kept and nothing else is`() {
        val stderr = "WARNING: Using incubator modules\n" +
            "% WARNING: portfolio arm ls/a quarantined: int 3 = 7 conflicts\n" +
            "% WARNING: portfolio arm bt/b quarantined: claimed infeasible against a verified incumbent\n"

        assertEquals(
            "ls/a quarantined: int 3 = 7 conflicts | bt/b quarantined: claimed infeasible against a verified incumbent",
            SolverInvocation.quarantines(stderr),
        )
        assertNull(SolverInvocation.quarantines("WARNING: Using incubator modules\n"))
    }
}
