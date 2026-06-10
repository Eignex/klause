package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolverTest {

    private fun isFeasible(problem: Problem, sample: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, sample.bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, sample.ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    @Test
    fun `parse handles aliases comma-lists and order`() {
        assertEquals(PresolveConfig.DEFAULT.passes, PresolveConfig.parse(null).passes)
        assertEquals(PresolveConfig.DEFAULT.passes, PresolveConfig.parse("default").passes)
        assertEquals(emptyList(), PresolveConfig.parse("none").passes)
        assertEquals(
            listOf(PresolvePass.ELIMINATE_AFFINE_SINGLETONS, PresolvePass.STRENGTHEN_COEFFICIENTS),
            PresolveConfig.parse("affine, strengthen").passes,
        )
        assertFailsWith<IllegalStateException> { PresolveConfig.parse("bogus") }
    }

    @Test
    fun `context extracts nonzero objective coefficients`() {
        val obj = LinearObjective(
            boolWeights = longArrayOf(0, 3, 0),
            intCoefficients = longArrayOf(5, 0, 2),
        )
        val ctx = PresolveContext.of(obj)
        assertEquals(setOf(0, 2), ctx.objectiveIntVars)
        assertEquals(setOf(1), ctx.objectiveBoolVars)
        assertTrue(PresolveContext.of(null).objectiveIntVars.isEmpty())
    }

    @Test
    fun `empty config is the identity`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 3)),
            listOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 4)),
        )
        val pre = Presolver.run(problem, PresolveConfig.NONE)
        assertSame(problem, pre.problem)
        val s = Sample(BooleanArray(0), intArrayOf(2))
        assertSame(s, pre.reconstruct(s))
    }

    @Test
    fun `default pipeline composes and reconstructs to a feasible original solution`() {
        // var 0: affine singleton (x = 2y+1 via x - 2y = 1), only here.
        // vars 1,2: GCD-reducible sum 2y+2z<=4 with y,z interchangeable (equal coeff) -> symmetry.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 9), IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(2, 2), intArrayOf(1, 2), LinearOp.LE, 4),
            ),
        )
        val pre = Presolver.run(problem, PresolveConfig.DEFAULT)
        assertTrue(pre.problem !== problem, "expected the pipeline to transform the problem")
        val result = BacktrackSolver(pre.problem).solve(BacktrackParams())
        assertTrue(result is SolveResult.Sat, "presolved problem should be SAT, got $result")
        val full = pre.reconstruct(result.assignment)
        assertEquals(2 * full.ints[1] + 1, full.ints[0], "affine var not reconstructed: x should be 2y+1")
        assertTrue(isFeasible(problem, full), "reconstructed sample infeasible in the original problem")
    }

    @Test
    fun `affine pass protects objective variables`() {
        // x (0) is an affine singleton, but it is the objective variable -> must not be eliminated.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 9), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1)),
        )
        val ctx = PresolveContext.of(LinearObjective(intCoefficients = longArrayOf(1, 0)))
        val pre = Presolver.run(problem, PresolveConfig(listOf(PresolvePass.ELIMINATE_AFFINE_SINGLETONS)), ctx)
        // Nothing eliminated -> identity problem, identity reconstruct.
        assertSame(problem, pre.problem)
    }
}
