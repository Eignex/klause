package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.composedFixpoint
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.WitnessCheck
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.Cancellation
import kotlin.math.abs

/** Materializes [scenario] over [problem] and schedules it on one lane per core, checking every result an arm
 *  claims against [problem] and reporting each arm quarantined for a refuted claim to [onFault]. */
fun FinitePipeline.portfolioExecutor(
    problem: BakedProblem,
    scenario: PortfolioScenario,
    objective: LinearObjective?,
    lsObjective: IncrementalObjective?,
    definitionalSweep: DefinitionalSweep?,
    onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
    onFault: ((ArmFault) -> Unit)? = null,
): PortfolioExecutor {
    val profile = ProblemProfile.of(problem, scenario.kind == Kind.COP)
    val workers = PortfolioBuilder.build(
        problem,
        scenario,
        objective = objective,
        lsObjective = lsObjective,
        definitionalSweep = definitionalSweep,
        onEvent = onEvent,
        profile = profile,
    )
    return Portfolio.thompson(
        workers,
        lanes = scenario.cores,
        baseSliceWork = scenario.sliceWork,
        phaseRetention = scenario.phaseRetention,
        profile = profile,
        witnessCheck = witnessCheck(problem, objective),
        onFault = onFault,
    )
}

/**
 * Re-derive an arm's claimed model from the whole factor set, and its claimed objective from [objective], before the
 * portfolio accepts it. The check decides the Boolean and integer columns: a real column's value is the arm's own
 * certified LP point, admitted under the run's tolerance by the arm, so it is left to that admission. A fixpoint
 * propagation cannot finish refutes nothing. The claimed objective is compared within `OBJECTIVE_TOLERANCE`
 * relative: an arm reports a rounded value on a model whose objective has real coefficients, and that rounding is
 * not a fault.
 */
private fun witnessCheck(problem: BakedProblem, objective: LinearObjective?): WitnessCheck =
    WitnessCheck { sample, claimed ->
        val discrete = if (sample.bools.size != problem.numBoolVars || sample.ints.size != problem.numIntVars) {
            "assignment covers ${sample.bools.size}/${sample.ints.size} of the " +
                "${problem.numBoolVars}/${problem.numIntVars} discrete variables"
        } else {
            (composedFixpoint(problem, Candidate(sample, Unit), Cancellation.Never) as? Verification.Rejected)?.reason
        }
        val evaluated = if (discrete == null && claimed != null) objective?.evaluate(sample) else null
        when {
            discrete != null -> discrete

            claimed != null && evaluated != null &&
                abs(claimed - evaluated) > OBJECTIVE_TOLERANCE * maxOf(1.0, abs(evaluated)) ->
                "objective is $evaluated, not the claimed $claimed"

            else -> null
        }
    }

// Relative slack between an arm's claimed objective and the one re-evaluated from its assignment.
private const val OBJECTIVE_TOLERANCE = 1e-6

/** Creates the fixed finite-domain solver over [problem]. */
fun FinitePipeline.backtrackSolver(problem: BakedProblem): BacktrackSolver = BacktrackSolver(problem)
