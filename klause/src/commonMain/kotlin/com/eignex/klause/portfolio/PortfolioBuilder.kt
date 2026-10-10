package com.eignex.klause.portfolio

import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.lock

/**
 * Materialises the arms a [PortfolioScenario] composes to into runnable [PortfolioWorker]s — the
 * single construction path every portfolio flows through, whatever the scenario (threads × kind ×
 * engine). The *policy* (which arms, ordering, mixed split) lives in [PortfolioComposition]; the
 * per-arm construction (which solver, objective form, bound / warm-start seam) lives in each
 * [WorkerConfig.materialize]. This builder is the thin glue: `compose` → `map { it.materialize(…) }`,
 * with no engine-specific switch.
 *
 * The returned [PortfolioWorker] list is what a [Portfolio] schedules, on one lane per core of the scenario.
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
        val facts = ProblemFacts.of(problem, profile, scenario.lpCeiling)
        val plan = PortfolioComposition.plan(scenario, facts)
        val composed = plan.arms
        // Expand the composed arms to one entry per lane. A lane is a worker slot; a parallel track
        // wants one per core, the sequential track one per arm — so laneCount is maxOf(arms, cores).
        // When arms >= cores (every existing scenario) this is a no-op cycle that returns the composed
        // list verbatim, so the built workers are byte-identical. Only arms < cores grows the list,
        // cycling the composed arms so the extra lanes are seed-diversified replicas: materialize feeds
        // each lane's index as its per-worker seed offset, exactly as diverse() wraps past the pool onto
        // fresh seeds, so a replica of a config gets a distinct seed.
        val laneCount = maxOf(composed.size, scenario.cores)
        val lanes = List(laneCount) { composed[it % composed.size] }
        // Lane i is a replica of composed arm i % composed.size, so that is its stable arm identity:
        // replicas of one config share an armId, distinct composed arms get distinct ones.
        val armIds = List(laneCount) { it % composed.size }
        return materialize(
            problem,
            lanes,
            armIds,
            scenario.seed,
            scenario.lsLambda,
            objective,
            lsObjective,
            definitionalSweep,
            onEvent,
            pools = poolsFor(scenario, problem),
        ).onEach { it.improvementOnly = it.armId >= plan.firstSolutionCount }
    }

    /**
     * Override entry for the per-worker credit campaign: materialise an **explicit** arm mix —
     * the LS configs named in [lsLabels] (or the whole pool for `["all"]`) plus [backtrackWorkers]
     * backtrack arms of [kind] — bypassing [PortfolioComposition] so the campaign can attribute
     * credit to an arbitrary composition. All other wiring matches [build].
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
        val lsConfigs = if (lsLabels == listOf("all")) {
            LocalSearchWorkerConfig.ranked(kind)
        } else {
            lsLabels.map { LocalSearchWorkerConfig.byLabel(it) }
        }
        val arms = buildList<WorkerConfig> {
            addAll(lsConfigs)
            if (backtrackWorkers > 0) addAll(BacktrackWorkerConfig.diverse(kind, backtrackWorkers))
        }
        // The credit campaign measures per-worker attribution, which cross-arm sharing would confound,
        // so the explicit path never shares. Every arm here is distinct, so armId == its position.
        return materialize(
            problem, arms, List(arms.size) { it }, seed, lsLambda, objective, lsObjective, definitionalSweep,
            onEvent, pools = null,
        )
    }

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
            )
        }
        check(workers.isNotEmpty()) { "portfolio produced no workers" }
        return workers
    }

    /**
     * The shared pools for [scenario], or null when sharing doesn't apply (an LS-only portfolio
     * ignores both). Created once per build and handed to every backtrack arm. The lock is derived
     * from the executor's concurrency: a no-op on one lane (`Concurrency.None`, zero overhead — the clause pool
     * is just cross-segment memory there) and a platform mutex for several lanes' concurrent writers. The clause
     * pool is always present; the cut pool only when [PortfolioScenario.shareCuts] opts in.
     */
    private fun poolsFor(scenario: PortfolioScenario, problem: BakedProblem): SharedPools? {
        if (scenario.engine == EngineMix.LOCAL_SEARCH) return null
        val concurrency = if (scenario.cores == 1) Concurrency.None else Concurrency.Strict
        val cuts = if (scenario.shareCuts) SharedCutPool(concurrency.lock()) else null
        // The bound managers are the dual of the shared incumbent: harmless for a CSP pool (no arm
        // publishes), so they are always present and only an optimising arm feeds them.
        return SharedPools(
            SharedClausePool(
                concurrency.lock(),
                shareMaxLbd = scenario.clauseShareMaxLbd,
                shareMaxLen = scenario.clauseShareMaxLen,
            ),
            cuts,
            SharedObjectiveBound(concurrency.lock()),
            SharedVarBounds(problem.numIntVars, concurrency.lock()),
            IncumbentExchange.minimizing(),
            ContributionTally(concurrency.lock()),
            nativeProjection = PropagationProblem(problem).takeIf { it.isNativeSatEligible },
            localSearchProjection = lazy { LocalSearchProblem(problem, LocalSearchModel.of(problem).domains) },
        )
    }
}
