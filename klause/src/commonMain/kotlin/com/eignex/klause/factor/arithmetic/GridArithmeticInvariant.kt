package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.distance
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

internal class GridArithmeticInvariant(private val relation: GridArithmetic) : Invariant {
    private fun score(state: LocalSearchState, value: (Int) -> Long): Int {
        if (relation.inputs.any { !it.contains(value(it.variable)) } ||
            !relation.output.contains(value(relation.output.variable))) return state.violationSoftCap
        val image = relation.image(value) ?: return maxOf(1, numericalEqualityDegree(relation.evaluate(value),
            relation.output.value(value(relation.output.variable)), state.violationSoftCap))
        return compressViolation(distance(image, value(relation.output.variable)), state.violationSoftCap)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        score(state, state.assignment::intValue) != 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = score(state, state.assignment::intValue)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        score(state) { if (it == intVar) newValue else state.assignment.intValue(it) } -
            score(state, state.assignment::intValue)

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int =
        score(state, state.assignment::intValue) -
            score(state) { if (it == intVar) oldValue else state.assignment.intValue(it) }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val before = score(state, state.assignment::intValue)
        if (before == 0) return
        fun propose(variable: Int, value: Long) {
            if (value in state.rootDomains[variable] && value != state.assignment.intValue(variable) &&
                score(state) { if (it == variable) value else state.assignment.intValue(it) } < before
            ) sink.addChannelingIntSet(state, variable, value)
        }
        fun propose(coordinate: GridCoordinate, value: Double) {
            coordinate.index(value)?.let { propose(coordinate.variable, it) }
        }
        relation.image(state.assignment::intValue)?.let { propose(relation.output.variable, it) }
        val output = relation.output.value(state.assignment.intValue(relation.output.variable))
        when (relation) {
            is GridProduct -> {
                val a = relation.a.value(state.assignment.intValue(relation.a.variable))
                val b = relation.b.value(state.assignment.intValue(relation.b.variable))
                if (relation.a.variable == relation.b.variable && relation.a == relation.b && output >= 0.0) {
                    propose(relation.a, sqrt(output))
                    propose(relation.a, -sqrt(output))
                } else {
                    if (b != 0.0) propose(relation.a, output / b)
                    if (a != 0.0) propose(relation.b, output / a)
                }
            }
            is GridPower -> if (relation.exponent > 0 && (output >= 0.0 || relation.exponent and 1 != 0)) {
                val magnitude = abs(output).pow(1.0 / relation.exponent)
                propose(relation.base, if (output < 0.0) -magnitude else magnitude)
                if (output > 0.0 && relation.exponent and 1 == 0) propose(relation.base, -magnitude)
            }
        }
        for (coordinate in relation.inputs) {
            propose(coordinate, coordinate.lower)
            propose(coordinate, coordinate.upper)
        }
        for (variable in relation.inputs.map { it.variable }.distinct()) {
            val span = state.rootDomains[variable].spanOrNull(REPAIR_LIMIT) ?: continue
            for (index in 0 until span.size) propose(variable, span.valueAt(index))
        }
    }

    private companion object {
        const val REPAIR_LIMIT = 64L
    }
}
