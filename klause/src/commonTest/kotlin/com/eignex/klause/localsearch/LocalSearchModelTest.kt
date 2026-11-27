package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.internals.CumulativeEventProfile
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.verifyOpenWitness
import com.eignex.util.collections.Bits
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class LocalSearchModelTest {

    private fun openProblem(vararg factors: Linear): Problem {
        val open = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        return Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), open, open),
            factors = arrayOf(*factors),
        )
    }

    @Test
    fun `a finite model refuted at the root should be reported unsatisfiable`() {
        val contradiction = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 3)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5)),
        ).bake()

        val model = LocalSearchModel.of(contradiction)

        assertNull(model.pinsUnder(Assumptions.None))
        assertIs<SolveResult.Unsat>(LocalSearchSolver(contradiction).solve(LocalSearchParams(maxFlips = 10)))
    }

    @Test
    fun `an open side should be searched through a window around zero`() {
        val model = LocalSearchModel.open(openProblem())

        assertEquals(-(1L shl 30) + 1, model.domains[0].min)
        assertEquals((1L shl 30) - 1, model.domains[0].max)
        assertFalse(model.refutesModel)
    }

    @Test
    fun `wide open cumulative windows should use task events in either constructor`() {
        val last = 1L shl 21
        val windows = arrayOf(IntDomain(0, last))
        val source = Problem(0, 1, arrayOf(IntDomain(0, 0)), listOf(
            Cumulative(intArrayOf(0), longArrayOf(1), longArrayOf(1), 1),
        ), openIntHi = booleanArrayOf(true))
        val selection = FactorProjectionProviders.Default.prepare(source)
        for (model in listOf(LocalSearchModel.open(source, windows), LocalSearchModel.open(source, selection, windows))) {
            val state = LocalSearchState(model, Random(3))
            state.assignment.setInt(0, last)

            state.recompute()

            assertIs<CumulativeEventProfile>(state.refPayload[0])
        }
    }

    @Test
    fun `exhausting a cumulative search window should leave the open source undecided`() {
        val blockerLength = 1L shl 21
        val windows = arrayOf(IntDomain(0, 1), IntDomain(0, 0))
        val source = Problem(0, 2, windows, listOf(
            Cumulative(intArrayOf(0, 1), longArrayOf(1, blockerLength), longArrayOf(1, 1), 1),
        ), openIntHi = booleanArrayOf(true, false))
        val selection = FactorProjectionProviders.Default.prepare(source)
        val witness = Sample(booleanArrayOf(), longArrayOf(blockerLength, 0))
        assertIs<Verification.Accepted<*, *>>(verifyOpenWitness(source, witness))

        for (model in listOf(LocalSearchModel.open(source, windows), LocalSearchModel.open(source, selection, windows))) {
            val result = LocalSearchEngine(model).solve(LocalSearchParams(maxFlips = 8, randomSeed = 3), warm = null)

            assertEquals(TerminationReason.BudgetExhausted, assertIs<SolveResult.Unknown>(result).reason)
        }
    }

    @Test
    fun `local search should find a solution of a model with open integer columns`() {
        val problem = openProblem(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 7),
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
        )

        val result = LocalSearchEngine(LocalSearchModel.open(problem))
            .solve(LocalSearchParams(maxFlips = 200_000, randomSeed = 3), warm = null)

        val sat = assertIs<SolveResult.Sat>(result)
        assertEquals(4L, sat.assignment.ints[0])
        assertEquals(3L, sat.assignment.ints[1])
    }
}
