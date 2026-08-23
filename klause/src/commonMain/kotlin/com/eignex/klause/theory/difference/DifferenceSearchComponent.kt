package com.eignex.klause.theory.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.arithmetic.difference.differenceFragmentOf
import com.eignex.klause.arithmetic.difference.potentialSample
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.TheoryComponent

/** Incremental difference-logic participant over the shared Boolean and integer-bound trail. */
class DifferenceSearchComponent(
    private val model: ProblemSpec,
    private val modelIntVars: IntArray = IntArray(model.numIntVars) { it },
) : TheoryComponent {
    private val base = differenceFragmentOf(model.factors, model.numIntVars, model.intBounds)
    private var assignment: Sample? = null

    override fun initialize(context: SearchContext): ComponentResult = propagate(context)

    override fun propagate(context: SearchContext): ComponentResult {
        val fragment = fragment(context) ?: return ComponentResult.Consistent
        val active = BooleanArray(fragment.edges.size) { edge ->
            val guard = fragment.edges[edge].guard
            guard == DifferenceEdge.ALWAYS || context.boolValue(Lit.variable(guard)) == Lit.isPositive(guard)
        }
        return if (fragment.graph().potentials(
                active,
            ) == null
        ) {
            ComponentResult.Conflict()
        } else {
            ComponentResult.Consistent
        }
    }

    override fun check(context: SearchContext): ComponentCheck {
        if (!context.consumeCheck() || context.cancelled()) return ComponentCheck.Indeterminate
        val bools = BooleanArray(model.numBoolVars) { variable ->
            context.boolValue(variable) ?: return ComponentCheck.Indeterminate
        }
        val fragment = fragment(context)
        val values = if (fragment == null) {
            unconstrainedValues(context)
        } else {
            fragment.potentialSample(model.numIntVars, bools) ?: return ComponentCheck.Infeasible()
        }
        assignment = Sample(bools, values)
        return ComponentCheck.Feasible
    }

    override fun retract(decisionLevel: Int) {
        assignment = null
    }

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        assignment?.let { assignment ->
            for (variable in modelIntVars) model.put(SearchIntValue(variable), assignment.ints[variable])
            model.put(this, assignment)
        }
    }

    private fun fragment(context: SearchContext): DifferenceFragment? {
        val original = base ?: return null
        val edges = ArrayList<DifferenceEdge>(original.edges.size + model.numIntVars * 2)
        edges.addAll(original.edges)
        for (variable in 0 until model.numIntVars) {
            context.intUpperBound(variable)?.let { edges += DifferenceEdge(DifferenceFragment.ZERO, variable, it) }
            context.intLowerBound(variable)?.let { edges += DifferenceEdge(variable, DifferenceFragment.ZERO, -it) }
        }
        return DifferenceFragment(edges)
    }

    private fun unconstrainedValues(context: SearchContext): LongArray = LongArray(model.numIntVars) { variable ->
        context.intLowerBound(variable) ?: when {
            model.intBounds.hasLower(variable) -> model.intBounds.lower(variable)
            context.intUpperBound(variable) != null -> context.intUpperBound(variable)!!
            model.intBounds.hasUpper(variable) -> model.intBounds.upper(variable)
            else -> 0L
        }
    }
}
