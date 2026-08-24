package com.eignex.klause.theory

import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.TheoryComponent

/**
 * Adapts an existing complete open-model [Theory] to the shared component lifecycle.
 *
 * The underlying arithmetic procedures retain their specialised exact search, while this adapter owns
 * their shared-trail boundary: Boolean assignments are trailed, semantic bound changes invalidate the
 * cached theory result, and a complete Boolean skeleton is checked during component propagation rather
 * than by a separate frontend loop. Directly incremental theories can replace this adapter without
 * changing the session contract.
 */
class TheorySearchComponent<A>(
    private val theory: Theory<A>,
    private val modelContribution: ((A, SearchModel) -> Unit)? = null,
) : TheoryComponent {
    private val bools = IntArray(theory.model.numBoolVars) { UNASSIGNED }
    private val boolLevels = IntArray(theory.model.numBoolVars) { -1 }
    private var assignment: A? = null
    private var outcome: ComponentCheck? = null

    override fun initialize(context: SearchContext): ComponentResult = propagate(context)

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        when (decision) {
            is SearchDecision.Bool -> {
                val variable = decision.literal ushr 1
                bools[variable] = if (decision.literal and 1 == 0) TRUE else FALSE
                boolLevels[variable] = context.decisionLevel
            }

            is SearchDecision.IntAtMost, is SearchDecision.IntAtLeast, is SearchDecision.IntEqual,
            is SearchDecision.Theory,
            -> Unit
        }
        outcome = null
        assignment = null
        return ComponentResult.Consistent
    }

    override fun propagate(context: SearchContext): ComponentResult {
        if (bools.any { it == UNASSIGNED }) return ComponentResult.Consistent
        return when (val result = evaluate(context)) {
            ComponentCheck.Feasible -> ComponentResult.Consistent
            is ComponentCheck.Infeasible -> ComponentResult.Conflict(result.explanation)
            ComponentCheck.Indeterminate -> ComponentResult.Indeterminate
        }
    }

    override fun check(context: SearchContext): ComponentCheck = outcome ?: evaluate(context)

    private fun evaluate(context: SearchContext): ComponentCheck {
        outcome?.let { return it }
        if (context.cancelled()) return ComponentCheck.Indeterminate
        val values = BooleanArray(theory.model.numBoolVars) { variable ->
            when (bools[variable]) {
                TRUE -> true
                FALSE -> false
                else -> return ComponentCheck.Indeterminate
            }
        }
        val result = theory.check(
            values,
            object : TheoryContext {
                override fun consumeCheck(): Boolean = context.consumeCheck()

                override fun cancelled(): Boolean = context.cancelled()

                override fun intLowerBound(variable: Int): Long? = context.intLowerBound(variable)

                override fun intUpperBound(variable: Int): Long? = context.intUpperBound(variable)
            },
        )
        return when (result) {
            is TheoryCheck.Sat -> {
                assignment = result.assignment
                ComponentCheck.Feasible.also { outcome = it }
            }

            is TheoryCheck.Infeasible -> ComponentCheck.Infeasible(result.explanation).also { outcome = it }

            TheoryCheck.Cancelled -> ComponentCheck.Indeterminate
        }
    }

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        assignment?.let {
            model.put(this, it)
            modelContribution?.invoke(it, model)
        }
    }

    override fun retract(decisionLevel: Int) {
        for (variable in bools.indices) {
            if (boolLevels[variable] > decisionLevel) {
                bools[variable] = UNASSIGNED
                boolLevels[variable] = -1
            }
        }
        assignment = null
        outcome = null
    }

    private companion object {
        const val UNASSIGNED = -1
        const val FALSE = 0
        const val TRUE = 1
    }
}
