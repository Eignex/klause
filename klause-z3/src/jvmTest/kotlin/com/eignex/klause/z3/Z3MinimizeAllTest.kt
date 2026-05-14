package com.eignex.klause.z3

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Z3MinimizeAllTest {

    @Test
    fun `top-k yields ascending objective values`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val results = Z3Sampler(problem).minimizeAll(objective, Z3Params(), k = 3).toList()
        assertEquals(3, results.size)
        val scores = results.map { objective.evaluate(it) }
        for (i in 0 until scores.size - 1) {
            assertTrue(scores[i] <= scores[i + 1], "non-ascending: $scores")
        }
        assertEquals(3.0, scores[0], "first should be global optimum")
    }

    @Test
    fun `top-k yields distinct samples`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        val results = Z3Sampler(problem).minimizeAll(objective, Z3Params(), k = 4).toList()
        assertEquals(4, results.size)
        assertEquals(4, results.toSet().size, "samples must be distinct")
    }

    @Test
    fun `top-k stops early when feasible space is exhausted`() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(1.0, 2.0))
        val results = Z3Sampler(problem).minimizeAll(objective, Z3Params(), k = 10).toList()
        // Only two feasible models: (T,F) and (F,T).
        assertEquals(2, results.size)
    }

    @Test
    fun `top-k on int objective is ascending`() {
        val problem = Problem(
            numBoolVars = 0, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2)),
        )
        val objective = LinearObjective(intCoefficients = doubleArrayOf(1.0))
        val results = Z3Sampler(problem).minimizeAll(objective, Z3Params(), k = 3).toList()
        assertEquals(3, results.size)
        assertEquals(listOf(2, 3, 4), results.map { it.ints[0] })
    }

    @Test
    fun `k=0 yields empty`() {
        val problem = Problem(1, 0, emptyArray(), emptyList())
        val results = Z3Sampler(problem)
            .minimizeAll(LinearObjective(boolWeights = doubleArrayOf(1.0)), Z3Params(), 0)
            .toList()
        assertEquals(0, results.size)
    }
}
