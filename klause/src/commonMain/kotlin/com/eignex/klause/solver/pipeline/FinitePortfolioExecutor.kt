package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.finiteWitnessVerifier
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent

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
        reseedStaleThreshold = scenario.reseedStaleThreshold,
        profile = profile,
        minShares = continuousLpShares(scenario, profile, workers.map { it.label }, objective),
        onFault = onFault,
    ).also {
        it.reserveBeforeIncumbentOnly = true
        if (scenario.cores == 1 && scenario.engine == EngineMix.MIXED && scenario.kind == Kind.COP &&
            scenario.btPool == null && scenario.lsPool == null
        ) {
            it.incumbentProbeArms = workers.indices.filter { arm ->
                arm >= scenario.arms && workers[arm].label == "bt/lp-default"
            }.toSet()
        }
        it.evidenceVerification = PortfolioEvidence(
            ModelIdentity.of(problem, objective),
            finiteWitnessVerifier(problem, objective, toleranceCheck = scenario.toleranceCheck),
        )
    }
}

/** Creates the fixed finite-domain solver over [problem]. */
fun FinitePipeline.backtrackSolver(problem: BakedProblem): BacktrackSolver = BacktrackSolver(problem)

internal fun continuousLpShares(
    scenario: PortfolioScenario,
    profile: ProblemProfile,
    labels: List<String>,
    objective: LinearObjective?,
): DoubleArray {
    if (objective?.realCoefficients?.any { it != 0.0 } != true) return DoubleArray(0)
    if (!LpConfig.AUTO.cappedUnder(scenario.lpCeiling).resolved(LpTechnique.BOUNDING)) return DoubleArray(0)
    if (scenario.cores != 1 || scenario.kind != Kind.COP || scenario.engine != EngineMix.MIXED ||
        !profile.realColumns || scenario.btPool != null || scenario.lsPool != null
    ) return DoubleArray(0)
    val lp = labels.indexOf("bt/lp-default")
    if (lp < 0) return DoubleArray(0)
    // LP is the complete arm that optimizes continuous objective columns. Its expensive first solves can
    // outweigh several cheap slices before it reaches a witness, so reserve time for that descent.
    return DoubleArray(labels.size).also { it[lp] = 0.5 }
}
