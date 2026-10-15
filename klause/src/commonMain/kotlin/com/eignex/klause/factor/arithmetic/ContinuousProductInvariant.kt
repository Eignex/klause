package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import kotlin.math.sqrt

internal class ContinuousProductInvariant(
    private val factor: ContinuousProduct,
) : Invariant {
    private val a = factor.a
    private val b = factor.b
    private val result = factor.result

    private fun score(state: LocalSearchState, changed: Int = -1, value: Double = 0.0, flipped: Int = -1): Int {
        fun read(id: Int): Double = if (id == changed) value else state.assignment.realValue(id)
        val product = numericalEqualityDegree(read(a) * read(b), read(result), state.violationSoftCap)
        val choices = factor.finiteChoices ?: return product
        val operand = read(choices.operand)
        val membership = numericalEqualityDegree(choices.nearest(operand), operand, state.violationSoftCap)
        var degree = product.toLong() + membership
        if (choices.hasGuards) for (index in 0 until choices.size) {
            val literal = choices.literalAt(index)
            val variable = Lit.variable(literal)
            val boolean = state.assignment.boolValue(variable) xor (variable == flipped)
            val selected = numericalEqualityDegree(choices.valueAt(index), operand, state.violationSoftCap) == 0
            if (Lit.evaluate(literal, boolean) != selected) degree++
        }
        return degree.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = score(state) != 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = score(state)

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        score(state, flipped = boolVar) - score(state)

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        score(state) - score(state, flipped = boolVar)

    override fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int =
        score(state, realVar, newValue) - score(state)

    override fun applyRealSet(state: LocalSearchState, factorId: Int, realVar: Int, oldValue: Double): Int =
        score(state) - score(state, realVar, oldValue)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val left = state.assignment.realValue(a)
        val right = state.assignment.realValue(b)
        val output = state.assignment.realValue(result)
        val before = score(state)
        fun propose(variable: Int, value: Double) {
            if (value.isFinite() && value != state.assignment.realValue(variable) &&
                value >= state.problem.realLower[variable] && value <= state.problem.realUpper[variable] &&
                score(state, variable, value) < before
            ) sink.addRealSet(variable, value)
        }
        factor.finiteChoices?.let { choices ->
            propose(choices.operand, choices.nearest(state.assignment.realValue(choices.operand)))
        }
        propose(result, left * right)
        if (a == b && output >= 0.0) {
            val root = sqrt(output)
            propose(a, root)
            propose(a, -root)
        } else {
            if (right != 0.0) propose(a, output / right)
            if (left != 0.0) propose(b, output / left)
        }
        if (a == result || b == result) {
            propose(result, 0.0)
            if (a == b) propose(result, 1.0)
        }
    }
}
