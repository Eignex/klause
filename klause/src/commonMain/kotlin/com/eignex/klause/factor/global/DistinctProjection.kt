package com.eignex.klause.factor.global

import com.eignex.klause.factor.bool.internals.reifiedDegree
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.bigIntOf

internal fun Distinct.propagationView(): Propagator {
    if (variables.reals.isNotEmpty()) return NoPropagator
    affinePropagationView()?.let { return it }
    val inputBooleans = operands.flatMap { it.references.toList() }.filter(Term::isBool)
        .map { Lit.variable(Term.lit(it)) }.distinct()
    return object : Propagator {
        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            if (variables.ints.any { state.intDomains[it].min != state.intDomains[it].max } ||
                inputBooleans.any { state.boolValues[it] == null }
            ) {
                return true
            }
            val holds = collisions(
                { bigIntOf(state.intDomains[it].min) },
                { requireNotNull(state.boolValues[it]) },
                { error("finite distinct operand has a real coordinate") },
            ) == 0
            return if (activator == LinearRow.ALWAYS) holds else state.pinLit(Lit.make(activator, holds))
        }
    }
}

internal fun Distinct.scoringView(): Invariant = DistinctInvariant(this)

private class DistinctInvariant(private val factor: Distinct) : Invariant {
    private fun score(
        state: LocalSearchState,
        changedInteger: Int = -1,
        integer: Long = 0,
        changedReal: Int = -1,
        real: Double = 0.0,
        flippedBoolean: Int = -1,
    ): Int {
        fun realValue(id: Int): Double = if (id == changedReal) real else state.assignment.realValue(id)
        if (factor.variables.reals.any { !realValue(it).isFinite() }) return state.violationSoftCap
        fun boolean(id: Int): Boolean = state.assignment.boolValue(id) xor (id == flippedBoolean)
        val collisions = factor.collisions(
            { bigIntOf(if (it == changedInteger) integer else state.assignment.intValue(it)) },
            ::boolean,
            { requireNotNull(BigFraction.ofDouble(realValue(it))) },
        )
        val holds = collisions == 0
        val wanted = factor.activator == LinearRow.ALWAYS || boolean(factor.activator)
        return reifiedDegree(wanted, holds) { compressViolation(collisions.toLong(), state.violationSoftCap) }
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = score(state) != 0
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = score(state)
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        score(state, changedInteger = intVar, integer = newValue) - score(state)
    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int =
        score(state) - score(state, changedInteger = intVar, integer = oldValue)
    override fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int =
        score(state, changedReal = realVar, real = newValue) - score(state)
    override fun applyRealSet(state: LocalSearchState, factorId: Int, realVar: Int, oldValue: Double): Int =
        score(state) - score(state, changedReal = realVar, real = oldValue)
    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        score(state, flippedBoolean = boolVar) - score(state)
    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        score(state) - score(state, flippedBoolean = boolVar)
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (factor.activator != LinearRow.ALWAYS && score(state) != 0) sink.addBoolFlip(factor.activator)
    }
}
