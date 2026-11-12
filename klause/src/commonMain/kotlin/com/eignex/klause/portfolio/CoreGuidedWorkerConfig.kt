package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.meta.coreguided.CoreGuidedSearch
import com.eignex.klause.meta.coreguided.CoreGuidedSolver
import com.eignex.klause.meta.coreguided.CoreRefinementMode
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SharingChannel

internal class CoreGuidedWorkerConfig(
    private val nodeBudget: NodeBudget? = null,
    private val maximumWork: Long? = null,
    private val maximumMillis: Long? = null,
    private val coreRefinement: CoreRefinementMode = CoreRefinementMode.NONE,
    private val edit: ((BacktrackParams) -> BacktrackParams)? = null,
) : WorkerConfig {
    override val label: String get() = "core-guided"
    override fun withNodeBudget(budget: NodeBudget): WorkerConfig =
        CoreGuidedWorkerConfig(budget, maximumWork, maximumMillis, coreRefinement, edit)

    override fun withCoreRefinement(mode: CoreRefinementMode): CoreGuidedWorkerConfig =
        CoreGuidedWorkerConfig(nodeBudget, maximumWork, maximumMillis, mode, edit)

    /** Bounds the worker's active time. Under [uniformCosts] every core raises the floor by the same step over
     *  a short cost range, so the worker converges and keeps a longer lifetime; nonuniform costs split weights
     *  and take the time from the incumbent search. */
    internal fun withActiveTimeLimit(uniformCosts: Boolean): CoreGuidedWorkerConfig = CoreGuidedWorkerConfig(
        nodeBudget,
        maximumWork,
        if (uniformCosts) UNIFORM_ACTIVE_MILLIS else ACTIVE_MILLIS,
        coreRefinement,
        edit,
    )

    internal fun withWorkLimit(limit: Long): CoreGuidedWorkerConfig =
        CoreGuidedWorkerConfig(nodeBudget, limit, null, coreRefinement, edit)

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
        val sourceObjective = requireNotNull(objective)
        val identity = ModelIdentity.of(problem, sourceObjective)
        val matched = pools?.takeIf { it.identity?.matches(identity) != false }
        val workerLabel = "bt/$label"
        val event = onEvent?.let { sink -> { value: SearchEvent -> sink(workerLabel, value) } }
        val base = BacktrackPresets.satOptimized(seed + 1000L + index, inprocess = false, onEvent = event)
        var params = (edit?.invoke(base) ?: base).copy(nodeBudget = nodeBudget)
        lateinit var worker: PortfolioWorker
        val meter = SharingMeter()
        matched?.bounds?.let { bounds ->
            params = params.copy(objectiveLowerBoundSink = { value ->
                val invocation = worker.evidenceInvocation?.takeIf { worker.sharingInvocation != null }
                if (invocation != null) {
                    meter.exported(SharingChannel.Floor, 1)
                    val raise = bounds.publish(CompletedEnginePublication.ObjectiveBound(invocation, value))
                    matched.contributions.note(Contribution.Floor, index, raise)
                }
            })
        }
        worker = PortfolioWorker.of(
            workerLabel,
            armId,
            CoreGuidedSolver(problem, maximumWork, maximumMillis, coreRefinement).session(),
            params,
            sourceObjective,
        ).bindEvidence(identity, provesResults = CoreGuidedSearch.scalarProofs(problem, sourceObjective)).also {
            it.auxiliaryProof = maximumWork != null || maximumMillis != null
            it.sharedPools = matched
            it.sharingMeter = meter
            it.sharing = if (matched?.bounds != null) setOf(Contribution.Floor) else emptySet()
        }
        return worker
    }
}

private const val ACTIVE_MILLIS = 2_000L
private const val UNIFORM_ACTIVE_MILLIS = 12_000L

/** Whether every nonzero Boolean and integer cost has the same magnitude. */
internal val LinearObjective.uniformCosts: Boolean
    get() {
        var magnitude = 0L
        for (cost in boolWeightValues.asSequence() + intCoefficientValues.asSequence()) {
            if (cost == 0L) continue
            val size = if (cost < 0L) -cost else cost
            if (magnitude == 0L) magnitude = size else if (size != magnitude) return false
        }
        return magnitude != 0L
    }
