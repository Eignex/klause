package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.binaryIntegers
import com.eignex.klause.portfolio.continuousMajority
import com.eignex.klause.portfolio.finiteWitnessVerifier
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.Cancellation

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
): PortfolioExecutor = portfolioExecutor(
    problem, scenario, objective, lsObjective, definitionalSweep, onEvent, onFault,
    problem.prepareExecutionPlan(objective = objective, preferFinite = true), Cancellation.Never,
)

internal fun FinitePipeline.portfolioExecutor(
    problem: BakedProblem,
    scenario: PortfolioScenario,
    objective: LinearObjective?,
    lsObjective: IncrementalObjective?,
    definitionalSweep: DefinitionalSweep?,
    onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
    onFault: ((ArmFault) -> Unit)?,
    preparedPlan: PreparedSourcePlan,
    cancellation: Cancellation,
): PortfolioExecutor {
    val profile = ProblemProfile.of(problem, scenario.kind == Kind.COP)
    val identity = ModelIdentity.of(problem, objective)
    val binding = EngineInvocation(preparedPlan, identity, objective)
    val factories = PortfolioBuilder.factories(problem, scenario, objective, lsObjective, definitionalSweep, onEvent,
        profile).map { factory ->
        PortfolioArmFactory(preparedPlan, factory.armId, factory.family, bind = { worker ->
            worker.bindSharingInvocation(binding).bindInvocation(EngineInvocation(preparedPlan, identity, objective,
                executionAssumptions = worker.executionAssumptions ?: Assumptions.None))
        }) { slot -> factory.materialize(slot.index, slot.baseSeed, slot.nodeBudget) }
    }
    return PortfolioInvocation(
        preparedPlan, factories,
        PortfolioInvocationControls(
            parallelism = scenario.cores, workerSeed = scenario.seed, cancellation = cancellation,
            nodeBudget = scenario.nodeBudget,
            baseSliceWork = scenario.sliceWork, maxSliceWork = scenario.maxSliceWork,
            sliceGrowth = scenario.sliceGrowth, phaseRetention = scenario.phaseRetention,
            reseedStaleThreshold = scenario.reseedStaleThreshold,
        ),
        PortfolioEvidence(identity,
            finiteWitnessVerifier(problem, objective, toleranceCheck = scenario.toleranceCheck))
            .bindInvocation(binding, cancellation),
        objective, onFault,
    ) { workers ->
        val proofShares = clausalProofShares(scenario, profile, workers.map { it.label })
        val shares = if (proofShares.isEmpty()) {
            continuousLpShares(scenario, profile, workers.map { it.label }, objective,
                problem.continuousMajority, problem.binaryIntegers)
        } else proofShares
        val probes = if (scenario.cores == 1 && scenario.engine == EngineMix.MIXED && scenario.kind == Kind.COP &&
            scenario.btPool == null && scenario.lsPool == null
        ) workers.indices.filter { arm -> arm >= scenario.arms && workers[arm].label == "bt/lp-default" }.toSet()
        else emptySet()
        PortfolioInvocationPolicy(profile, shares, reserveBeforeIncumbentOnly = true, probes = probes)
    }
}

/** Creates the fixed finite-domain solver over [problem]. */
fun FinitePipeline.backtrackSolver(problem: BakedProblem): BacktrackSolver = BacktrackSolver(problem)

internal fun clausalProofShares(
    scenario: PortfolioScenario,
    profile: ProblemProfile,
    labels: List<String>,
): DoubleArray {
    if (scenario.cores != 1 || scenario.kind != Kind.CSP || scenario.engine != EngineMix.MIXED ||
        profile.problemClass != ProblemClass.Sat || scenario.btPool != null || scenario.lsPool != null ||
        scenario.btEdit != null || scenario.annotationArm != null
    ) return DoubleArray(0)
    val proof = labels.indexOf("bt/clausal-balanced")
    if (proof < 0) return DoubleArray(0)
    // A refutation supplies no incumbent progress, so family rewards cannot identify its approaching proof.
    return DoubleArray(labels.size).also { it[proof] = 0.98 }
}

internal fun continuousLpShares(
    scenario: PortfolioScenario,
    profile: ProblemProfile,
    labels: List<String>,
    objective: LinearObjective?,
    continuousMajority: Boolean = false,
    binaryIntegers: Boolean = false,
): DoubleArray {
    if (continuousMajority && !binaryIntegers) return DoubleArray(0)
    if (objective?.realCoefficientValues?.any { it != 0.0 } != true) return DoubleArray(0)
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
