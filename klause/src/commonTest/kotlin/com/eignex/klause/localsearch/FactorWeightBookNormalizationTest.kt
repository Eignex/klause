package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Initial [FactorWeightBook.factorWeights] seeding: with [LocalSearchParams.normalizeWeightsByClass]
 * on, an over-populated factor *kind* is damped so it can't steer the descent by sheer count, while
 * smaller kinds keep weight 1.0.
 */
class FactorWeightBookNormalizationTest {

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
    fun `off by default leaves every factor at weight 1`() {
        val factors = List(8) { linear() } + List(2) { clause() }
        val state = LocalSearchState(problem(factors), Random(0))
        assertTrue(state.weights.factorWeights.all { it == 1.0 })
    }

    @Test
    fun `class normalization damps the over-represented kind and spares the rest`() {
        // 8 Linear + 2 Clause: mean class size = 5, so Linear (8 > 5) is scaled to 5/8 each,
        // Clause (2 <= 5) stays at 1.0.
        val factors = List(8) { linear() } + List(2) { clause() }
        val state = LocalSearchState(problem(factors), Random(0))
        state.weights.normalizeWeightsByClass = true
        val w = state.weights.factorWeights
        for (i in 0 until 8) assertEquals(5.0 / 8.0, w[i], 1e-9)
        for (i in 8 until 10) assertEquals(1.0, w[i], 1e-9)
    }

    @Test
    fun `balanced classes are left untouched`() {
        // 3 Linear + 3 Clause: mean = 3, neither class exceeds it, so nothing is damped.
        val factors = List(3) { linear() } + List(3) { clause() }
        val state = LocalSearchState(problem(factors), Random(0))
        state.weights.normalizeWeightsByClass = true
        assertTrue(state.weights.factorWeights.all { it == 1.0 })
    }

    @Test
    fun `base factor weights snapshot the seed and survive live-weight mutation`() {
        // The smoothing target must be the seed, not the live (bumped) weights — capture it once and
        // hold it immutable so decay restores the proactive landscape.
        val factors = List(8) { linear() } + List(2) { clause() }
        val implied = BooleanArray(10).also { for (i in 0 until 6) it[i] = true }
        val state = LocalSearchState(problem(factors, implied), Random(0))
        val base = state.weights.baseFactorWeights
        for (i in 0 until 6) assertEquals(IMPLIED_FACTOR_INITIAL_WEIGHT, base[i], 1e-9)
        for (i in 6 until 10) assertEquals(1.0, base[i], 1e-9)
        // Bumping the live weights leaves the baseline untouched.
        for (i in state.weights.factorWeights.indices) state.weights.factorWeights[i] += 5.0
        for (i in 0 until 6) assertEquals(IMPLIED_FACTOR_INITIAL_WEIGHT, state.weights.baseFactorWeights[i], 1e-9)
        for (i in 6 until 10) assertEquals(1.0, state.weights.baseFactorWeights[i], 1e-9)
    }

    @Test
    fun `implied factors are pinned and excluded from the class tally`() {
        // 8 Linear (first 6 implied) + 2 Clause. The class tally counts only the 2 non-implied
        // Linear and 2 Clause: mean = 2, neither exceeds it, so the structural Linear stay at 1.0
        // rather than being penalised for sharing a type with the implied bulk. The 6 implied
        // factors are pinned to the implied seed.
        val factors = List(8) { linear() } + List(2) { clause() }
        val implied = BooleanArray(10).also { for (i in 0 until 6) it[i] = true }
        val state = LocalSearchState(problem(factors, implied), Random(0))
        state.weights.normalizeWeightsByClass = true
        val w = state.weights.factorWeights
        for (i in 0 until 6) assertEquals(IMPLIED_FACTOR_INITIAL_WEIGHT, w[i], 1e-9)
        for (i in 6 until 10) assertEquals(1.0, w[i], 1e-9)
    }
}
