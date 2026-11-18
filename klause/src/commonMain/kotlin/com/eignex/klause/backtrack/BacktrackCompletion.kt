package com.eignex.klause.backtrack

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.harvestRootRelaxation
import com.eignex.klause.lp.bounding.shaveVariableBounds
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveCursor
import com.eignex.klause.lp.engine.LpSolveStep
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.completedSearchSample
import com.eignex.klause.solver.validation.ToleranceRealSearch
import com.eignex.klause.solver.validation.ToleranceSearchResult
import com.eignex.klause.solver.validation.permitsToleranceOptimum
import com.eignex.klause.solver.validation.readingSourceSlack
import com.eignex.klause.solver.validation.toleranceSearch
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.theory.AbsRealCompletion
import com.eignex.klause.theory.requiresRealCompletionSearch
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.cancelledWhen
import com.eignex.klause.util.workScheduled
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal interface LpSearchResource : AutoCloseable {
    fun releasePersistentSolvers()

    fun totalSolveWork(): Long
}

internal sealed interface BacktrackCompletion {
    val lpResource: LpSearchResource? get() = null

    fun addTo(components: MutableList<SearchComponent>)

    fun sample(model: com.eignex.klause.solver.search.AssembledSearchModel, cp: CpSearchComponent): Sample

    data object Discrete : BacktrackCompletion {
        override fun addTo(components: MutableList<SearchComponent>) = Unit

        override fun sample(
            model: com.eignex.klause.solver.search.AssembledSearchModel,
            cp: CpSearchComponent,
        ): Sample = model.valueOf(completedSearchSample) ?: checkNotNull(model.valueOf(cp.assignmentKey))
    }

    private class ResidualReal(private val component: ResidualRealComponent) : BacktrackCompletion {
        override val lpResource: LpSearchResource get() = component

        override fun addTo(components: MutableList<SearchComponent>) {
            components += component
        }

        override fun sample(
            model: com.eignex.klause.solver.search.AssembledSearchModel,
            cp: CpSearchComponent,
        ): Sample = component.sample()
    }

    companion object {
        fun of(
            problem: Problem,
            cp: CpSearchComponent,
            params: BacktrackParams,
            sink: SolveStatsSink?,
            solveContext: LpSolveContext,
            cancellation: ExecutionControl = params.cancellation,
            slice: TraversalSlice? = null,
        ): BacktrackCompletion = if (problem.numRealVars == 0) {
            Discrete
        } else {
            ResidualReal(ResidualRealComponent(problem, cp, params, sink, solveContext, cancellation, slice))
        }
    }
}

/**
 * The LP relaxation as a satisfaction-path participant: refute a node whose relaxation is infeasible.
 *
 * The optimize path reaches the relaxation through [ResumableMinimize], which owns an [LpEngine] to bound
 * against an incumbent. A satisfaction search has no incumbent and no objective to bound, but that is not
 * the only thing a relaxation decides — an infeasible one refutes its node whatever the objective is, and
 * on real models that is where most of the pruning comes from. Without this component the relaxation is
 * unreachable on a model with no objective, however the arm is configured.
 *
 * The engine is handed a zero objective, so the bound arms it also carries can never prune: the
 * lower bound of a zero objective is zero and the bound to beat is infinite. Root cut harvesting,
 * hull pruning and variable shaving are objective-independent; node pruning uses infeasibility.
 */
internal class LpFeasibilityComponent(
    problem: Problem,
    private val cp: CpSearchComponent,
    private val params: BacktrackParams,
    sink: SolveStatsSink?,
    solveContext: LpSolveContext,
) : LpSearchResource {
    private var preparationToken: ExecutionControl? = null
    private val cancellation = cancelledWhen(
        { params.cancellation.deadline() }, { params.cancellation.workScheduled() },
        { params.cancellation.stopPolling },
    ) {
        params.cancellation() || preparationToken?.invoke() == true
    }
    val engine = LpEngine(
        problem,
        LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
        LpParams(
            lpPlan = params.lpPlan,
            lpConfig = params.lpConfig,
            cancellation = cancellation,
            solveBudgetMillis = params.solveBudgetMillis,
            randomSeed = params.randomSeed,
            zeroObjectivePricing = params.zeroObjectivePricing,
        ),
        sink ?: SolveStatsSink(backend = "backtrack"),
        solveContext.readingSourceSlack(problem, params.toleranceCheck),
    )

    val component: SearchComponent = engine.propagator.also { engine.cpAdapter.attach(cp.session, feasibility = true) }

    val preparesRoot: Boolean
        get() {
            val plan = engine.params.lpPlan
            return plan.pruneHulls || plan.variableShaving ||
                (plan.rootCutHarvest && (engine.lpSeparators.isNotEmpty() || plan.gomoryEnabled || plan.mirEnabled))
        }

    fun prepareRoot(): Boolean {
        val plan = engine.params.lpPlan
        val remaining = params.cancellation.deadline()?.let {
            (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds
        } ?: params.solveBudgetMillis
        val millis = remaining?.let { minOf((it * plan.rootBudgetFraction).toLong(), plan.rootBudgetMillis) }
            ?: plan.rootBudgetMillis
        val before = engine.totalSolveWork()
        val token = params.cancellation or ExecutionControl {
            (plan.rootMaxWork > 0L && engine.totalSolveWork() - before >= plan.rootMaxWork) ||
                engine.lpWorkAllowanceSpent()
        } or if (plan.rootBudgetFraction > 0.0 && !params.cancellation.workScheduled()) {
            ExecutionControl.after(millis.coerceAtLeast(0L).milliseconds)
        } else {
            ExecutionControl.Never
        }
        preparationToken = token
        try {
            engine.harvestRootRelaxation(token)
            if (plan.variableShaving) {
                for (bound in engine.shaveVariableBounds(token)) {
                    if (cp.session.implyIntAtLeast(bound.varId, bound.lo) is PropagationResult.Unsat ||
                        cp.session.implyIntAtMost(bound.varId, bound.hi) is PropagationResult.Unsat
                    ) return true
                }
            }
            return false
        } finally {
            preparationToken = null
            engine.cpAdapter.reset()
        }
    }

    override fun releasePersistentSolvers() = engine.releasePersistentSolvers()

    override fun totalSolveWork(): Long = engine.totalSolveWork()

    override fun close() = engine.close()
}

private class ResidualRealComponent(
    private val problem: Problem,
    private val cp: CpSearchComponent,
    params: BacktrackParams,
    sink: SolveStatsSink?,
    solveContext: LpSolveContext,
    private val cancellation: ExecutionControl,
    private val slice: TraversalSlice?,
) : SearchComponent,
    LpSearchResource {
    private val toleranceCheck = params.toleranceCheck
    private val absCompletion = if (problem.requiresRealCompletionSearch()) AbsRealCompletion(problem) else null

    // The source's search within its tolerance, present when acceptance admits tolerance points.
    private val toleranceSearch = toleranceCheck.toleranceSearch()
    private val floatCompletion = toleranceCheck.permitsToleranceOptimum()
    private val solveContext = solveContext
    private var absWork = 0L
    private val engine = LpEngine(
        problem,
        LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
        LpParams(
            lpPlan = params.lpPlan.copy(bounding = true, learn = true, realResidual = true),
            lpConfig = params.lpConfig,
            cancellation = cancellation,
            solveBudgetMillis = params.solveBudgetMillis,
            randomSeed = params.randomSeed,
            zeroObjectivePricing = params.zeroObjectivePricing,
        ),
        sink ?: SolveStatsSink(backend = "backtrack"),
        solveContext.readingSourceSlack(problem, toleranceCheck),
    )
    private var completed: Sample? = null
    private var pending: LpSolveCursor<ComponentCheck>? = null

    private fun discrete(): Sample = Sample(BooleanArray(problem.numBoolVars) { cp.session.boolValue(it) ?: false },
        LongArray(problem.numIntVars) { cp.session.intDomain(it).min })

    override fun check(context: SearchContext): ComponentCheck {
        if (context.cancelled()) return ComponentCheck.Indeterminate
        val cursor = pending ?: LpSolveCursor { checkLeaf(context) }.also { pending = it }
        val allowance = slice?.remainingLpWork?.invoke()
        if (allowance == 0L) return ComponentCheck.Paused
        return when (val step = cursor.advance(allowance ?: 0L, { allowance == null && context.cancelled() })) {
            LpSolveStep.Paused -> ComponentCheck.Paused
            is LpSolveStep.Completed -> {
                pending = null
                cursor.close()
                if (cancellation()) ComponentCheck.Indeterminate else step.value
            }
        }
    }

    private suspend fun LpSolveCursor<ComponentCheck>.checkLeaf(context: SearchContext): ComponentCheck {
        completed = null
        if (context.cancelled()) return ComponentCheck.Indeterminate
        absCompletion?.let { completion ->
            if (toleranceCheck != null && floatCompletion) {
                await(engine.openLeafFloatCompletion(cp.session, toleranceCheck))?.let { sample ->
                    completed = sample
                    return ComponentCheck.Feasible
                }
                if (context.cancelled()) return ComponentCheck.Indeterminate
            }
            val discrete = discrete()
            val stop = context.executionControls.cancellation
            // Within tolerance the search over the source decides the leaf. Where it cannot, an exact completion is
            // still a point within tolerance, but an exact refutation decides nothing about the region within the
            // slack.
            toleranceSearch?.let { search ->
                when (val decided = withinTolerance(search, discrete, context)) {
                    ToleranceLeaf.Refuted -> return ComponentCheck.Infeasible()
                    ToleranceLeaf.Undecided -> return ComponentCheck.Indeterminate
                    ToleranceLeaf.Unavailable -> Unit
                    is ToleranceLeaf.Completed -> {
                        completed = decided.sample
                        return ComponentCheck.Feasible
                    }
                }
            }
            val checked = completion.complete(discrete, stop, context.executionControls, solveContext)
            absWork += checked.work
            val exact = (checked.result as? AbsRealCompletion.Result.Witness)?.let { result ->
                discrete.copy(reals = DoubleArray(result.reals.size) { result.reals[it].toDouble() },
                    exactReals = result.reals,
                ).takeIf { verifyRealCoordinates(problem, it, stop) is Verification.Accepted }
            }
            exact?.let { sample ->
                completed = sample
                return ComponentCheck.Feasible
            }
            if (checked.result == AbsRealCompletion.Result.Refuted && toleranceSearch == null) {
                return ComponentCheck.Infeasible()
            }
            // The relaxation refuted over every side widened by the slack holds no point within tolerance either.
            val refuted = toleranceSearch != null &&
                await(engine.openLeafCertify(cp.session, toleranceCheck, poolRefutation = false)).refutesWithinSlack
            return if (refuted) ComponentCheck.Infeasible() else ComponentCheck.Indeterminate
        }
        val result = await(engine.openLeafCertify(cp.session, toleranceCheck, poolRefutation = toleranceSearch == null))
        return when (result.verdict) {
            LpVerdict.FEASIBLE,
            LpVerdict.ATTAINED_OPTIMUM,
            LpVerdict.TOLERANCE_OPTIMUM,
            LpVerdict.UNBOUNDED,
            -> {
                val sample = Sample(
                    BooleanArray(problem.numBoolVars) { cp.session.boolValue(it) ?: false },
                    LongArray(problem.numIntVars) { cp.session.intDomain(it).min },
                    result.reals,
                    result.exactReals,
                )
                val tolerated = result.verdict == LpVerdict.TOLERANCE_OPTIMUM && toleranceCheck?.invoke(sample) == true
                if ((problem as? BakedProblem)?.factorProjections?.requiresRealSourceCheck == true && !tolerated &&
                    verifyRealCoordinates(problem, sample) !is Verification.Accepted
                ) ComponentCheck.Indeterminate
                else ComponentCheck.Feasible.also { completed = sample }
            }

            // A refutation over the sides widened by the slack holds within the tolerance. A refused float point and
            // any other LP refutation leave the decision to the search within tolerance, and that refutation decides
            // nothing about the region within the slack where the search cannot run.
            LpVerdict.INFEASIBLE, LpVerdict.INDETERMINATE, LpVerdict.CERTIFIED_BOUND -> {
                if (result.refutesWithinSlack) return ComponentCheck.Infeasible()
                val search = toleranceSearch ?: return if (result.verdict == LpVerdict.INFEASIBLE) {
                    ComponentCheck.Infeasible()
                } else ComponentCheck.Indeterminate
                when (val decided = withinTolerance(search, discrete(), context)) {
                    ToleranceLeaf.Refuted -> ComponentCheck.Infeasible()
                    ToleranceLeaf.Undecided, ToleranceLeaf.Unavailable -> ComponentCheck.Indeterminate
                    is ToleranceLeaf.Completed -> ComponentCheck.Feasible.also { completed = decided.sample }
                }
            }
        }
    }

    private sealed interface ToleranceLeaf {
        data object Refuted : ToleranceLeaf
        data object Undecided : ToleranceLeaf
        data object Unavailable : ToleranceLeaf
        class Completed(val sample: Sample) : ToleranceLeaf
    }

    // The leaf decided by the search within tolerance. A source the search cannot state, or a witness acceptance
    // refuses, is unavailable: it decides nothing.
    private fun withinTolerance(search: ToleranceRealSearch, discrete: Sample, context: SearchContext): ToleranceLeaf {
        val stop = context.executionControls.cancellation
        val found = search.complete(discrete, stop, context.executionControls, solveContext)
        absWork += found.work
        return when (found) {
            is ToleranceSearchResult.Refuted -> ToleranceLeaf.Refuted
            is ToleranceSearchResult.Undecided -> ToleranceLeaf.Undecided
            ToleranceSearchResult.Unavailable -> ToleranceLeaf.Unavailable
            is ToleranceSearchResult.Witness -> discrete.copy(
                reals = DoubleArray(found.reals.size) { found.reals[it].toDouble() }, exactReals = found.reals,
            ).takeIf { toleranceCheck?.invoke(it) == true }?.let(ToleranceLeaf::Completed)
                ?: if (stop()) ToleranceLeaf.Undecided else ToleranceLeaf.Unavailable
        }
    }

    override fun retract(decisionLevel: Int) {
        val cursor = pending
        pending = null
        cursor?.close()
        completed = null
    }

    fun sample(): Sample = requireNotNull(completed)

    override fun releasePersistentSolvers() = release(engine::releasePersistentSolvers)

    override fun totalSolveWork(): Long = engine.totalSolveWork() + absWork

    override fun close() = release(engine::close)

    @Suppress("TooGenericExceptionCaught") // cursor and engine cleanup must preserve the primary failure
    private fun release(releaseEngine: () -> Unit) {
        var failure: Throwable? = null
        try {
            retract(0)
        } catch (cleanup: Throwable) {
            failure = cleanup
        }
        try {
            releaseEngine()
        } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
        failure?.let { throw it }
    }
}
