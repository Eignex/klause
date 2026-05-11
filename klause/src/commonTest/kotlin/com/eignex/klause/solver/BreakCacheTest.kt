package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedLinear
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class BreakCacheTest {

    private fun naiveBreakScore(state: SolverState, boolVar: Int): Int {
        var count = 0
        for (factorId in state.problem.boolOccurrences[boolVar]) {
            val f = state.problem.factors[factorId]
            if (f.deltaIfBoolFlipped(state, factorId, boolVar) > 0) count++
        }
        return count
    }

    private fun assertCacheConsistent(state: SolverState, label: String) {
        for (v in 0 until state.problem.numBoolVars) {
            val cached = state.breakScore(Move.BoolFlip(v))
            val naive = naiveBreakScore(state, v)
            assertEquals(naive, cached, "boolVar=$v $label")
        }
    }

    @Test
    fun `cache matches scan after random move sequence on mixed problem`() {

        val numBool = 4
        val numInt = 2
        val intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5))
        val factors = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
            Cardinality(
                literals = intArrayOf(Lit.make(0, true), Lit.make(2, true), Lit.make(3, true)),
                min = 1,
                max = 2,
            ),
            ReifiedLinear(
                auxBoolVar = 1,
                coeffs = intArrayOf(1, 2),
                vars = intArrayOf(0, 1),
                op = LinearOp.LE,
                bound = 4,
            ),
        )
        val problem = Problem(numBool, numInt, intDomains, factors)
        val state = SolverState(problem, Random(42))
        state.restart()
        assertCacheConsistent(state, "after restart")

        val rng = Random(123)
        repeat(200) { step ->
            val pickInt = numInt > 0 && rng.nextInt(3) == 0
            if (pickInt) {
                val v = rng.nextInt(numInt)
                val d = problem.intDomains[v]
                val target = d.min + rng.nextInt(d.size)
                state.apply(Move.IntSet(v, target))
                assertCacheConsistent(state, "after IntSet($v=$target) at step=$step")
            } else {
                val v = rng.nextInt(numBool)
                state.apply(Move.BoolFlip(v))
                assertCacheConsistent(state, "after BoolFlip($v) at step=$step")
            }
        }
    }

    @Test
    fun `restart clears stale cache`() {

        val factors = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
        )
        val problem = Problem(2, 0, emptyArray(), factors)
        val state = SolverState(problem, Random(0))
        state.restart()

        state.apply(Move.BoolFlip(0))
        assertCacheConsistent(state, "after first flip")
        state.restart()
        assertCacheConsistent(state, "after restart")
    }
}
