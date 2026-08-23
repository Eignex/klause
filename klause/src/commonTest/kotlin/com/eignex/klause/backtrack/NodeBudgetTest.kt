package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The solve-spanning node cap. What distinguishes it from [BacktrackParams.maxDecisions] is that the
 * allowance is not renewed when a driver re-enters the engine, which is the property that lets two runs
 * be held to the same work.
 */
class NodeBudgetTest {

    /** [pigeons] pigeons into [holes] holes: each pigeon in >= 1 hole, each hole holds <= 1. UNSAT when
     *  pigeons exceed holes, and without cutting planes it takes far more nodes than any cap here. */
    private fun php(pigeons: Int, holes: Int): Problem {
        fun x(p: Int, h: Int) = p * holes + h
        val factors = ArrayList<Factor>()
        for (p in 0 until pigeons) {
            factors.add(Cardinality(IntArray(holes) { h -> Lit.make(x(p, h), true) }, min = 1, max = holes))
        }
        for (h in 0 until holes) {
            factors.add(Cardinality(IntArray(pigeons) { p -> Lit.make(x(p, h), true) }, min = 0, max = 1))
        }
        return Problem(pigeons * holes, 0, emptyArray(), factors.toTypedArray())
    }

    private fun params(budget: NodeBudget) = BacktrackParams(randomSeed = 1L, pbLearning = false, nodeBudget = budget)

    @Test
    fun `a spent allowance stops the search short of a verdict`() {
        val budget = NodeBudget(limit = 1)
        val result = BacktrackSolver(php(pigeons = 8, holes = 7).bake()).solve(params(budget))
        assertIs<SolveResult.Unknown>(result, "the first decision exhausts the allowance before the refutation")
        assertTrue(budget.exhausted(), "the allowance is what stopped it, spent=${budget.spent}")
    }

    @Test
    fun `the allowance is not renewed for a second search`() {
        val budget = NodeBudget(limit = 200)
        val problem = php(pigeons = 8, holes = 7).bake()
        BacktrackSolver(problem).solve(params(budget))
        val afterFirst = budget.spent
        BacktrackSolver(problem).solve(params(budget))
        assertTrue(
            budget.spent - afterFirst < budget.limit,
            "a re-entered search must not get a fresh allowance; it spent ${budget.spent - afterFirst} more",
        )
    }

    @Test
    fun `a search that finishes inside its allowance is unaffected`() {
        val budget = NodeBudget(limit = 1_000_000)
        val result = BacktrackSolver(php(pigeons = 5, holes = 4).bake()).solve(params(budget))
        assertIs<SolveResult.Unsat>(result, "a cap it never reaches must not change the verdict")
    }
}
