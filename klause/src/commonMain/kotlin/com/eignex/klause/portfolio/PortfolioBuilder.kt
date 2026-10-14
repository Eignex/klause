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
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.SearchEvent
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
        return List(count) { index ->
            val worker = factories[index % factories.size].materialize(
                index, scenario.seed, quotas?.get(index) ?: scenario.nodeBudget,
            )
            bindRuntime(worker, binding)
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
    ): List<FinitePortfolioFactory> {
        val plan = PortfolioComposition.plan(scenario, ProblemFacts.of(problem, profile, scenario.lpCeiling, objective))
        val residuals = if (scenario.reportLsResiduals) plan.arms.map { it.withLsResiduals() } else plan.arms
        val configs = if (scenario.reportLsPhaseTimes) residuals.map { it.withLsPhaseTimes() } else residuals
        val count = maxOf(configs.size, scenario.cores)
        val deferCore = scenario.cores == 1 && scenario.engine == EngineMix.MIXED &&
            scenario.btPool == null && configs.any { it is BacktrackWorkerConfig }
        val pools = poolsFor(scenario, problem, definitionalSweep, objective,
            clausePeers = (0 until count).count { configs[it % configs.size] is BacktrackWorkerConfig } > 1)
        return configs.mapIndexed { armId, config ->
            val family = when (config) {
                is BacktrackWorkerConfig, is CoreGuidedWorkerConfig -> ArmFamily.Backtrack
                is LocalSearchWorkerConfig -> ArmFamily.LocalSearch
                is AlnsWorkerConfig -> ArmFamily.Lns
            }
            FinitePortfolioFactory(armId, family) { index, seed, budget ->
                val admitted = if (config is CoreGuidedWorkerConfig && deferCore) {
                    config.withWorkLimit(scenario.sliceWork)
                } else config
                val bounded = budget?.let(admitted::withNodeBudget) ?: admitted
                bounded.materialize(
                    problem, index, armId, seed, scenario.lsLambda, objective, lsObjective,
                    definitionalSweep, onEvent, pools,
                ).bindEvidence(ModelIdentity.of(problem, objective)).also {
                    it.improvementOnly = armId >= plan.firstSolutionCount ||
                        (config is CoreGuidedWorkerConfig && deferCore)
                }
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
        ).map { bindRuntime(it, binding) }
    }

    private fun runtimeBinding(problem: BakedProblem, objective: LinearObjective?): EngineInvocation =
        EngineInvocation(problem.prepareExecutionPlan(objective, preferFinite = true),
            ModelIdentity.of(problem, objective), objective)

    private fun bindRuntime(worker: PortfolioWorker, binding: EngineInvocation): PortfolioWorker =
        worker.bindSharingInvocation(binding).bindInvocation(EngineInvocation(binding.preparedPlan, binding.model,
            binding.executionObjective, executionAssumptions = worker.executionAssumptions ?: Assumptions.None))

    /** Materialise each composed arm via its own [WorkerConfig.materialize] — the shared body of
     *  [build] and [buildExplicit]. The arm index offsets the seed;
     *  [armIds] carries each lane's composed-arm identity (replicas share one, see [build]) purely as
     *  attribution metadata; [pools], when non-null, is shared by every backtrack arm for clause and
     *  cut exchange. */
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
    ): List<PortfolioWorker> {
        val workers = arms.mapIndexed { i, config ->
            config.materialize(
                problem, i, armIds[i], seed, lsLambda, objective, lsObjective, definitionalSweep, onEvent, pools,
            ).bindEvidence(ModelIdentity.of(problem, objective))
        }
        check(workers.isNotEmpty()) { "portfolio produced no workers" }
        return workers
    }

    /**
     * The shared pools for [scenario], or null when sharing doesn't apply (an LS-only portfolio
     * ignores both). Created once per build and handed to every backtrack arm. The lock is derived
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
            if (clausePeers) SharedClausePool(
                concurrency.lock(),
                shareMaxLbd = scenario.clauseShareMaxLbd,
                shareMaxLen = scenario.clauseShareMaxLen,
            ) else null,
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
                problem, LocalSearchModel.of(problem).domains, concurrency.lock(),
            ).takeIf { it.shareable },
            localSearchInvariants = InvariantNetworkPreparation(
                definitionalSweep, problem.numIntVars, problem.numBoolVars, concurrency.lock(),
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
