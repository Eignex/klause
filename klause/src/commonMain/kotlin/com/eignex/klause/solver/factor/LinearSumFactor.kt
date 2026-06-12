package com.eignex.klause.solver.factor

import com.eignex.klause.solver.localsearch.LocalSearchState

/** Body abstraction for the integer weighted-sum factors [Linear] and [ReifiedLinear]:
 *  `Σ coeffs(i) · vars(i) ⟨op⟩ bound`. */
abstract class LinearSumFactor internal constructor(
    terms: CoalescedTerms,
    /** Relation between the weighted sum and [bound]. */
    val op: LinearOp,
    /** Right-hand-side bound. */
    val bound: Int,
) : WeightedSumFactor() {

    /** Integer variable ids, parallel to [coeffs]; each variable appears at most once. */
    val vars: IntArray = terms.vars

    /** Coefficients, parallel to [vars]. */
    val coeffs: IntArray = terms.coeffs

    init {
        require(coeffs.isNotEmpty()) { "linear sum must have at least one term" }
    }

    private val coeffLookup: CoeffLookup = CoeffLookup.build(vars, coeffs)

    final override val intVars: IntArray = vars

    final override fun holds(sum: Long): Boolean = linearHolds(sum, op, bound)

    final override fun residual(sum: Long, softCap: Int): Int = linearResidual(sum, op, bound, softCap)

    final override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0L
        for (i in vars.indices) sum += coeffs[i].toLong() * state.assignment.intValue(vars[i])
        state.longPayload[factorId] = sum
    }

    protected fun coeffOf(intVar: Int): Int = coeffLookup.coeffOf(intVar)

    protected fun snapTarget(coeff: Int, sumWithout: Long, wantHolds: Boolean): Long? =
        snapLinearTarget(op, bound, coeff, sumWithout, wantHolds)
}
