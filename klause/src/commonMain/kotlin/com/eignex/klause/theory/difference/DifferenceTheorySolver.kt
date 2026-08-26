package com.eignex.klause.theory.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.Potentials
import com.eignex.klause.arithmetic.difference.potentialSample
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.componentPlan
import com.eignex.klause.solver.differenceFragmentOf
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.theory.Theory
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext

/** Complete open integer difference-logic solver. */
class DifferenceTheorySolver(override val model: ProblemSpec) : Theory<Sample> {
    private val fragment = differenceFragmentOf(model.factors, model.numIntVars, model.intBounds)

    init {
        require(model.componentPlan().theoryPipeline == ProblemPipeline.DIFFERENCE_THEORY) {
            "difference-theory search requires complete difference coverage over an open integer model"
        }
    }

    override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<Sample> {
        if (!context.consumeCheck()) return TheoryCheck.Cancelled
        val values = if (fragment == null) {
            unconstrainedValues()
        } else {
            when (val outcome = fragment.potentialSample(model.numIntVars, bools, context::cancelled)) {
                is Potentials.Found -> outcome.values

                // A refutation is claimed only when the sweeps settled on one; a spent budget reports the
                // budget, never infeasibility.
                Potentials.Infeasible -> return TheoryCheck.Infeasible(negativeCycleExplanation(bools, context))

                Potentials.Abandoned -> return TheoryCheck.Cancelled
            }
        }
        return TheoryCheck.Sat(Sample(bools.copyOf(), values))
    }

    private fun negativeCycleExplanation(bools: BooleanArray, context: TheoryContext): SearchExplanation? {
        val fragment = fragment ?: return null
        val active = BooleanArray(fragment.edges.size) { edge ->
            val guard = fragment.edges[edge].guard
            guard == DifferenceEdge.ALWAYS || bools[Lit.variable(guard)] == Lit.isPositive(guard)
        }
        val cycle = fragment.graph().negativeCycle(active, context::cancelled) ?: return null
        val guards = cycle.map { fragment.edges[it].guard }
        if (guards.any { it == DifferenceEdge.ALWAYS }) return null
        return SearchExplanation(guards.distinct().map(Lit::negate).toIntArray())
    }

    private fun unconstrainedValues(): LongArray = LongArray(model.numIntVars) { variable ->
        when {
            model.intBounds.hasLower(variable) -> model.intBounds.lower(variable)
            model.intBounds.hasUpper(variable) -> model.intBounds.upper(variable)
            else -> 0L
        }
    }
}
