package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.theory.difference.DifferenceSearchComponent
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent

/** Builds the theory component selected by this plan. */
internal fun ComponentPlan.theoryComponent(spec: Problem, smtStats: SmtStatsSink? = null): TheoryComponent? {
    val fragment = theoryFragment(spec)
    return when (theoryPipeline) {
        ProblemPipeline.DIFFERENCE_THEORY -> DifferenceSearchComponent.withRootBounds(
            fragment,
            theoryIntVars,
            cpIntVars,
        )

        ProblemPipeline.EXACT_LRA -> ExactLiraSearchComponent(fragment) { assignment, model ->
            assignment.reals.forEachIndexed { variable, value -> model.put(SearchRealValue(variable), value) }
        }.also { component -> smtStats?.let(component::observeWith) }

        ProblemPipeline.EXACT_LIRA -> ExactLiraSearchComponent(fragment) { assignment, model ->
            assignment.ints.forEachIndexed { variable, value ->
                if (intOwner(variable) == IntVariableOwner.THEORY) {
                    model.put(SearchIntValue(variable), value)
                }
            }
            assignment.reals.forEachIndexed { variable, value -> model.put(SearchRealValue(variable), value) }
        }.also { component -> smtStats?.let(component::observeWith) }

        ProblemPipeline.FINITE_CP -> null

        ProblemPipeline.UNSUPPORTED_OPEN -> error("no complete theory component covers the selected fragment")
    }
}
