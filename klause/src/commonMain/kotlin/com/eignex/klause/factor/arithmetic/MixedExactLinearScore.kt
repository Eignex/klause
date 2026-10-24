package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.bool.internals.reifiedDegree
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import kotlin.math.abs
import kotlin.math.ceil

internal class MixedExactLinearScore(
    private val row: ExactLinear,
    private val indicator: Int = -1,
    private val whenOne: Boolean = true,
) : Invariant {
    private val scale = run {
        var maximum = bigIntOf(1)
        val bound = row.constants.exactBound.abs()
        if (bound > maximum) maximum = bound
        for (index in 0 until row.size) {
            val magnitude = row.constants.exactCoeff(index).abs()
            if (magnitude > maximum) maximum = magnitude
        }
        maximum
    }
    private val coefficients = DoubleArray(row.size) { BigFraction.of(row.constants.exactCoeff(it), scale).toDouble() }
    private val bound = BigFraction.of(row.constants.exactBound, scale).toDouble()
    private val index = IntIntMap.build(IntArray(row.size) { row.ref(it) }, IntArray(row.size) { it }, absent = -1)
    private val leaves = run {
        var size = 1
        while (size < row.size) size *= 2
        size
    }

    // A fixed reduction tree has no history-dependent update drift, including during probe rollback.
    override fun initialize(state: LocalSearchState, factorId: Int) {
        val tree = (state.refPayload[factorId] as? DoubleArray) ?: DoubleArray(2 * leaves).also {
            state.refPayload[factorId] = it
        }
        for (k in 0 until row.size) {
            val ref = row.ref(k)
            val value = if (Term.isInt(ref)) state.assignment.intValue(Term.intVar(ref)).toDouble()
            else state.assignment.realValue(Term.realVar(ref))
            tree[leaves + k] = coefficients[k] * value
        }
        for (k in leaves - 1 downTo 1) tree[k] = tree[2 * k] + tree[2 * k + 1]
    }

    private fun activity(state: LocalSearchState, factorId: Int, ref: Int, value: Double): Double {
        val tree = state.refPayload[factorId] as DoubleArray
        val k = index[ref]
        if (k < 0) return tree[1]
        var node = leaves + k
        var sum = coefficients[k] * value
        while (node > 1) {
            sum = if ((node and 1) == 0) sum + tree[node + 1] else tree[node - 1] + sum
            node /= 2
        }
        return sum
    }

    private fun update(state: LocalSearchState, factorId: Int, ref: Int, value: Double) {
        val k = index[ref]
        if (k < 0) return
        val tree = state.refPayload[factorId] as DoubleArray
        var node = leaves + k
        tree[node] = coefficients[k] * value
        while (node > 1) {
            node /= 2
            tree[node] = tree[2 * node] + tree[2 * node + 1]
        }
    }

    private fun score(
        state: LocalSearchState,
        activity: Double,
        changedInt: Int = -1,
        integer: Long = 0L,
        flippedBool: Int = -1,
    ): Int {
        if (indicator >= 0) {
            val value = if (indicator == changedInt) integer else state.assignment.intValue(indicator)
            if (value != (if (whenOne) 1L else 0L)) return 0
        }
        val difference = activity - bound
        if (!difference.isFinite()) return state.violationSoftCap
        val residual = when (row.relation) {
            LinearOp.LE -> maxOf(0.0, difference)
            LinearOp.GE -> maxOf(0.0, -difference)
            LinearOp.EQ -> abs(difference)
            LinearOp.NE -> if (difference == 0.0) 1.0 else 0.0
        }
        val holds = when (row.relation) {
            LinearOp.LE -> if (row.strict) difference < 0.0 else difference <= 0.0
            LinearOp.GE -> if (row.strict) difference > 0.0 else difference >= 0.0
            LinearOp.EQ -> difference == 0.0
            LinearOp.NE -> difference != 0.0
        }
        fun bodyDegree(): Int = if (!residual.isFinite()) state.violationSoftCap else
            maxOf(1, compressViolation(ceil(residual * 1_000_000.0).toLong(), state.violationSoftCap))
        if (row.activator == LinearRow.ALWAYS) return if (holds) 0 else bodyDegree()
        val actual = state.assignment.boolValue(row.activator)
        val wanted = if (row.activator == flippedBool) !actual else actual
        return reifiedDegree(wanted, holds, ::bodyDegree)
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        score(state, (state.refPayload[factorId] as DoubleArray)[1])

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = violationDegree(state, factorId) != 0

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        score(state, activity(state, factorId, Term.ofIntVar(intVar), newValue.toDouble()), intVar, newValue) -
            state.factorDegree[factorId]

    override fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int =
        score(state, activity(state, factorId, Term.ofRealVar(realVar), newValue)) - state.factorDegree[factorId]

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        score(state, (state.refPayload[factorId] as DoubleArray)[1], flippedBool = boolVar) - state.factorDegree[factorId]

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        update(state, factorId, Term.ofIntVar(intVar), state.assignment.intValue(intVar).toDouble())
        return 0
    }

    override fun applyRealSet(state: LocalSearchState, factorId: Int, realVar: Int, oldValue: Double): Int {
        update(state, factorId, Term.ofRealVar(realVar), state.assignment.realValue(realVar))
        return 0
    }
}
