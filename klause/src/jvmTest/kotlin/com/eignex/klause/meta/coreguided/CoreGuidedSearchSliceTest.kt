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
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class CoreGuidedSearchSliceTest(private val sliceWork: Long) {
    @Test
    fun `sliced weight splitting preserves the exact optimum and cumulative work`() {
        val problem = Problem(
            3, 0, emptyArray(),
            arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
        )
        ).bake()
        val objective = LinearObjective(longArrayOf(-7, -2, -1), constant = 10)
        val fresh = CoreGuidedSearch(problem, objective, BacktrackParams())
        val sliced = CoreGuidedSearch(problem, objective, BacktrackParams())
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

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "sliceWork={0}")
        fun parameters(): List<Array<Long>> = listOf(1L, 5L, 200L).map { arrayOf(it) }
    }
}
