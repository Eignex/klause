package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.CostShaping
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.schedule.Geometric
import com.eignex.klause.meta.alns.Alns
import com.eignex.klause.meta.alns.BacktrackRepair
import com.eignex.klause.meta.alns.DestroyOperator
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.finiteExactNeighborhoodWorker
import com.eignex.klause.solver.pipeline.fixedDiscreteSample
import com.eignex.klause.solver.result.SearchEvent
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random

/**
 * A portfolio arm running hybrid ALNS with CP repair: an outer destroy/repair loop over the
 * incumbent, each freed neighbourhood repaired by a bounded backtrack LCG+LP solve under the pinned
 * complement ([BacktrackRepair]) — full propagation, clause learning, and LP bounding on the fragment,
 * unlike a pure-LS repair. An LS-class engine: it optimises anytime and returns
 * [com.eignex.klause.solver.result.MinimizeResult.BestFound], never claiming completeness, so it fits
 * the local-search track. COP-oriented (it optimises an incumbent); on a CSP the underlying [Alns]
 * degrades to its inner LS via `solve`.
 *
 * [profile] fixes this arm's regime — its destroy-size band and acceptance temperature; a diverse ALNS
 * engine cycles [AlnsProfile.Curated] via [diverse]. The default regime is used for the standalone arm
 * (e.g. the neighbourhood arm of a mixed roster).
 *
 * [nodeBudget] is the solve-spanning node allowance. This arm builds its repair [BacktrackParams] itself
 * rather than drawing a [com.eignex.klause.backtrack.BacktrackRecipe] from the pool, so it has to be
 * handed the allowance explicitly; without it the bootstrap and every repair search nodes that neither
 * count against the cap nor stop at it. The outer destroy/repair loop also charges each iteration's flip
 * allowance as local-search moves, so a run whose repairs never search still stops at the cap.
 *
 * [PortfolioWorker.newResumableSearch] retains CP and LS bootstrap preparation across counted
 * [PortfolioWorker.acceptsInstructionBudget] segments. After the first incumbent it retains the
 * neighborhood, acceptance policy and CP repair session. Each finite segment charges its offered
 * scheduling allowance; [com.eignex.klause.solver.result.AlnsStats] records actual inner work separately.
 * Repairs remain bounded atomic fragment searches.
 */
internal class AlnsWorkerConfig private constructor(
    val profile: AlnsProfile,
    val nodeBudget: NodeBudget?,
    private val numericalCompletion: Boolean,
    private val toleranceCheck: ((Sample) -> Boolean)?,
    private val workScheduled: Boolean = false,
    private val sharingMemory: Boolean = false,
    private val sharingMemoryWork: Long = 32_768L,
    private val sharingMemoryScratch: Int = 4_096,
    private val sharingMemoryBytes: Long? = null,
    private val neighborhoodObserver: PoolClauseNeighborhoodObserver? = null,
) :
    WorkerConfig {
    constructor(
        profile: AlnsProfile = AlnsProfile.Default,
        nodeBudget: NodeBudget? = null,
        numericalCompletion: Boolean = false,
    ) : this(profile, nodeBudget, numericalCompletion, null)

    fun withToleranceCheck(check: (Sample) -> Boolean): AlnsWorkerConfig =
        AlnsWorkerConfig(
            profile, nodeBudget, numericalCompletion, check,
            workScheduled, sharingMemory, sharingMemoryWork, sharingMemoryScratch, sharingMemoryBytes,
            neighborhoodObserver,
        )

    override fun withNodeBudget(budget: NodeBudget): WorkerConfig =
        AlnsWorkerConfig(
            profile, budget, numericalCompletion, toleranceCheck,
            workScheduled, sharingMemory, sharingMemoryWork, sharingMemoryScratch, sharingMemoryBytes,
            neighborhoodObserver,
        )

    fun withNumericalCompletion(): AlnsWorkerConfig =
        AlnsWorkerConfig(
            profile, nodeBudget, true, toleranceCheck,
            workScheduled, sharingMemory, sharingMemoryWork, sharingMemoryScratch, sharingMemoryBytes,
            neighborhoodObserver,
        )

    fun withWorkScheduling(): AlnsWorkerConfig =
        AlnsWorkerConfig(
            profile, nodeBudget, numericalCompletion, toleranceCheck,
            true, sharingMemory, sharingMemoryWork, sharingMemoryScratch, sharingMemoryBytes, neighborhoodObserver,
        )

    fun withSharingMemory(enabled: Boolean, work: Long, scratch: Int, bytes: Long? = null): AlnsWorkerConfig =
        AlnsWorkerConfig(
            profile, nodeBudget, numericalCompletion, toleranceCheck,
            workScheduled, enabled, work, scratch, bytes, neighborhoodObserver,
        )

    fun withNeighborhoodObserver(observer: PoolClauseNeighborhoodObserver): AlnsWorkerConfig =
        AlnsWorkerConfig(profile, nodeBudget, numericalCompletion, toleranceCheck,
            workScheduled, sharingMemory, sharingMemoryWork, sharingMemoryScratch, sharingMemoryBytes, observer)

    override val label: String get() = "alns-${profile.label}"

    override fun materialize(
        problem: BakedProblem,
        index: Int,
        armId: Int,
        seed: Long,
        lsLambda: Double,
        objective: LinearObjective?,
        lsObjective: IncrementalObjective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        pools: SharedPools?,
    ): PortfolioWorker {
        if (!numericalCompletion && problem.numRealVars > 0) problem.fixedDiscreteSample()?.let { fixed ->
            return finiteExactNeighborhoodWorker(problem, fixed, objective, nodeBudget, true, seed + index, armId)
        }
        val evidenceModel = ModelIdentity.of(problem, objective)
        val matchedPools = pools?.takeIf { it.identity?.matches(evidenceModel) != false }
        val peerPools = pools?.takeIf { it.identity?.matches(evidenceModel) == true }
        val workerLabel = "alns/${profile.label}"
        // One pool carries globally-valid learned clauses across repair fragments. A repair learns under
        // pins and an incumbent, so its permanent (objective-bound, blocking) clauses and LP Farkas nogoods
        // hold only under those pins and are not shared.
        val repairClauses = SharedClausePool(measureMemory = sharingMemory,
            memorySampleWork = sharingMemoryWork, memorySampleScratchEntries = sharingMemoryScratch,
            softLimitBytes = sharingMemoryBytes)
        val meter = SharingMeter()
        val alns = Alns(
            inner = LocalSearchSolver(
                problem,
                definitionalSweep = definitionalSweep,
                perMoveInvariants = definitionalSweep != null,
                completion = if (numericalCompletion && problem.numRealVars > 0)
                    LeafRealCompletion(problem, objective, toleranceCheck) else null,
            ).apply {
                matchedPools?.localSearchProjection?.let { engine.projection = it }
                matchedPools?.localSearchInvariants?.let { engine.invariantNetwork = it }
            },
            destroyOperators = peerPools?.clauses?.let { clauses ->
                DestroyOperator.Defaults + PoolClauseRelatedDestroy(clauses, meter,
                    observer = neighborhoodObserver, worker = workerLabel, seed = seed + index)
            } ?: DestroyOperator.Defaults,
            repairOperators = BacktrackRepair.Defaults,
            backtrack = BacktrackSolver(problem),
            backtrackParams = BacktrackParams(
                randomSeed = seed + index,
                clauseExchange = PoolClauseExchange(repairClauses, skipPermanent = true,
                    shareGlobalNogoods = false, meter = meter, peerPool = peerPools?.clauses,
                    tally = peerPools?.contributions),
                nodeBudget = nodeBudget,
                toleranceCheck = toleranceCheck,
            ),
            minDestroyFraction = profile.minDestroyFraction,
            maxDestroyFraction = profile.maxDestroyFraction,
            pooledIncumbents = matchedPools?.solutions,
            acceptanceFor = acceptanceFactory(),
            literalSweep = definitionalSweep,
            rng = Random(seed + index),
        )
        if (peerPools != null) {
            alns.iterationObserver = neighborhoodObserver?.iterations?.owner(workerLabel, seed + index,
                problem.numBoolVars, problem.numIntVars, problem.numRealVars)
        }
        val params = LocalSearchParams(
            randomSeed = seed + index,
            costShaping = CostShaping.Linear(lambda = lsLambda),
            lsObjective = lsObjective,
            onEvent = onEvent?.let { sink -> { e -> sink(workerLabel, e) } },
            nodeBudget = nodeBudget,
        )
        return PortfolioWorker.of(
            workerLabel,
            armId,
            alns.session(),
            params,
            objective = objective,
            withWarmStart = { p, sample -> p.copy(initialAssignment = sample) },
            withInstructionBudget = { p, limit ->
                p.copy(maxInstructions = minOf(p.maxInstructions ?: Long.MAX_VALUE, limit))
            },
        ).bindEvidence(evidenceModel).also {
            it.family = ArmFamily.Lns
            it.sharingMeter = meter
        }
    }

    /**
     * This regime's acceptance-policy factory: strict hill-climbing when the profile disables worsening,
     * else simulated annealing whose start temperature is scaled to the initial objective — a move
     * `saInitialWorsening` worse than the first incumbent accepts with ~50% probability, then cools.
     */
    private fun acceptanceFactory(): (Double) -> AcceptanceCriterion = if (profile.saInitialWorsening <= 0.0) {
        { AcceptanceCriterion.Improving }
    } else {
        { initialObjective ->
            val start = (profile.saInitialWorsening * abs(initialObjective) / ln(2.0)).coerceAtLeast(1.0)
            val schedule = Geometric(initialTemperature = start, coolingRate = profile.saCooling)
            AcceptanceCriterion.SimulatedAnnealing(schedule)
        }
    }

    companion object {
        /** What an ALNS arm needs from a model: an objective to optimise. */
        val NEEDS: Set<ArmNeed> = setOf(ArmNeed.Objective)

        /** [count] diverse ALNS arms cycling the curated regimes ([AlnsProfile.Curated]) — the ALNS analog
         *  of [LocalSearchWorkerConfig.diverse]. Every slot is a fresh instance even when regimes repeat,
         *  and every slot spends [nodeBudget]. */
        fun diverse(count: Int, nodeBudget: NodeBudget? = null): List<AlnsWorkerConfig> =
            List(count) { AlnsWorkerConfig(AlnsProfile.Curated[it % AlnsProfile.Curated.size], nodeBudget) }
    }
}
