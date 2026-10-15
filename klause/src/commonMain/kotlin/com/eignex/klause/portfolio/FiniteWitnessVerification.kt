package com.eignex.klause.portfolio

import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.solver.validation.finiteWitnessVerifier as validateFiniteWitness

internal fun finiteWitnessVerifier(
    problem: BakedProblem,
    objective: LinearObjective?,
    cancellation: Cancellation = Cancellation.Never,
    toleranceCheck: ((Sample) -> Boolean)? = null,
): CandidateVerifier<Sample, Double?> = validateFiniteWitness(problem, objective, cancellation, toleranceCheck)
