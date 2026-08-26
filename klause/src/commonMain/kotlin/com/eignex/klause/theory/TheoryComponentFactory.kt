package com.eignex.klause.theory

import com.eignex.klause.solver.pipeline.ComponentPlan
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.theory.difference.DifferenceSearchComponent
import com.eignex.klause.theory.lia.GeneralLiaSearchComponent
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.theory.qflra.ExactLraSolver

/** Builds the theory component selected by this plan. */
fun ComponentPlan.theoryComponent(spec: ProblemSpec): TheoryComponent? {
    val fragment = theoryFragment(spec)
    return when (theoryPipeline) {
        ProblemPipeline.DIFFERENCE_THEORY -> DifferenceSearchComponent(fragment, theoryIntVars)

        ProblemPipeline.GENERAL_LIA -> GeneralLiaSearchComponent(fragment, theoryIntVars) { assignment, model ->
            assignment.ints.forEachIndexed { variable, value ->
                if (intOwner(variable) == com.eignex.klause.solver.pipeline.IntVariableOwner.THEORY) {
                    model.put(SearchIntValue(variable), value)
                }
            }
        }

        ProblemPipeline.EXACT_LRA -> TheorySearchComponent(ExactLraSolver(fragment)) { assignment, model ->
            assignment.reals.forEachIndexed { variable, value -> model.put(SearchRealValue(variable), value) }
        }

        ProblemPipeline.EXACT_LIRA -> ExactLiraSearchComponent(fragment) { assignment, model ->
            assignment.ints.forEachIndexed { variable, value ->
                if (intOwner(variable) == com.eignex.klause.solver.pipeline.IntVariableOwner.THEORY) {
                    model.put(SearchIntValue(variable), value)
                }
            }
            assignment.reals.forEachIndexed { variable, value -> model.put(SearchRealValue(variable), value) }
        }

        ProblemPipeline.FINITE_CP -> null

        ProblemPipeline.UNSUPPORTED_OPEN -> error("no complete theory component covers the selected fragment")
    }
}
