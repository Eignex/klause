package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.ExactComparison
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal data class ExplainedLinearBound(
    val value: BigFraction,
    val strict: Boolean,
    val premise: SearchAtomPremise,
)

internal data class PartialLinearImplication(val truth: Boolean, val premise: SearchAtomPremise)

internal data class PartialLinearDeduction(
    val factor: Int,
    val row: Int,
    val variable: Int,
    val truth: Boolean,
    val clause: IntArray,
)

internal data class PartialLinearProbe(
    val visited: Int,
    val eligible: Int,
    val unexplained: Int,
    val stopped: Boolean,
    val deductions: List<PartialLinearDeduction>,
)

internal data class PartialLinearPropagationStats(
    val candidateRows: Int,
    val invocations: Long,
    val visited: Long,
    val eligible: Long,
    val deductions: Long,
    val numericalDeferrals: Long,
    val arithmeticEvaluations: Long,
    val unchangedDeclines: Long,
    val producerNs: Long,
    val sourceAssertionNs: Long,
    val numericalQueryNs: Long,
    val sourceDefinitionAppends: Long,
    val sourceDefinitionNs: Long,
    val sourceBoundCalls: Long,
    val sourceBoundSides: Long,
    val sourceBoundNs: Long,
    val sourceRowVisits: Long,
    val sourceRowTouches: Long,
    val sourceRowInvalidations: Long,
)

internal data class PartialLinearSide(val value: BigFraction, val strict: Boolean)

internal data class PartialLinearInputs(val bound: BigFraction, val sides: List<PartialLinearSide?>)

internal fun partialLinearImplication(
    comparison: ExactComparison,
    bound: (column: Int, upper: Boolean) -> ExplainedLinearBound?,
    stop: Cancellation,
): PartialLinearImplication? {
    val arithmetic = PartialImplicationArithmetic(stop)
    if (!arithmetic.admit(comparison.bound)) return null
    val upper by lazy { arithmetic.activity(comparison, upper = true, bound) }
    val lower by lazy { arithmetic.activity(comparison, upper = false, bound) }
    fun implied(truth: Boolean, vararg sides: ExplainedLinearBound): PartialLinearImplication? =
        if (stop()) null else PartialLinearImplication(truth, SearchAtomPremise.All(sides.map { it.premise }))
    return when (comparison.op) {
        LinearOp.LE -> when {
            upper?.let { it.value < comparison.bound ||
                (it.value == comparison.bound && (!comparison.strict || it.strict)) } == true ->
                implied(true, requireNotNull(upper))
            lower?.let { it.value > comparison.bound ||
                (it.value == comparison.bound && (comparison.strict || it.strict)) } == true ->
                implied(false, requireNotNull(lower))
            else -> null
        }
        LinearOp.GE -> when {
            lower?.let { it.value > comparison.bound ||
                (it.value == comparison.bound && (!comparison.strict || it.strict)) } == true ->
                implied(true, requireNotNull(lower))
            upper?.let { it.value < comparison.bound ||
                (it.value == comparison.bound && (comparison.strict || it.strict)) } == true ->
                implied(false, requireNotNull(upper))
            else -> null
        }
        LinearOp.EQ, LinearOp.NE -> when {
            upper?.let { it.value < comparison.bound || (it.value == comparison.bound && it.strict) } == true ->
                implied(comparison.op == LinearOp.NE, requireNotNull(upper))
            lower?.let { it.value > comparison.bound || (it.value == comparison.bound && it.strict) } == true ->
                implied(comparison.op == LinearOp.NE, requireNotNull(lower))
            lower?.let { it.value == comparison.bound && !it.strict } == true &&
                upper?.let { it.value == comparison.bound && !it.strict } == true ->
                implied(comparison.op == LinearOp.EQ, requireNotNull(lower), requireNotNull(upper))
            else -> null
        }
    }
}

private class PartialImplicationArithmetic(private val stop: Cancellation) {
    fun admit(value: BigFraction): Boolean {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        stop.charge(1L + bits / Long.SIZE_BITS)
        return bits <= MAX_BITS && !stop()
    }

    fun activity(
        comparison: ExactComparison,
        upper: Boolean,
        bound: (Int, Boolean) -> ExplainedLinearBound?,
    ): ExplainedLinearBound? {
        var total = BigFraction.ZERO
        var strict = false
        val premises = ArrayList<SearchAtomPremise>()
        for ((column, coefficient) in comparison.terms) {
            stop.charge(1)
            if (!admit(coefficient)) return null
            if (coefficient.isZero) continue
            val side = bound(column, if (coefficient.signum() > 0) upper else !upper) ?: return null
            if (!admit(side.value) || side.premise === SearchAtomPremise.Unavailable) return null
            val product = coefficient * side.value
            if (!admit(product)) return null
            total += product
            if (!admit(total)) return null
            strict = strict || side.strict
            premises += side.premise
        }
        if (stop()) return null
        return ExplainedLinearBound(total, strict, SearchAtomPremise.All(premises))
    }

    private companion object {
        const val MAX_BITS = 4096
    }
}
