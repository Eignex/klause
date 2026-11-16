package com.eignex.klause.cli

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.qfnra.ExactNraAssignment

internal fun interruptedSolvable(reason: TerminationReason, admissionElapsedMs: Long): Solvable =
    sourceSolvable(SourceRendering.Interrupted(reason), admissionElapsedMs)

internal fun polynomialAssignment(sample: Sample): OpenTheoryAssignment = OpenTheoryAssignment.Rebuilt(
    OpenTheoryAssignment.ExactNra(ExactNraAssignment(sample.bools, requireNotNull(sample.algebraicReals))),
    sample.bools, Array(sample.numIntVars) { sample.exactIntValue(it) },
)

private fun sourceSolvable(rendering: SourceRendering, admissionElapsedMs: Long): Solvable = Solvable(
    finite = null,
    render = { error("an open source witness is rendered without narrowing to Sample") },
    objectiveValue = null,
    rendering = rendering,
    routingElapsedMs = admissionElapsedMs,
)
