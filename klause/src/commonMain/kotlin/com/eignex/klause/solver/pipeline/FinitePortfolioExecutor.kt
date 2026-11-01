package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioDiagnostics
import com.eignex.klause.portfolio.PortfolioEvent
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.SourceWorkerBinding
import com.eignex.klause.portfolio.WorkerProofAuthority
import com.eignex.klause.portfolio.binaryIntegers
import com.eignex.klause.portfolio.continuousMajority
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.validation.finiteWitnessVerifier
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
): PortfolioExecutor = portfolioExecutor(
    problem, scenario, objective, lsObjective, definitionalSweep,
    onEvent, onFault, preparedPlan, cancellation, false,
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
    numericalCompletion: Boolean,
    sourceHints: ((Set<ExecutionStrategy>) -> PreparedSourceHints?)? = null,
    numericalCompanions: TheoryParams? = null,
    objectiveCoordinates: ObjectiveCoordinateView? = null,
    incumbentCompletionCheck: ((Sample, BigFraction, Cancellation) -> Boolean)? = null,
    improvementProofShare: Double = 0.5,
    deferLpProbes: Boolean = false,
    includeRestartingProof: Boolean = false,
    probePolicy: InitialProbePolicy = InitialProbePolicy.ORDINARY,
    booleanProofTasks: Boolean? = false,
    fundedCompanions: Boolean = false,
    waitForCompanionImprovement: Boolean = false,
): PortfolioInvocation {
    require(scenario.diagnosticWorkLimit == null || problem.numRealVars == 0) {
        "portfolio-work requires a finite Boolean/integer source without numerical companions"
    }
    val profile = ProblemProfile.of(problem, scenario.kind == Kind.COP)
    val curatedFiniteInteger = profile.problemClass == ProblemClass.FiniteCp && problem.numBoolVars == 0 &&
        scenario.cores == 1 && scenario.kind == Kind.COP && scenario.engine == EngineMix.MIXED &&
        !scenario.explicitArms && scenario.arms == PortfolioScenario.DEFAULT_ARMS &&
        scenario.btPool == null && scenario.lsPool == null && scenario.btEdit == null && scenario.annotationArm == null
    if (deferLpProbes && !curatedFiniteInteger) {
        pipelineConfigError("portfolio-lp-start requires a curated single-core mixed finite integer optimization")
    }
    if (probePolicy != InitialProbePolicy.ORDINARY && (!curatedFiniteInteger || scenario.diagnosticWorkLimit != null ||
            cancellation.deadline() == null)) {
        pipelineConfigError("portfolio-probes requires curated elapsed single-core mixed finite integer optimization")
    }
    val identity = ModelIdentity.of(problem, objective)
    val binding = EngineInvocation(preparedPlan, identity, objective)
    val companionEligibility = numericalCompanions?.let {
        finiteNumericalCompanionEligibility(problem, scenario, objective, it)
    }
    if (waitForCompanionImprovement && !fundedCompanions) {
        pipelineConfigError("progress-funded companion admission requires funded construction")
    }
    if (fundedCompanions && (!scenario.companionAfterIncumbent || cancellation.deadline() == null)) {
        pipelineConfigError("funded companion admission requires companion-after-incumbent and a solve deadline")
    }
    val companionAdmission = if (fundedCompanions && companionEligibility?.admitted == true) {
        FiniteNumericalCompanions.admission(problem, requireNotNull(objective),
            cancellation or requireNotNull(numericalCompanions).cancellation or numericalCompanions.timeout,
            companionEligibility.requirements.construction)
    } else null
    val companionPreparation = if (!fundedCompanions && companionEligibility?.admitted == true) {
        FiniteNumericalCompanions.prepare(problem, requireNotNull(objective), requireNotNull(numericalCompanions),
            cancellation, companionEligibility.requirements.construction, scenario.companionAfterIncumbent,
            incumbentCompletionCheck)
    } else null
    val companions = if (companionAdmission?.initiallyFunded == true) {
        FiniteNumericalCompanions.deferred(problem, requireNotNull(objective), requireNotNull(numericalCompanions),
            cancellation, requireNotNull(companionEligibility).requirements.construction, companionAdmission,
            incumbentCompletionCheck, afterImprovement = waitForCompanionImprovement)
    } else companionPreparation?.companions
    companionEligibility?.let { eligibility ->
        val declines = if (companionAdmission?.initiallyFunded == false) listOf("companion-lifetime-unfunded")
        else if (eligibility.admitted && companions == null) listOf("complete-affine-capture-unavailable")
        else eligibility.declines
        val frontier = eligibility.requirements
        val repair = frontier.copy(authority = WorkerProofAuthority.WITNESS_ONLY,
            construction = frontier.construction.copy(sliceMillis = 250))
        for ((label, requirements) in listOf("numerical/finite-companion" to frontier,
            "ls/finite-joint-companion" to repair)) {
            onEvent?.invoke("portfolio", SearchEvent.WorkerAdmission(label, declines.isEmpty(),
                requirements.toString(), declines, selected = declines.isEmpty()))
        }
    }
    val finiteFactories = PortfolioBuilder.factories(
        problem, scenario, objective, lsObjective, definitionalSweep, onEvent,
        profile, numericalCompletion, sourceHints, objectiveCoordinates,
    ).map { factory ->
        PortfolioArmFactory(preparedPlan, factory.armId, factory.family, binding = { worker ->
            SourceWorkerBinding(
                EngineInvocation(
                    preparedPlan,
                    identity,
                    objective,
                    executionAssumptions = worker.executionAssumptions ?: Assumptions.None,
                ),
                sharingInvocation = binding,
                provesResults = worker.permitsSourceProofs,
            )
        }) { slot ->
            // The leading numerical probe does not renumber the finite workers' seed positions.
            factory.materialize(slot.index - if (companions == null) 0 else 1, slot.baseSeed, slot.nodeBudget)
        }
    }
    val additional = companions?.factories(preparedPlan, finiteFactories.size).orEmpty()
    val factories = additional.take(1) + finiteFactories + additional.drop(1)
    return PortfolioInvocation(
        preparedPlan,
        factories,
        PortfolioInvocationControls(
            parallelism = scenario.cores, workerSeed = scenario.seed, cancellation = cancellation,
            nodeBudget = scenario.nodeBudget,
            baseSliceWork = scenario.sliceWork, maxSliceWork = scenario.maxSliceWork,
            sliceGrowth = scenario.sliceGrowth, phaseRetention = scenario.phaseRetention,
            reseedStaleThreshold = scenario.reseedStaleThreshold,
            rewardPolicy = scenario.rewardPolicy,
            improvementProofShare = improvementProofShare,
            includeRestartingProof = includeRestartingProof,
            booleanProofTasks = booleanProofTasks,
            onProgress = if (scenario.reportPortfolioProgress) { event ->
                onEvent?.invoke("portfolio", SearchEvent.PortfolioDiagnostic(event))
            } else null,
            diagnostics = scenario.diagnosticWorkLimit?.let { limit ->
                PortfolioDiagnostics(limit, scenario.seed) { event ->
                    onEvent?.invoke("portfolio", SearchEvent.PortfolioDiagnostic(event))
                }
            },
        ),
        PortfolioEvidence(
            identity,
            finiteWitnessVerifier(problem, objective, toleranceCheck = scenario.toleranceCheck),
        )
            .bindInvocation(binding, cancellation),
        objective,
        onFault,
        optimizationIncumbents = companions?.incumbents,
        preparationStages = companionPreparation?.let {
            mapOf("finiteCompanionPreparationAttempts" to 1L,
                "finiteCompanionPreparationNs" to it.elapsedNanos,
                "finiteCompanionPreparationReady" to if (companions != null) 1L else 0L,
                "finiteCompanionPreparationUnavailable" to if (companions == null) 1L else 0L)
        }.orEmpty(),
        livePreparationStages = companionAdmission?.let { it::stages },
    ) { workers ->
        val proofShares = clausalProofShares(scenario, profile, workers.map { it.label })
        val shares = if (proofShares.isEmpty()) {
            continuousLpShares(
                scenario,
                profile,
                workers.map { it.label },
                objective,
                problem.continuousMajority,
                problem.binaryIntegers,
            )
        } else {
            proofShares
        }
        val finiteReserved = if (shares.isEmpty()) booleanIncumbentShares(workers) else shares
        val reserved = if (companions == null) finiteReserved else {
            (if (finiteReserved.isEmpty()) DoubleArray(workers.size) else finiteReserved).also {
                it[workers.indexOfFirst { worker -> worker.label == "ls/finite-joint-companion" }] = 0.3
            }
        }
        val probes = if (scenario.cores == 1 && scenario.engine == EngineMix.MIXED && scenario.kind == Kind.COP &&
            scenario.btPool == null && scenario.lsPool == null
        ) {
            workers.indices.filter { arm -> arm >= scenario.arms && workers[arm].label == "bt/lp-default" }.toSet()
        } else {
            emptySet()
        }
        val deferred = if (deferLpProbes) deferredLpProbes(workers) else null
        val probeWeights = when {
            probePolicy == InitialProbePolicy.LEADER_HALF -> leadingProbeWeights(workers)
            probePolicy == InitialProbePolicy.LP_TRANSFER -> lpTransferredProbeWeights(workers)
            companions == null -> intArrayOf()
            else -> IntArray(workers.size) { arm ->
                if (workers[arm].label == "numerical/finite-companion") 3 else 1
            }
        }
        PortfolioInvocationPolicy(profile, reserved, reserveBeforeIncumbentOnly = true, probes = probes,
            probeWeights = probeWeights,
            onProgress = deferred)
    }
}

internal fun leadingProbeWeights(workers: List<PortfolioWorker>): IntArray {
    val initial = workers.indices.filter {
        !workers[it].startAfterIncumbent && !workers[it].improvementOnly && !workers[it].auxiliaryProof
    }
    val leader = initial.firstOrNull {
        workers[it].family == ArmFamily.Backtrack && workers[it].permitsSourceProofs
    } ?: return intArrayOf()
    return IntArray(workers.size) { if (it == leader) (initial.size - 1).coerceAtLeast(1) else 1 }
}

internal fun lpTransferredProbeWeights(workers: List<PortfolioWorker>): IntArray {
    val initial = workers.indices.filter {
        !workers[it].startAfterIncumbent && !workers[it].improvementOnly && !workers[it].auxiliaryProof
    }
    val leader = initial.firstOrNull {
        workers[it].family == ArmFamily.Backtrack && workers[it].permitsSourceProofs && !workers[it].isLpProbe()
    } ?: return intArrayOf()
    val donors = initial.filter { workers[it].isLpProbe() }
    if (donors.isEmpty()) return intArrayOf()
    return IntArray(workers.size) { arm ->
        when (arm) {
            leader -> 2 + donors.size
            in donors -> 1
            else -> 2
        }
    }
}

private fun PortfolioWorker.isLpProbe(): Boolean = when (label) {
    "bt/lp-default", "bt/lp-aggressive", "bt/lp-conservative" -> true
    else -> false
}

internal fun deferredLpProbes(workers: List<PortfolioWorker>): ((PortfolioEvent) -> Unit)? {
    val delayed = workers.indices.filter {
        workers[it].isLpProbe() && !workers[it].startAfterIncumbent
    }
    val pending = workers.indices.filter {
        it !in delayed && !workers[it].startAfterIncumbent &&
            !workers[it].improvementOnly && !workers[it].auxiliaryProof
    }.toMutableSet()
    if (delayed.isEmpty() || pending.isEmpty()) return null
    delayed.forEach { workers[it].startAfterIncumbent = true }
    var released = false
    return observer@{ event ->
        if (released) return@observer
        val progress = (event as? PortfolioEvent.Progressed)?.progress ?: return@observer
        if (progress.idleCredit) return@observer
        pending.remove(progress.arm)
        if (progress.acceptedIncumbent || pending.isEmpty()) {
            delayed.forEach { workers[it].startAfterIncumbent = false }
            released = true
        }
    }
}

internal fun booleanIncumbentShares(workers: List<PortfolioWorker>): DoubleArray {
    if (workers.none { it.auxiliaryProof }) return DoubleArray(0)
    val complete = workers.indices.filter {
        workers[it].family == ArmFamily.Backtrack && !workers[it].auxiliaryProof && !workers[it].improvementOnly
    }
    if (complete.isEmpty()) return DoubleArray(0)
    val families = workers.map { it.family }.distinct().size
    val share = 1.0 / families / complete.size
    return DoubleArray(workers.size).also { shares -> complete.forEach { shares[it] = share } }
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
    ) {
        return DoubleArray(0)
    }
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
    ) {
        return DoubleArray(0)
    }
    val lp = labels.indexOf("bt/lp-default")
    if (lp < 0) return DoubleArray(0)
    // LP is the complete arm that optimizes continuous objective columns. Its expensive first solves can
    // outweigh several cheap slices before it reaches a witness, so reserve time for that descent.
    return DoubleArray(labels.size).also { it[lp] = 0.5 }
}
