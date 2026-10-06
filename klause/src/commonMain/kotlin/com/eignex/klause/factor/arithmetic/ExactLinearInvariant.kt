package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.ceilDiv
import com.eignex.klause.factor.arithmetic.internals.floorDiv
import com.eignex.klause.factor.bool.internals.reifiedDegree
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.IntIntMap
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.abs

/**
 * LS invariant for a [Linear] or [ReifiedLinear] row whose running sum a `Long` cannot hold: over-64-bit
 * constants, or terms whose coefficients and domains can carry `Σ coeff·value` past the 64-bit range.
 *
 * The sum is kept exactly as a [BigInteger] in [LocalSearchState.refPayload], so violation and move deltas are
 * the row's own however large its terms grow. That costs an allocation per scored move, which is why only rows
 * [needsExactSum] selects take this form; every other row keeps the `Long` invariant.
 */
internal class ExactLinearInvariant(
    constants: IntegralConstants,
    private val vars: IntArray,
    private val op: LinearOp,
    /** The reifying Boolean, or -1 for a plain row. */
    private val auxBoolVar: Int = -1,
) : Invariant {
    private val coeffs: Array<BigInteger> = Array(vars.size) { constants.exactCoeff(it) }
    private val bound: BigInteger = constants.exactBound
    private val index = IntIntMap.build(vars, IntArray(vars.size) { it }, absent = -1)

    private fun coeffOf(intVar: Int): BigInteger {
        val i = index[intVar]
        return if (i < 0) BigInteger.ZERO else coeffs[i]
    }

    private fun sum(state: LocalSearchState, factorId: Int): BigInteger = state.refPayload[factorId] as BigInteger

    private fun holds(sum: BigInteger): Boolean {
        val c = sum.compareTo(bound)
        return when (op) {
            LinearOp.LE -> c <= 0
            LinearOp.GE -> c >= 0
            LinearOp.EQ -> c == 0
            LinearOp.NE -> c != 0
        }
    }

    private fun bodyDegree(sum: BigInteger, softCap: Int): Int {
        val residual = sum - bound
        return when (op) {
            LinearOp.LE -> if (residual <= BigInteger.ZERO) 0 else compressViolation(saturated(residual), softCap)
            LinearOp.GE -> if (residual >= BigInteger.ZERO) 0 else compressViolation(saturated(-residual), softCap)
            LinearOp.EQ -> if (residual == BigInteger.ZERO) 0 else compressViolation(saturated(residual.abs()), softCap)
            LinearOp.NE -> if (residual != BigInteger.ZERO) 0 else 1
        }
    }

    private fun degree(sum: BigInteger, aux: Boolean, softCap: Int): Int = if (auxBoolVar < 0) {
        bodyDegree(sum, softCap)
    } else {
        reifiedDegree(aux, holds(sum)) { bodyDegree(sum, softCap) }
    }

    private fun aux(state: LocalSearchState): Boolean = auxBoolVar >= 0 && state.assignment.boolValue(auxBoolVar)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = BigInteger.ZERO
        for (i in vars.indices) sum += coeffs[i] * BigInteger.fromLong(state.assignment.intValue(vars[i]))
        state.refPayload[factorId] = sum
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        degree(sum(state, factorId), aux(state), state.violationSoftCap) > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degree(sum(state, factorId), aux(state), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val step = BigInteger.fromLong(newValue) - BigInteger.fromLong(state.assignment.intValue(intVar))
        val newSum = sum(state, factorId) + coeffOf(intVar) * step
        return degree(newSum, aux(state), state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val oldSum = sum(state, factorId)
        val step = BigInteger.fromLong(state.assignment.intValue(intVar)) - BigInteger.fromLong(oldValue)
        val newSum = oldSum + coeffOf(intVar) * step
        state.refPayload[factorId] = newSum
        val aux = aux(state)
        return degree(newSum, aux, state.violationSoftCap) - degree(oldSum, aux, state.violationSoftCap)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (boolVar != auxBoolVar) return 0
        return degree(sum(state, factorId), !aux(state), state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (boolVar != auxBoolVar) return 0
        val sum = sum(state, factorId)
        val aux = aux(state)
        return degree(sum, aux, state.violationSoftCap) - degree(sum, !aux, state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val sum = sum(state, factorId)
        val aux = aux(state)
        // The body must hold for a plain row or a true reifier, and fail for a false one.
        val want = auxBoolVar < 0 || aux
        if (holds(sum) == want) return
        if (auxBoolVar >= 0) sink.addBoolFlip(auxBoolVar)
        val n = vars.size
        val sampled = n > REPAIR_SAMPLE_CAP
        repeat(if (sampled) REPAIR_SAMPLE_CAP else n) { k ->
            val i = if (sampled) state.rng.nextInt(n) else k
            val v = vars[i]
            val c = coeffs[i]
            if (c == BigInteger.ZERO) return@repeat
            val cur = state.assignment.intValue(v)
            val d = state.rootDomains[v]
            if (op == LinearOp.NE || (op == LinearOp.EQ && !want)) {
                if (cur > d.min) sink.addChannelingIntSet(state, v, d.lower(cur))
                if (cur < d.max) sink.addChannelingIntSet(state, v, d.higher(cur))
                return@repeat
            }
            val target = target(c, bound - (sum - c * BigInteger.fromLong(cur)), want) ?: return@repeat
            val clamped = clampExact(d, target)
            if (clamped != cur) sink.addChannelingIntSet(state, v, clamped)
        }
    }

    // The value of a term with coefficient [c] that brings the body to [want], given [remaining] = bound minus the
    // rest of the row; null when no integer value of the term satisfies an equality. Mirrors snapLinearTarget.
    private fun target(c: BigInteger, remaining: BigInteger, want: Boolean): BigInteger? {
        val positive = c > BigInteger.ZERO
        return when (op) {
            LinearOp.EQ -> if (remaining % c == BigInteger.ZERO) remaining / c else null

            LinearOp.LE -> when {
                want -> if (positive) floorDiv(remaining, c) else ceilDiv(remaining, c)
                positive -> floorDiv(remaining, c) + BigInteger.ONE
                else -> ceilDiv(remaining, c) - BigInteger.ONE
            }

            LinearOp.GE -> when {
                want -> if (positive) ceilDiv(remaining, c) else floorDiv(remaining, c)
                positive -> ceilDiv(remaining, c) - BigInteger.ONE
                else -> floorDiv(remaining, c) + BigInteger.ONE
            }

            LinearOp.NE -> null
        }
    }

    private companion object {
        val LONG_MAX: BigInteger = BigInteger.fromLong(Long.MAX_VALUE)
        val LONG_MIN: BigInteger = BigInteger.fromLong(Long.MIN_VALUE)

        // Repair candidates drawn per pick on a wide row, as [LinearInvariant] does.
        const val REPAIR_SAMPLE_CAP: Int = 256

        fun saturated(value: BigInteger): Long = when {
            value >= LONG_MAX -> Long.MAX_VALUE
            value <= LONG_MIN -> Long.MIN_VALUE
            else -> value.longValue()
        }

        fun clampExact(d: IntDomain, target: BigInteger): Long = d.clamp(saturated(target))
    }
}

/**
 * Whether a row's running sum can leave the 64-bit range over [domains]: `|bound| + Σ |coeff|·max(|min|, |max|)`
 * reaches 2^61. Below that, every partial sum and every move delta `coeff·(new − old)` fits a `Long`, so the `Long`
 * invariant is exact and the row pays nothing for being exact. Estimated in `Double`, whose rounding is far inside
 * the factor of two between this limit and the `Long` range.
 */
internal fun needsExactSum(constants: IntegralConstants, vars: IntArray, domains: Array<IntDomain>): Boolean {
    if (constants !is IntegerConstants) return true
    var total = abs(constants.bound.toDouble())
    for (i in vars.indices) {
        val d = domains[vars[i]]
        val reach = maxOf(abs(d.min.toDouble()), abs(d.max.toDouble()))
        total += abs(constants.coeff(i).toDouble()) * reach
        if (total >= EXACT_SUM_LIMIT) return true
    }
    return false
}

private const val EXACT_SUM_LIMIT: Double = (1L shl 61).toDouble()
