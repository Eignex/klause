package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class CoreGuidedSearchSliceTest(private val sliceWork: Long, private val nativeSat: Boolean) {
    @Test
    fun `sliced weight splitting should preserve the exact optimum and cumulative work`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
            ),
        ).bake()
        val objective = LinearObjective(longArrayOf(-7, -2, -1), constant = 10)
        val fresh = CoreGuidedSearch(problem, objective, BacktrackParams(nativeSat = nativeSat))
        val sliced = CoreGuidedSearch(problem, objective, BacktrackParams(nativeSat = nativeSat))
        val expected = assertIs<MinimizeResult.Optimal>(fresh.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})
        var result: MinimizeResult? = null
        var previousWork = 0L

        repeat(100) {
            if (result == null) result = sliced.runSlice(Cancellation.Never, Long.MAX_VALUE, sliceWork) {}
            assertTrue(sliced.work >= previousWork)
            previousWork = sliced.work
        }

        val actual = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(3L, objective.evaluateLong(actual.sample))
        assertEquals(objective.evaluateExact(expected.sample), objective.evaluateExact(actual.sample))
        assertEquals(fresh.work, sliced.work)
        assertEquals(objective.evaluateExact(actual.sample), sliced.lowerBound)
    }

    @Test
    fun `sliced stratification should retain scans with costs beyond scalar precision`() {
        val problem = Problem(
            3, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        ).bake()
        val objective = LinearObjective(longArrayOf(9_007_199_254_740_993L, Long.MAX_VALUE, Long.MIN_VALUE))
        val fresh = CoreGuidedSearch(problem, objective, BacktrackParams(nativeSat = nativeSat), stratify = true)
        val sliced = CoreGuidedSearch(problem, objective, BacktrackParams(nativeSat = nativeSat), stratify = true)
        val expected = assertIs<MinimizeResult.Optimal>(fresh.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})
        var result: MinimizeResult? = null

        repeat(100) {
            if (result == null) result = sliced.runSlice(Cancellation.Never, Long.MAX_VALUE, sliceWork) {}
        }

        val actual = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(objective.evaluateExact(expected.sample), objective.evaluateExact(actual.sample))
        assertEquals(fresh.preparationWork, sliced.preparationWork)
        assertEquals(fresh.thresholdVisits, sliced.thresholdVisits)
        assertEquals(fresh.queryCount, sliced.queryCount)
        assertEquals(fresh.work, sliced.work)
        assertEquals(objective.evaluateExact(actual.sample), sliced.lowerBound)
    }

    @Test
    fun `query pin preparation should resume without repeating threshold visits`() {
        val problem = Problem(1024, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(LongArray(1024) { 1L })
        val search = CoreGuidedSearch(problem, objective, BacktrackParams(nativeSat = nativeSat))
        val fresh = CoreGuidedSearch(problem, objective, BacktrackParams(nativeSat = nativeSat))
        val expected = fresh.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {}

        val paused = search.runSlice(Cancellation { search.thresholdVisits >= sliceWork }, Long.MAX_VALUE, -1L) {
            error("unfinished query pins")
        }
        val visited = search.thresholdVisits
        val actual = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {}

        assertNull(paused)
        assertEquals(sliceWork, visited)
        assertIs<MinimizeResult.Optimal>(expected)
        assertIs<MinimizeResult.Optimal>(actual)
        assertEquals(1024L, search.thresholdVisits)
        assertEquals(fresh.preparationWork, search.preparationWork)
        assertEquals(fresh.work, search.work)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "sliceWork={0}, nativeSat={1}")
        fun parameters(): List<Array<Any>> = listOf(1L, 5L, 200L).flatMap { work ->
            listOf(false, true).map { native -> arrayOf(work, native) }
        }
    }
}
