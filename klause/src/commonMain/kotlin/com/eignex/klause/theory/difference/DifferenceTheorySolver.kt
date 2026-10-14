package com.eignex.klause.theory.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.arithmetic.difference.Potentials
import com.eignex.klause.arithmetic.difference.potentialSample
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.selectedFactorProjections
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.linearTheoryModel
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.differenceFragmentOf
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.supportsCompleteDifferenceTheory
import com.eignex.klause.theory.Theory
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.util.Cancellation

/** Complete open integer difference-logic solver. */
class DifferenceTheorySolver(
    override val model: Problem,
    projections: PreparedFactorProjections,
) : Theory<Sample> {
    /** Open the built-in or source-retained selection with the original service constructor. */
    constructor(model: Problem) : this(model, model.selectedFactorProjections())

    private val encoded = requireNotNull(projections.linearTheoryModel(EncodingTarget.DIFFERENCE)) {
        "unsupported selected difference encoding"
    }.also { projections.requireSource(model) }
    private val fragment = differenceFragmentOf(encoded.factors, model.numIntVars, model.intBounds)

    init {
        require(supportsCompleteDifferenceTheory(encoded.factors, model.numIntVars, model.intBounds)) {
            "difference-theory search requires complete difference coverage over an open integer model"
        }
    }

    override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<Sample> {
        if (!context.consumeCheck()) return TheoryCheck.Cancelled
        when (encoded.checkDifferenceBooleans(bools, Cancellation { context.cancelled() })) {
            false -> return TheoryCheck.Infeasible()
            null -> return TheoryCheck.Cancelled
            true -> Unit
        }
        val active = activeFragment(context)
        if (context.cancelled()) return TheoryCheck.Cancelled
        val values = if (active == null) {
            unconstrainedValues()
        } else {
            when (val outcome = active.potentialSample(model.numIntVars, bools, context::cancelled)) {
                is Potentials.Found -> outcome.values

                // A refutation is claimed only when the sweeps settled on one; a spent budget reports the
                // budget, never infeasibility.
                Potentials.Infeasible -> return TheoryCheck.Infeasible(negativeCycleExplanation(active, bools, context))

                Potentials.Abandoned -> return TheoryCheck.Cancelled
            }
        }
        if (context.cancelled() || values.indices.any { variable ->
                model.intDomainOrNull(variable)?.let { values[variable] !in it } == true ||
                    context.intLowerBound(variable)?.let { values[variable] < it } == true ||
                    context.intUpperBound(variable)?.let { values[variable] > it } == true
            }) return TheoryCheck.Cancelled
        return TheoryCheck.Sat(Sample(bools.copyOf(), values))
    }

    private fun negativeCycleExplanation(
        fragment: DifferenceFragment,
        bools: BooleanArray,
        context: TheoryContext,
    ): SearchExplanation? {
        val active = BooleanArray(fragment.edges.size) { edge ->
            val guard = fragment.edges[edge].guard
            guard == DifferenceEdge.ALWAYS || bools[Lit.variable(guard)] == Lit.isPositive(guard)
        }
        val cycle = fragment.graph().negativeCycle(active, context::cancelled) ?: return null
        val guards = cycle.map { fragment.edges[it].guard }
        if (guards.any { it == DifferenceEdge.ALWAYS }) return null
        return SearchExplanation(guards.distinct().map(Lit::negate).toIntArray())
    }

    private fun activeFragment(context: TheoryContext): DifferenceFragment? {
        val edges = ArrayList<DifferenceEdge>()
        fragment?.let { edges.addAll(it.edges) }
        for (variable in 0 until model.numIntVars) {
            if (context.cancelled()) return null
            if (model.intBounds.hasUpper(variable)) {
                edges += DifferenceEdge(DifferenceFragment.ZERO, variable, model.intBounds.upper(variable))
            }
            if (model.intBounds.hasLower(variable) && model.intBounds.lower(variable) != Long.MIN_VALUE) {
                edges += DifferenceEdge(variable, DifferenceFragment.ZERO, -model.intBounds.lower(variable))
            }
            context.intUpperBound(variable)?.let { edges += DifferenceEdge(DifferenceFragment.ZERO, variable, it) }
            context.intLowerBound(variable)?.takeUnless { it == Long.MIN_VALUE }?.let {
                edges += DifferenceEdge(variable, DifferenceFragment.ZERO, -it)
            }
        }
        return if (edges.isEmpty()) null else DifferenceFragment(edges)
    }

    private fun unconstrainedValues(): LongArray = LongArray(model.numIntVars) { variable ->
        when {
            model.intBounds.hasLower(variable) -> model.intBounds.lower(variable)
            model.intBounds.hasUpper(variable) -> model.intBounds.upper(variable)
            else -> 0L
        }
    }
}
