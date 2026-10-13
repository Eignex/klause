package com.eignex.klause.bench.profile

import com.eignex.klause.bench.report.Reports
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SolveTimingTest {
    @Test
    fun `times each repeat of a smoke problem into timings json`() {
        val out = createTempDirectory("solve-timing").toFile()
        try {
            val timings = SolveTiming.measure(listOf("suite=opb-core", "problem=sporttournament06", "repeats=2"), out)
            assertEquals(2, timings.runs.size)
            assertTrue(timings.runs.all { it.feasible == true && it.proven && it.objective == -6.0 }, "$timings")
            val written = Reports.json.decodeFromString<SolveTiming.Timings>(File(out, "timings.json").readText())
            assertEquals(timings, written)
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun `rejects unknown problems and malformed arguments`() {
        val out = File("build/unused")
        assertFailsWith<IllegalStateException> { SolveTiming.measure(listOf("suite=opb-core", "problem=nope"), out) }
        assertFailsWith<IllegalArgumentException> { SolveTiming.measure(listOf("repeats=0"), out) }
        assertFailsWith<IllegalArgumentException> { SolveTiming.measure(listOf("problem"), out) }
        assertFailsWith<IllegalArgumentException> { SolveTiming.measure(listOf("seed=1"), out) }
    }
}
