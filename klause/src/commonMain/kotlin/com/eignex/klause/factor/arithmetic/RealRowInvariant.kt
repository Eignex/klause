package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.bool.internals.reifiedDegree
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.IntIntMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * LS invariant for a linear row over continuous columns: a [Linear] carrying real terms, or the body of a
 * [ReifiedRealLinear] when [auxBoolVar] is set.
 *
 * The running sum lives in floating point in [LocalSearchState.doublePayload], so the row is decided within a
 * tolerance scaled to its constants: an equality holds within that tolerance, and an inequality must clear its bound by
 * that much on the strict side. That makes zero violation a proposal rather than a proof — the exact decision
 * belongs to the candidate completion — and the engine re-sums these rows from scratch on a schedule so incremental
 * rounding cannot accumulate past the tolerance.
 */
internal class RealRowInvariant(
    private val intVars: IntArray,
    private val intCoeffs: DoubleArray,
    private val realVars: IntArray,
    private val realCoeffs: DoubleArray,
    private val op: LinearOp,
    private val bound: Double,
    private val strict: Boolean,
    /** The reifying Boolean, or -1 for a plain row. */
    private val auxBoolVar: Int = -1,
) : Invariant {
    private val intIndex = IntIntMap.build(intVars, IntArray(intVars.size) { it }, absent = -1)
    private val realIndex = IntIntMap.build(realVars, IntArray(realVars.size) { it }, absent = -1)

    private val scale: Double = maxOf(
        1.0,
        abs(bound),
        intCoeffs.maxOfOrNull(::abs) ?: 0.0,
        realCoeffs.maxOfOrNull(::abs) ?: 0.0,
    )

    /** How far the row may miss its bound and still read as satisfied, and how far a strict side must clear it. */
    private val tolerance: Double = TOLERANCE_PER_SCALE * scale

    // One unit of graded violation: residuals are counted in these before compression.
    private val unit: Double = UNIT_PER_SCALE * scale

    private fun intCoeff(v: Int): Double = intIndex[v].let { if (it < 0) 0.0 else intCoeffs[it] }

    private fun realCoeff(r: Int): Double = realIndex[r].let { if (it < 0) 0.0 else realCoeffs[it] }

    // How far [sum] misses the body, 0 when it holds; the strict side must clear the bound by the tolerance.
    private fun shortfall(sum: Double): Double {
        val r = sum - bound
        return when (op) {
            LinearOp.LE -> if (strict) {
                maxOf(0.0, r + tolerance)
            } else if (r <= tolerance) {
                0.0
            } else {
                r
            }

            LinearOp.GE -> if (strict) {
                maxOf(0.0, tolerance - r)
            } else if (-r <= tolerance) {
                0.0
            } else {
                -r
            }

            LinearOp.EQ -> if (abs(r) <= tolerance) 0.0 else abs(r)

            LinearOp.NE -> if (abs(r) > tolerance) 0.0 else tolerance
        }
    }

    private fun bodyDegree(sum: Double, softCap: Int): Int {
        val miss = shortfall(sum)
        if (miss == 0.0) return 0
        val units = ceil(miss / unit)
        return compressViolation(
            if (units >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else maxOf(1L, units.toLong()),
            softCap,
        )
    }

    private fun degree(sum: Double, aux: Boolean, softCap: Int): Int = if (auxBoolVar < 0) {
        bodyDegree(sum, softCap)
    } else {
        reifiedDegree(aux, shortfall(sum) == 0.0) { bodyDegree(sum, softCap) }
    }

    private fun aux(state: LocalSearchState): Boolean = auxBoolVar >= 0 && state.assignment.boolValue(auxBoolVar)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0.0
        for (i in intVars.indices) sum += intCoeffs[i] * state.assignment.intValue(intVars[i]).toDouble()
        for (i in realVars.indices) sum += realCoeffs[i] * state.assignment.realValue(realVars[i])
        state.doublePayload[factorId] = sum
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = violationDegree(state, factorId) > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degree(state.doublePayload[factorId], aux(state), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val step = newValue.toDouble() - state.assignment.intValue(intVar).toDouble()
        val newSum = state.doublePayload[factorId] + intCoeff(intVar) * step
        return degree(newSum, aux(state), state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val oldSum = state.doublePayload[factorId]
        val newSum = oldSum + intCoeff(intVar) * (state.assignment.intValue(intVar).toDouble() - oldValue.toDouble())
        return commit(state, factorId, oldSum, newSum)
    }

    override fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int {
        val newSum = state.doublePayload[factorId] + realCoeff(
            realVar,
        ) * (newValue - state.assignment.realValue(realVar))
        return degree(newSum, aux(state), state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyRealSet(state: LocalSearchState, factorId: Int, realVar: Int, oldValue: Double): Int {
        val oldSum = state.doublePayload[factorId]
        val newSum = oldSum + realCoeff(realVar) * (state.assignment.realValue(realVar) - oldValue)
        return commit(state, factorId, oldSum, newSum)
    }

    private fun commit(state: LocalSearchState, factorId: Int, oldSum: Double, newSum: Double): Int {
        state.doublePayload[factorId] = newSum
        val aux = aux(state)
        return degree(newSum, aux, state.violationSoftCap) - degree(oldSum, aux, state.violationSoftCap)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (boolVar != auxBoolVar) return 0
        return degree(state.doublePayload[factorId], !aux(state), state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (boolVar != auxBoolVar) return 0
        val sum = state.doublePayload[factorId]
        val aux = aux(state)
        return degree(sum, aux, state.violationSoftCap) - degree(sum, !aux, state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val sum = state.doublePayload[factorId]
        val want = auxBoolVar < 0 || aux(state)
        if ((shortfall(sum) == 0.0) == want) return
        if (auxBoolVar >= 0) sink.addBoolFlip(auxBoolVar)
        // The sum the row must reach: inside the body when it should hold, past the bound when it should fail.
        val goal = goalSum(want) ?: return
        for (i in realVars.indices) {
            val c = realCoeffs[i]
            if (c == 0.0) continue
            val r = realVars[i]
            val cur = state.assignment.realValue(r)
            val target = (cur + (goal - sum) / c).coerceIn(state.problem.realLower[r], state.problem.realUpper[r])
            if (target.isFinite() && target != cur) sink.addRealSet(r, target)
        }
        for (i in intVars.indices) {
            val c = intCoeffs[i]
            if (c == 0.0) continue
            val v = intVars[i]
            val cur = state.assignment.intValue(v)
            val exact = cur + (goal - sum) / c
            if (!exact.isFinite()) continue
            // Round toward the side that keeps the goal: down when raising the term lowers the sum enough.
            val rounded = if ((goal >= sum) == (c > 0)) ceil(exact) else floor(exact)
            if (abs(rounded) >= Long.MAX_VALUE.toDouble()) continue
            val clamped = state.rootDomains[v].clamp(rounded.toLong())
            if (clamped != cur) sink.addChannelingIntSet(state, v, clamped)
        }
    }

    // A sum on the wanted side of the bound with twice the tolerance to spare, or null for a disequality to fail.
    private fun goalSum(want: Boolean): Double? {
        val margin = 2 * tolerance
        return when (op) {
            LinearOp.LE -> if (want) bound - margin else bound + margin
            LinearOp.GE -> if (want) bound + margin else bound - margin
            LinearOp.EQ -> if (want) bound else bound + margin
            LinearOp.NE -> if (want) bound + margin else bound
        }
    }

    private companion object {
        // Relative tolerance of a row decided in floating point.
        const val TOLERANCE_PER_SCALE: Double = 1e-9

        // Relative size of one unit of graded violation.
        const val UNIT_PER_SCALE: Double = 1e-6
    }
}

/**
 * LS invariant for [RealProduct]: `result = intOperand · realOperand`, decided in floating point within a tolerance
 * scaled to the operands, like [RealRowInvariant].
 */
internal class RealProductInvariant(
    private val intOperand: Int,
    private val realOperand: Int,
    private val result: Int,
) : Invariant {

    private fun residual(k: Long, x: Double, y: Double): Double = y - k.toDouble() * x

    private fun degree(k: Long, x: Double, y: Double, softCap: Int): Int {
        val r = abs(residual(k, x, y))
        val scale = maxOf(1.0, abs(y), abs(k.toDouble() * x))
        if (r <= PRODUCT_TOLERANCE * scale) return 0
        return compressViolation(
            maxOf(1L, ceil(r / (PRODUCT_UNIT * scale)).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()),
            softCap,
        )
    }

    private fun current(state: LocalSearchState, k: Long = state.assignment.intValue(intOperand)): Int = degree(
        k,
        state.assignment.realValue(realOperand),
        state.assignment.realValue(result),
        state.violationSoftCap,
    )

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = current(state) > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = current(state)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        if (intVar == intOperand) current(state, newValue) - state.factorDegree[factorId] else 0

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int =
        if (intVar == intOperand) current(state) - current(state, oldValue) else 0

    override fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int {
        val k = state.assignment.intValue(intOperand)
        val x = if (realVar == realOperand) newValue else state.assignment.realValue(realOperand)
        val y = if (realVar == result) newValue else state.assignment.realValue(result)
        return degree(k, x, y, state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyRealSet(state: LocalSearchState, factorId: Int, realVar: Int, oldValue: Double): Int {
        val k = state.assignment.intValue(intOperand)
        val x = state.assignment.realValue(realOperand)
        val y = state.assignment.realValue(result)
        val oldX = if (realVar == realOperand) oldValue else x
        val oldY = if (realVar == result) oldValue else y
        return degree(k, x, y, state.violationSoftCap) - degree(k, oldX, oldY, state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (current(state) == 0) return
        val k = state.assignment.intValue(intOperand)
        val x = state.assignment.realValue(realOperand)
        val y = state.assignment.realValue(result)
        val lower = state.problem.realLower
        val upper = state.problem.realUpper
        val product = (k.toDouble() * x).coerceIn(lower[result], upper[result])
        if (product.isFinite()) sink.addRealSet(result, product)
        if (k != 0L) {
            val operand = (y / k.toDouble()).coerceIn(lower[realOperand], upper[realOperand])
            if (operand.isFinite()) sink.addRealSet(realOperand, operand)
        }
        if (x != 0.0) {
            val ratio = (y / x).let {
                if (it.isFinite() && abs(
                        it,
                    ) < Long.MAX_VALUE.toDouble()
                ) {
                    it.roundToLong()
                } else {
                    null
                }
            }
            if (ratio != null) {
                val clamped = state.rootDomains[intOperand].clamp(ratio)
                if (clamped != k) sink.addChannelingIntSet(state, intOperand, clamped)
            }
        }
    }

    private companion object {
        const val PRODUCT_TOLERANCE: Double = 1e-9
        const val PRODUCT_UNIT: Double = 1e-6
    }
}
