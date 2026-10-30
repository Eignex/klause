package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.InvariantNetworkPreparation
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchPreparation
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionStrategy
import com.eignex.klause.solver.pipeline.ObjectiveCoordinateView
import com.eignex.klause.solver.pipeline.PreparedSourceHints
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.validation.finiteWitnessVerifier
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.lock

/**
 * Supplies finite recipes and shared immutable preparation to source portfolio construction.
 * [PortfolioComposition] selects arms; each [WorkerConfig.materialize] creates its private engine state.
 * [build] and [buildExplicit] retain the low-level worker-list API for caller-controlled execution.
 * Those entry points bind one finite source invocation and each worker's actual execution assumptions.
 */
object PortfolioBuilder {
    /**
     * Compose [scenario] and materialise its arms over [problem].
     *
     * [objective] is the canonical [LinearObjective] every optimising worker minimises (null for a
     * satisfaction-only portfolio). [lsObjective] is the optional per-move gradient view of the
     * same objective for the local-search workers (see `LocalSearchParams.lsObjective`); backtrack
     * workers ignore it.
     *
     * [definitionalSweep] is threaded into every LS worker (per-move invariants). [onEvent]
     * threads the [SearchEvent] seam through to every worker tagged with its label; workers run
     * concurrently under a parallel `Portfolio`, so the listener must be thread-safe and cheap.
     *
     * [profile] classifies [problem]; the arms each family gets are the ones its class calls for.
     */
    fun build(
        problem: BakedProblem,
        scenario: PortfolioScenario,
        objective: LinearObjective? = null,
        lsObjective: IncrementalObjective? = null,
        definitionalSweep: DefinitionalSweep? = null,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)? = null,
        profile: ProblemProfile = ProblemProfile.of(problem, scenario.kind == Kind.COP),
    ): List<PortfolioWorker> {
        val binding = runtimeBinding(problem, objective)
        val factories = factories(problem, scenario, objective, lsObjective, definitionalSweep, onEvent, profile)
        val count = maxOf(factories.size, scenario.cores)
        val quotas = if (scenario.cores > 1) scenario.nodeBudget?.quotas(count) else null
        return materializeBound(count, binding) { index ->
            factories[index % factories.size].materialize(
                index,
                scenario.seed,
                quotas?.get(index) ?: scenario.nodeBudget,
            )
        }
    }

    internal fun factories(
        problem: BakedProblem,
        scenario: PortfolioScenario,
        objective: LinearObjective?,
        lsObjective: IncrementalObjective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        profile: ProblemProfile,
    ): List<FinitePortfolioFactory> = factories(
        problem,
        scenario,
        objective,
        lsObjective,
        definitionalSweep,
        onEvent,
        profile,
        false,
    )

    internal fun factories(
        problem: BakedProblem,
        scenario: PortfolioScenario,
        objective: LinearObjective?,
        lsObjective: IncrementalObjective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        profile: ProblemProfile,
        numericalCompletion: Boolean,
        sourceHints: ((Set<ExecutionStrategy>) -> PreparedSourceHints?)? = null,
        objectiveCoordinates: ObjectiveCoordinateView? = null,
    ): List<FinitePortfolioFactory> {
        val facts = ProblemFacts.of(problem, profile, scenario.lpCeiling, objective) { recipe, eligibility ->
            onEvent?.invoke("portfolio", SearchEvent.WorkerAdmission(recipe, eligibility.admitted,
                eligibility.requirements.toString(), eligibility.declines))
        }
        val plan = PortfolioComposition.plan(scenario, facts)
        val residuals = if (scenario.reportLsResiduals) plan.arms.map { it.withLsResiduals() } else plan.arms
        val configs = if (scenario.reportLsPhaseTimes) residuals.map { it.withLsPhaseTimes() } else residuals
        require(!scenario.earlyCoreAdmission || configs.any { it is CoreGuidedWorkerConfig }) {
            "core-early requires an eligible Boolean objective core worker"
        }
        val strategies = configs.mapNotNull { config ->
            when (config) {
                is LocalSearchWorkerConfig -> ExecutionStrategy.LOCAL_SEARCH
                is AlnsWorkerConfig -> ExecutionStrategy.ALNS
                is BacktrackWorkerConfig, is CoreGuidedWorkerConfig -> null
            }
        }.toSet()
        val views = if (strategies.isEmpty()) null else sourceHints?.invoke(strategies)
        val selectedSweep = views?.sweep ?: definitionalSweep
        val selectedObjective = views?.objective ?: lsObjective
        val count = maxOf(configs.size, scenario.cores)
        val deferCore = scenario.cores == 1 && scenario.engine == EngineMix.MIXED &&
            scenario.btPool == null && configs.any { it is BacktrackWorkerConfig }
        val pools = poolsFor(
            scenario,
            problem,
            selectedSweep,
            objective,
            clausePeers = (0 until count).count { configs[it % configs.size] is BacktrackWorkerConfig } > 1,
        )
        return configs.mapIndexed { armId, config ->
            val family = when (config) {
                is BacktrackWorkerConfig, is CoreGuidedWorkerConfig -> ArmFamily.Backtrack
                is LocalSearchWorkerConfig -> ArmFamily.LocalSearch
                is AlnsWorkerConfig -> ArmFamily.Lns
            }
            FinitePortfolioFactory(armId, family) { index, seed, budget ->
                val observed = if (config is AlnsWorkerConfig) config.withSharingMemory(
                    scenario.sharingMemory, scenario.sharingMemoryWork,
                    scenario.sharingMemoryScratch, scenario.sharingMemoryBytes,
                ) else config
                val admitted = when {
                    config is CoreGuidedWorkerConfig && deferCore ->
                        scenario.diagnosticWorkLimit?.let { config.withWorkLimit(it) } ?: config.withActiveTimeLimit()
                    observed is AlnsWorkerConfig && scenario.diagnosticWorkLimit != null ->
                        observed.withWorkScheduling()
                    else -> observed
                }
                val completion = if (numericalCompletion) {
                    when (admitted) {
                        is LocalSearchWorkerConfig -> admitted.withNumericalCompletion()
                        is AlnsWorkerConfig -> admitted.withNumericalCompletion()
                        else -> admitted
                    }
                } else {
                    admitted
                }
                val bounded = (budget?.let(completion::withNodeBudget) ?: completion)
                    .withCoreRefinement(scenario.coreRefinement)
                val privateCoordinates = objectiveCoordinates != null && bounded is LocalSearchWorkerConfig &&
                    config.label == LocalSearchArm.CblsPlateauIlsBasin.label &&
                    scenario.cores == 1 && scenario.engine == EngineMix.MIXED &&
                    scenario.lsPool == null && scenario.btPool == null
                val worker = if (privateCoordinates) {
                    bounded.materializeObjectiveCoordinates(
                        objectiveCoordinates, index, armId, seed, scenario.lsLambda, onEvent,
                    )
                } else bounded.materialize(
                    problem, index, armId, seed, scenario.lsLambda, objective, selectedObjective,
                    selectedSweep, onEvent, pools,
                )
                worker.bindEvidence(ModelIdentity.of(problem, objective)).also {
                    it.improvementOnly = if (config is CoreGuidedWorkerConfig && deferCore) {
                        !scenario.earlyCoreAdmission
                    } else armId >= plan.firstSolutionCount
                }
                onEvent?.let { observe ->
                    val authority = when {
                        !worker.permitsSourceProofs -> WorkerProofAuthority.WITNESS_ONLY
                        worker.auxiliaryProof || worker.improvementOnly -> WorkerProofAuthority.CONDITIONED_TERMINALS
                        else -> WorkerProofAuthority.CHECKED_TERMINALS
                    }
                    val requirements = WorkerRequirements(WorkerFragment.FINITE,
                        if (family == ArmFamily.Backtrack) WorkerObjectiveView.CANONICAL
                        else WorkerObjectiveView.CANONICAL_WITH_GUIDANCE,
                        authority, facts.construction,
                        sourceMapping = if (privateCoordinates) WorkerSourceMapping.CHECKED_RECONSTRUCTION
                        else WorkerSourceMapping.WORKING_COORDINATES)
                    observe("portfolio", SearchEvent.WorkerAdmission(config.label, true,
                        requirements.toString(), emptyList(), selected = true))
                }
                worker
            }
        }
    }

    /**
     * Materializes the local-search configs in [lsLabels] (`["all"]` selects the whole pool),
     * plus [backtrackWorkers] backtrack arms of [kind]. Bypasses [PortfolioComposition] and
     * cross-arm sharing for per-worker attribution; remaining wiring matches [build].
     */
    fun buildExplicit(
        problem: BakedProblem,
        lsLabels: List<String>,
        backtrackWorkers: Int,
        kind: Kind,
        seed: Long = 0L,
        lsLambda: Double = 1.0,
        objective: LinearObjective? = null,
        lsObjective: IncrementalObjective? = null,
        definitionalSweep: DefinitionalSweep? = null,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)? = null,
    ): List<PortfolioWorker> {
        require(backtrackWorkers >= 0) { "backtrackWorkers must be ≥ 0" }
        val binding = runtimeBinding(problem, objective)
        val lsConfigs = if (lsLabels == listOf("all")) {
            LocalSearchWorkerConfig.ranked(kind)
        } else {
            lsLabels.map { LocalSearchWorkerConfig.byLabel(it) }
        }
        val arms = buildList<WorkerConfig> {
            addAll(lsConfigs)
            if (backtrackWorkers > 0) addAll(BacktrackWorkerConfig.diverse(kind, backtrackWorkers))
        }
        // Sharing would confound per-worker attribution. Distinct arms use their position as armId.
        return materialize(
            problem, arms, List(arms.size) { it }, seed, lsLambda, objective, lsObjective, definitionalSweep,
            onEvent, pools = null,
            binding = binding,
        )
    }

    private fun runtimeBinding(problem: BakedProblem, objective: LinearObjective?): EngineInvocation = EngineInvocation(
        problem.prepareExecutionPlan(objective, preferFinite = true),
        ModelIdentity.of(problem, objective),
        objective,
    )

    private fun bindRuntime(worker: PortfolioWorker, binding: EngineInvocation): PortfolioWorker = worker.bindSource(
        SourceWorkerBinding(
            EngineInvocation(
                binding.preparedPlan,
                binding.model,
                binding.executionObjective,
                executionAssumptions = worker.executionAssumptions ?: Assumptions.None,
            ),
            sharingInvocation = binding,
            provesResults = worker.permitsSourceProofs,
        ),
    )

    /** Materialise each arm of [buildExplicit] via its own [WorkerConfig.materialize]. The arm index offsets
     *  the seed; [armIds] carries each lane's arm identity purely as attribution metadata; [pools], when
     *  non-null, is shared by every arm. */
    private fun materialize(
        problem: BakedProblem,
        arms: List<WorkerConfig>,
        armIds: List<Int>,
        seed: Long,
        lsLambda: Double,
        objective: LinearObjective?,
        lsObjective: IncrementalObjective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        pools: SharedPools?,
        binding: EngineInvocation,
    ): List<PortfolioWorker> {
        val workers = materializeBound(arms.size, binding) { i ->
            arms[i].materialize(
                problem, i, armIds[i], seed, lsLambda, objective, lsObjective, definitionalSweep, onEvent, pools,
            ).bindEvidence(ModelIdentity.of(problem, objective))
        }
        check(workers.isNotEmpty()) { "portfolio produced no workers" }
        return workers
    }

    @Suppress("TooGenericExceptionCaught")
    private fun materializeBound(
        count: Int,
        binding: EngineInvocation,
        create: (Int) -> PortfolioWorker,
    ): List<PortfolioWorker> {
        val workers = ArrayList<PortfolioWorker>(count)
        try {
            repeat(count) { index ->
                val worker = create(index)
                workers += worker
                bindRuntime(worker, binding)
            }
            return workers
        } catch (failure: Throwable) {
            for (worker in workers.asReversed()) {
                runCatching { worker.close() }.exceptionOrNull()
                    ?.takeUnless { it === failure }?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    /**
     * The shared pools for [scenario], or null when sharing doesn't apply (an LS-only portfolio
     * ignores both). Created once per build and handed to every arm. The lock is derived
     * from the executor's concurrency: a no-op on one lane (`Concurrency.None`, zero overhead — the clause pool
     * is just cross-segment memory there) and a platform mutex for several lanes' concurrent writers. The clause
     * pool is present when multiple backtrack workers can exchange clauses; the cut pool only when
     * [PortfolioScenario.shareCuts] opts in.
     */
    private fun poolsFor(
        scenario: PortfolioScenario,
        problem: BakedProblem,
        definitionalSweep: DefinitionalSweep?,
        objective: LinearObjective?,
        clausePeers: Boolean,
    ): SharedPools? {
        if (scenario.engine == EngineMix.LOCAL_SEARCH) return null
        val identity = ModelIdentity.of(problem, objective)
        val concurrency = if (scenario.cores == 1) Concurrency.None else Concurrency.Strict
        val cuts = if (scenario.shareCuts) SharedCutPool(concurrency.lock()) else null
        val witnessVerifier = finiteWitnessVerifier(problem, objective, toleranceCheck = scenario.toleranceCheck)
        // The bound managers are the dual of the shared incumbent: harmless for a CSP pool (no arm
        // publishes), so they are always present and only an optimising arm feeds them.
        return SharedPools(
            if (clausePeers) {
                SharedClausePool(
                    concurrency.lock(),
                    measureMemory = scenario.sharingMemory,
                    memorySampleWork = scenario.sharingMemoryWork,
                    memorySampleScratchEntries = scenario.sharingMemoryScratch,
                    shareMaxLbd = scenario.clauseShareMaxLbd,
                    shareMaxLen = scenario.clauseShareMaxLen,
                    softLimitBytes = scenario.sharingMemoryBytes,
                    booleanOnly = problem.numIntVars == 0,
                )
            } else {
                null
            },
            cuts,
            SharedObjectiveBound(concurrency.lock(), identity),
            SharedVarBounds(problem.numIntVars, concurrency.lock(), identity.forObjective(null)),
            IncumbentExchange<Sample, Double>(
                improves = { candidate, standing -> candidate < standing },
                verifier = { candidate ->
                    if (!candidate.objective.isFinite()) {
                        Verification.Rejected("non-finite objective ${candidate.objective}")
                    } else {
                        when (val checked = witnessVerifier.verify(candidate)) {
                            is Verification.Accepted -> Verification.Accepted(
                                Candidate(candidate.assignment, candidate.objective),
                            )

                            is Verification.Rejected -> checked

                            is Verification.Indeterminate -> checked
                        }
                    }
                },
            ),
            ContributionTally(concurrency.lock()),
            nativeProjection = PropagationProblem(problem).takeIf { it.isNativeSatEligible },
            localSearchProjection = LocalSearchPreparation(
                problem,
                LocalSearchModel.of(problem).domains,
                concurrency.lock(),
            ).takeIf { it.shareable },
            localSearchInvariants = InvariantNetworkPreparation(
                definitionalSweep,
                problem.numIntVars,
                problem.numBoolVars,
                concurrency.lock(),
            ),
            identity = identity,
        )
    }
}

internal class FinitePortfolioFactory(
    val armId: Int,
    val family: ArmFamily,
    private val create: (Int, Long, NodeBudget?) -> PortfolioWorker,
) {
    fun materialize(index: Int, seed: Long, nodeBudget: NodeBudget?): PortfolioWorker = create(index, seed, nodeBudget)
}
