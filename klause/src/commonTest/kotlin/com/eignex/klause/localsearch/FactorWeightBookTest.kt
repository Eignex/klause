package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class FactorWeightBookTest {

    private fun linear(): Factor = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)
    private fun clause(): Factor = Clause(intArrayOf(Lit.make(0, true)))

    private fun problem(factors: List<Factor>, implied: BooleanArray? = null) = Problem(
        numBoolVars = 1,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 5)),
        factors = factors.toTypedArray(),
        impliedFactorMask = implied,
    )

    @Test
    fun `class normalization should damp the over-represented kind and spare the rest`() {
        // Mean class size 5: Linear (8 > 5) scales to 5/8, Clause (2 <= 5) stays at 1.0.
        val factors = List(8) { linear() } + List(2) { clause() }
        val state = LocalSearchState(problem(factors).bake(), Random(0))
        state.weights.normalizeWeightsByClass = true
        val w = state.weights.factorWeights
        for (i in 0 until 8) assertEquals(5.0 / 8.0, w[i], 1e-9)
        for (i in 8 until 10) assertEquals(1.0, w[i], 1e-9)
    }

    @Test
    fun `base factor weights should snapshot the seed and survive live-weight mutation`() {
        val factors = List(8) { linear() } + List(2) { clause() }
        val implied = BooleanArray(10).also { for (i in 0 until 6) it[i] = true }
        val state = LocalSearchState(problem(factors, implied).bake(), Random(0))
        val base = state.weights.baseFactorWeights
        for (i in 0 until 6) assertEquals(IMPLIED_FACTOR_INITIAL_WEIGHT, base[i], 1e-9)
        for (i in 6 until 10) assertEquals(1.0, base[i], 1e-9)
        for (i in state.weights.factorWeights.indices) state.weights.factorWeights[i] += 5.0
        for (i in 0 until 6) assertEquals(IMPLIED_FACTOR_INITIAL_WEIGHT, state.weights.baseFactorWeights[i], 1e-9)
        for (i in 6 until 10) assertEquals(1.0, state.weights.baseFactorWeights[i], 1e-9)
    }

    @Test
    fun `implied factors should be pinned and excluded from the class tally`() {
        // The tally sees 2 non-implied Linear and 2 Clause: mean 2, so neither class is scaled.
        val factors = List(8) { linear() } + List(2) { clause() }
        val implied = BooleanArray(10).also { for (i in 0 until 6) it[i] = true }
        val state = LocalSearchState(problem(factors, implied).bake(), Random(0))
        state.weights.normalizeWeightsByClass = true
        val w = state.weights.factorWeights
        for (i in 0 until 6) assertEquals(IMPLIED_FACTOR_INITIAL_WEIGHT, w[i], 1e-9)
        for (i in 6 until 10) assertEquals(1.0, w[i], 1e-9)
    }
}
