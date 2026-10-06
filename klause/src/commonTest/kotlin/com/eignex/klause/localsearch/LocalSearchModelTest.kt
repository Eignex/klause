package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

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
    fun `a finite model searches its root-propagated domains`() {
        val baked = Problem(0, 1, arrayOf(IntDomain(2, 9)), emptyArray()).bake()

        val model = LocalSearchModel.of(baked)

        assertSame(baked.rootIntDomainsInPlace, model.domains)
    }

    @Test
    fun `a finite model refuted at the root is reported unsatisfiable`() {
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
    fun `an open side is searched through a window around zero`() {
        val model = LocalSearchModel.open(openProblem())

        assertEquals(-(1L shl 30) + 1, model.domains[0].min)
        assertEquals((1L shl 30) - 1, model.domains[0].max)
        assertFalse(model.refutesModel)
    }

    @Test
    fun `local search finds a solution of a model with open integer columns`() {
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
