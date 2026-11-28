package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.objective.objectiveBoundOverlay
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.localsearch.CostShaping
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSession
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.ObjectiveGuidance
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ObjectiveCoordinateView
import com.eignex.klause.solver.pipeline.finiteExactNeighborhoodWorker
import com.eignex.klause.solver.pipeline.fixedDiscreteSample
import com.eignex.klause.solver.result.SearchEvent

/**
 * Materialises a [LocalSearchRecipe] into a [LocalSearchSession] worker. The recipe owns the search axes; this
 * adapter owns only the run-time wiring (λ-shaping, warm-start, event sink, node budget).
 */
internal class LocalSearchWorkerConfig private constructor(
    val recipe: LocalSearchRecipe,
    val nodeBudget: NodeBudget?,
    private val reportResiduals: Boolean,
    private val reportPhaseTimes: Boolean,
    private val numericalCompletion: Boolean,
    private val toleranceCheck: ((Sample) -> Boolean)?,
    private val bufferedPublications: Boolean,
) :
    WorkerConfig {

    constructor(
        recipe: LocalSearchRecipe,
        nodeBudget: NodeBudget? = null,
        reportResiduals: Boolean = false,
        reportPhaseTimes: Boolean = false,
        numericalCompletion: Boolean = false,
    ) : this(recipe, nodeBudget, reportResiduals, reportPhaseTimes, numericalCompletion, null, false)

    fun withBufferedPublications(): LocalSearchWorkerConfig =
        LocalSearchWorkerConfig(recipe, nodeBudget, reportResiduals, reportPhaseTimes,
            numericalCompletion, toleranceCheck, true)

    fun withToleranceCheck(check: (Sample) -> Boolean): LocalSearchWorkerConfig =
        LocalSearchWorkerConfig(recipe, nodeBudget, reportResiduals, reportPhaseTimes,
            numericalCompletion, check, bufferedPublications)

    override fun withNodeBudget(budget: NodeBudget): WorkerConfig =
        LocalSearchWorkerConfig(recipe, budget, reportResiduals, reportPhaseTimes,
            numericalCompletion, toleranceCheck, bufferedPublications)

    fun withNumericalCompletion(): LocalSearchWorkerConfig =
        LocalSearchWorkerConfig(recipe, nodeBudget, reportResiduals, reportPhaseTimes, true,
            toleranceCheck, bufferedPublications)

    override val label: String get() = recipe.label

    override fun withLsResiduals(): LocalSearchWorkerConfig =
        LocalSearchWorkerConfig(recipe, nodeBudget, true, reportPhaseTimes,
            numericalCompletion, toleranceCheck, bufferedPublications)

    override fun withLsPhaseTimes(): LocalSearchWorkerConfig =
        LocalSearchWorkerConfig(recipe, nodeBudget, reportResiduals, true,
            numericalCompletion, toleranceCheck, bufferedPublications)

    internal fun materializeObjectiveCoordinates(
        view: ObjectiveCoordinateView,
        index: Int,
        armId: Int,
        seed: Long,
        lsLambda: Double,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
    ): PortfolioWorker = materialize(
        view.problem, index, armId, seed, lsLambda, view.objective, view.hints?.objective,
        view.hints?.sweep,
        onEvent = null,
        pools = null,
    ).objectiveCoordinateProposals(view.canonicalModel) { sample ->
        view.proposal(sample)?.also { point ->
            onEvent?.invoke("ls/$label/objective-coordinates", SearchEvent.Incumbent(point.objective))
        }
    }

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
        pools: SharedPools?, // solutions only: local search neither learns nor consumes clauses or cuts
    ): PortfolioWorker {
        if (!numericalCompletion && problem.numRealVars > 0) problem.fixedDiscreteSample()?.let { fixed ->
            return finiteExactNeighborhoodWorker(problem, fixed, objective, nodeBudget, false, seed + index, armId)
        }
        val evidenceModel = ModelIdentity.of(problem, objective)
        val matchedPools = pools?.takeIf { it.identity?.matches(evidenceModel) != false }
        // On a COP every arm optimizes: a violation-native recipe gets an `objective ≤ incumbent` ratchet overlaid
        // on its problem so it does not stop at feasibility.
        val (effectiveProblem, boundHandle) = if (objective != null &&
            recipe.feasibleDescent == FeasibleDescent.RatchetAsConstraint
        ) {
            objectiveBoundOverlay(problem, objective) ?: (problem to null)
        } else {
            problem to null
        }
        // WCNF indicators are private penalties; general defined outputs can also need hard-constraint repairs.
        val sweep = definitionalSweep ?: (lsObjective as? FunctionalObjective)?.booleanCostSweep?.takeIf {
            problem.numBoolVars <= 1_000_000 && problem.numIntVars == 0 && problem.numRealVars == 0 &&
                problem.semantics.effective.format == SourceFormat.WCNF &&
                objective != null && recipe.perMoveInvariants &&
                recipe.objectiveGuidance == ObjectiveGuidance.Functional &&
                recipe.feasibleDescent == FeasibleDescent.SelfOwned
        }
        val session = LocalSearchSolver(
            effectiveProblem,
            strategy = recipe.strategy,
            optimizeStrategy = recipe.optimizeStrategy,
            definitionalSweep = sweep,
            perMoveInvariants = sweep != null && recipe.perMoveInvariants,
            seedImplicitOnRestart = recipe.seedImplicitOnRestart,
            // Floating scores guide candidates; the residual LP retains the configured source guarantee.
            completion = if (problem.numRealVars > 0) LeafRealCompletion(problem, objective, toleranceCheck) else null,
        ).apply {
            objectiveBound = boundHandle
            if (effectiveProblem === problem) {
                matchedPools?.localSearchProjection?.let { engine.projection = it }
                if (sweep === definitionalSweep) {
                    matchedPools?.localSearchInvariants?.let { engine.invariantNetwork = it }
                }
            }
        }.session()
        val workerLabel = "ls/$label"
        val params = LocalSearchParams(
            randomSeed = seed + index,
            costShaping = CostShaping.Linear(lambda = lsLambda),
            lsObjective = lsObjective,
            objectiveGuidance = recipe.objectiveGuidance,
            onEvent = onEvent?.let { sink -> { e -> sink(workerLabel, e) } },
            // Keeps a single over-populated constraint kind from steering the initial descent.
            normalizeWeightsByClass = true,
            reportResiduals = reportResiduals,
            reportPhaseTimes = reportPhaseTimes,
            pooledIncumbents = matchedPools?.solutions,
            nodeBudget = nodeBudget,
        )
        return PortfolioWorker.of(
            workerLabel,
            armId,
            session,
            params,
            objective = objective,
            withWarmStart = { p, sample -> p.copy(initialAssignment = sample) },
            withInstructionBudget = { p, limit ->
                p.copy(maxInstructions = minOf(p.maxInstructions ?: Long.MAX_VALUE, limit))
            },
        ).bindEvidence(evidenceModel).also { worker ->
            if (bufferedPublications && matchedPools != null) {
                worker.publicationExchange = matchedPools.solutions
                worker.sharingMeter = SharingMeter()
            }
        }
    }

    companion object {
        fun byLabel(label: String): LocalSearchWorkerConfig = LocalSearchWorkerConfig(LocalSearchCatalog.byLabel(label))

        fun ranked(kind: Kind): List<LocalSearchWorkerConfig> =
            LocalSearchCatalog.ranked(kind).map { LocalSearchWorkerConfig(it) }

        fun diverse(
            kind: Kind,
            count: Int,
            nodeBudget: NodeBudget? = null,
            problemClass: ProblemClass = ProblemClass.FiniteCp,
        ): List<LocalSearchWorkerConfig> =
            LocalSearchCatalog.diverse(kind, count, problemClass).map { LocalSearchWorkerConfig(it, nodeBudget) }
    }
}
