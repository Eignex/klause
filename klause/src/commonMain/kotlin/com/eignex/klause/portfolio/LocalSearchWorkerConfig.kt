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
 * A portfolio arm wrapping a curated [LocalSearchRecipe] for execution: it materialises the recipe into a
 * runnable [LocalSearchSession] worker. The recipe owns the four axes (restart included, in its
 * schedule); this adapter owns only the run-time wiring (λ-shaping, warm-start, event sink, and the solve-wide
 * [nodeBudget] every segment spends).
 */
internal class LocalSearchWorkerConfig private constructor(
    val recipe: LocalSearchRecipe,
    val nodeBudget: NodeBudget?,
    private val reportResiduals: Boolean,
    private val reportPhaseTimes: Boolean,
    private val seedOffset: Int,
    private val numericalCompletion: Boolean,
    private val toleranceCheck: ((Sample) -> Boolean)?,
) :
    WorkerConfig {

    constructor(
        recipe: LocalSearchRecipe,
        nodeBudget: NodeBudget? = null,
        reportResiduals: Boolean = false,
        reportPhaseTimes: Boolean = false,
        seedOffset: Int = 0,
        numericalCompletion: Boolean = false,
    ) : this(recipe, nodeBudget, reportResiduals, reportPhaseTimes, seedOffset, numericalCompletion, null)

    fun withToleranceCheck(check: (Sample) -> Boolean): LocalSearchWorkerConfig =
        LocalSearchWorkerConfig(recipe, nodeBudget, reportResiduals, reportPhaseTimes, seedOffset,
            numericalCompletion, check)

    override fun withNodeBudget(budget: NodeBudget): WorkerConfig =
        LocalSearchWorkerConfig(recipe, budget, reportResiduals, reportPhaseTimes, seedOffset,
            numericalCompletion, toleranceCheck)

    fun withNumericalCompletion(): LocalSearchWorkerConfig =
        LocalSearchWorkerConfig(recipe, nodeBudget, reportResiduals, reportPhaseTimes, seedOffset, true, toleranceCheck)

    override val label: String get() = recipe.label

    override fun withLsResiduals(): LocalSearchWorkerConfig =
        LocalSearchWorkerConfig(recipe, nodeBudget, true, reportPhaseTimes, seedOffset,
            numericalCompletion, toleranceCheck)

    override fun withLsPhaseTimes(): LocalSearchWorkerConfig =
        LocalSearchWorkerConfig(recipe, nodeBudget, reportResiduals, true, seedOffset,
            numericalCompletion, toleranceCheck)

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

    /** Build an LS worker: its [LocalSearchSolver] session (with the per-move invariant network when
     *  a [definitionalSweep] is present and the recipe enables it) + λ-shaped params, exposing the
     *  warm-start seam so a [Portfolio] can resume a segment from the shared incumbent. The
     *  restart cadence rides on the recipe's `strategy.schedule.restart`. Label is `ls/<label>`. A model with
     *  continuous columns whose discrete part is already fixed gets an exact neighbourhood worker instead,
     *  unless numerical completion is enabled. */
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
        // On a COP, every arm optimizes. A recipe that drives objective descent itself (CBLS, SA) needs
        // nothing; a violation-native one (probSAT / WalkSAT / feasibility-jump) gets an `objective ≤
        // incumbent` ratchet overlaid on its problem and the shared bound to tighten — so no arm bails at
        // feasibility on a COP. A CSP (no objective) leaves every arm a pure feasibility finder.
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
            randomSeed = seed + index + seedOffset,
            costShaping = CostShaping.Linear(lambda = lsLambda),
            // The per-move gradient view of the objective, when the model provides one.
            lsObjective = lsObjective,
            objectiveGuidance = recipe.objectiveGuidance,
            onEvent = onEvent?.let { sink -> { e -> sink(workerLabel, e) } },
            // Keep a single over-populated constraint kind from steering the initial descent; a
            // no-op for the pool's weight-blind arms.
            normalizeWeightsByClass = true,
            reportResiduals = reportResiduals,
            reportPhaseTimes = reportPhaseTimes,
            // Bidirectional cross-engine flow: publish incumbents this arm finds and, on restart, anchor
            // on the verified global best — so LS and backtrack incumbents circulate both ways.
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
        ).bindEvidence(evidenceModel)
    }

    companion object {
        /** A fresh instance of the pool config named [label] (the string boundary). */
        fun byLabel(label: String): LocalSearchWorkerConfig = LocalSearchWorkerConfig(LocalSearchCatalog.byLabel(label))

        /** The credit-ordered LS pool for [kind] — [LocalSearchCatalog.ranked] wrapped as arms. */
        fun ranked(kind: Kind): List<LocalSearchWorkerConfig> =
            LocalSearchCatalog.ranked(kind).map { LocalSearchWorkerConfig(it) }

        /** The top-[count] prefix of [kind]'s credit-ordered pool (wrapping past the pool size) —
         *  `-p <n>` maps straight onto this. Every slot is a fresh instance even when arms repeat. */
        fun diverse(
            kind: Kind,
            count: Int,
            nodeBudget: NodeBudget? = null,
            problemClass: ProblemClass = ProblemClass.FiniteCp,
        ): List<LocalSearchWorkerConfig> =
            LocalSearchCatalog.diverse(kind, count, problemClass).map { LocalSearchWorkerConfig(it, nodeBudget) }
    }
}
