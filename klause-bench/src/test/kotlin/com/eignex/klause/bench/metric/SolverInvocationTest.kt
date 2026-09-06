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
        assertNotNull(SolverInvocation.klauseCliDefect(File("/bin/false")))
    }

    @Test
    fun `a klause dist that answers version is no defect`() {
        assertNull(SolverInvocation.klauseCliDefect(File("/bin/true")))
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
}
