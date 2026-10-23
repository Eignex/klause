package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.ResumableSatisfaction
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBranchSelection
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchRestart
import com.eignex.klause.solver.search.SearchRun
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.SearchStopCause
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException

internal class ClausalTheorySearch(
    private val source: Problem,
    clauses: List<Clause>,
    private val params: TheoryParams,
    private val state: OpenTheorySolveState,
    private val root: SearchSession,
    private val cancellation: Cancellation,
    private val nodePolicy: SearchNodePolicy,
    private val branchSelection: SearchBranchSelection,
    private val open: (BooleanArray) -> PlannedSearch,
) : AutoCloseable {
    private val clauses = clauses.toMutableList()
    private var booleanSearch: ResumableSolve? = null
    private var booleanBudget: NodeBudget? = null
    private var chargedBooleanWork = 0L
    private var cube: BooleanArray? = null
    private var completion: PlannedSearch? = null
    private var traversal: SearchRun? = null
    private var rootCaptured = false
    private var terminal: SearchRunEvent? = null
    private var closed = false

    var stopCause: SearchStopCause? = null
        private set

    val session: SearchSession get() = completion?.session ?: root
    val statsSession: SearchSession? get() = completion?.session ?: root.takeUnless { rootCaptured }

    private val booleanStop = object : Cancellation {
        override fun isCancelled(): Boolean = cancellation()
        override fun deadline() = cancellation.deadline()
        override fun workMeter() = cancellation.workMeter()
    }

    fun next(): SearchRunEvent {
        terminal?.let { return it }
        check(!closed)
        while (true) {
            stopped()?.let { return finish(it) }
            if (cube == null) {
                when (nodePolicy.beforeBranch(root)) {
                    SearchNodeDisposition.Pause -> return SearchRunEvent.Paused
                    SearchNodeDisposition.Prune -> return finish(SearchRunEvent.Exhausted)
                    SearchNodeDisposition.Indeterminate, is SearchNodeDisposition.Backjump -> {
                        stopCause = SearchStopCause.RepresentationDecline
                        return finish(SearchRunEvent.Indeterminate.Component)
                    }
                    SearchNodeDisposition.Expand -> Unit
                }
                val search: ResumableSolve
                val result: SolveResult?
                try {
                    search = booleanSearch ?: openBooleanSearch()
                    result = search.runSlice(booleanStop, Long.MAX_VALUE, 1)
                } catch (_: CancellationException) {
                    return finish(stopped(includeResources = true) ?: SearchRunEvent.Indeterminate.Cancelled)
                }
                chargeBooleanWork()
                state.observeClauseProgress(search.stats.search)
                when (result) {
                    null -> if (nodePolicy !== SearchNodePolicy.ExpandAll) return SearchRunEvent.Paused else continue
                    is SolveResult.Unsat -> {
                        observe("CLAUSAL_BOOLEAN_UNSAT")
                        closeBooleanSearch()
                        return finish(SearchRunEvent.Exhausted)
                    }
                    is SolveResult.Unknown -> {
                        observe("CLAUSAL_BOOLEAN_UNKNOWN")
                        closeBooleanSearch()
                        stopped(includeResources = true)?.let { return finish(it) }
                        stopCause = SearchStopCause.RepresentationDecline
                        return finish(SearchRunEvent.Indeterminate.Component)
                    }
                    is SolveResult.Sat -> {
                        cube = BooleanArray(source.numBoolVars) { result.assignment.boolValue(it) }
                        observe("CLAUSAL_BOOLEAN_SAT")
                        closeBooleanSearch()
                    }
                }
            }
            if (completion == null) {
                if (!rootCaptured) {
                    state.capture(root)
                    rootCaptured = true
                }
                val planned = try {
                    open(checkNotNull(cube))
                } catch (_: CancellationException) {
                    return finish(stopped(includeResources = true) ?: SearchRunEvent.Indeterminate.Cancelled)
                }
                completion = planned
                observe("CLAUSAL_CUBE_OPENED")
                when (planned.session.initialize()) {
                    is ComponentResult.Conflict -> {
                        refuteCube()
                        continue
                    }
                    ComponentResult.Indeterminate -> {
                        stopCause = planned.session.executionControls.stopCause
                        return finish(SearchRunEvent.Indeterminate.Component)
                    }
                    ComponentResult.Consistent -> Unit
                }
                traversal = planned.session.openRun(
                    source.numBoolVars,
                    SearchSolveParams(
                        maxDecisions = state.remainingDecisions(),
                        restart = params.sharedRestart?.let(SearchRestart::Every) ?: SearchRestart.Never,
                    ),
                    booleanBranching = BooleanBranching.SourceOrder(source.numBoolVars),
                    decisionBudget = state.decisionBudget,
                    nodePolicy = nodePolicy,
                    candidateHints = planned.candidateHints,
                    branchSelection = branchSelection,
                )
            }
            when (val result = checkNotNull(traversal).next()) {
                SearchRunEvent.Exhausted -> refuteCube()
                SearchRunEvent.Paused -> return result
                is SearchRunEvent.Satisfied -> return finish(result)
                is SearchRunEvent.Indeterminate -> {
                    stopCause = checkNotNull(completion).session.executionControls.stopCause
                    observe("CLAUSAL_ARITHMETIC_UNKNOWN")
                    return finish(result)
                }
            }
        }
    }

    private fun openBooleanSearch(): ResumableSolve {
        val maximum = minOf(state.remainingDecisions(), state.work.remaining())
        val budget = params.nodeBudget?.boundedPhase(maximum) ?: NodeBudget(maximum)
        booleanBudget = budget
        chargedBooleanWork = 0L
        val model = Problem(
            settings = source.settings,
            semantics = source.semantics,
            numBoolVars = source.numBoolVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = clauses.toList(),
        )
        observe("CLAUSAL_BOOLEAN_OPENED")
        return ResumableSatisfaction(BacktrackSolver(model.bake(booleanStop)),
            BacktrackParams(
                maxDecisions = maximum,
                randomSeed = params.randomSeed,
                nodeBudget = budget,
                nativeSat = true,
                cancellation = booleanStop,
            ), ownsWorkDebt = false,
        ).also { booleanSearch = it }
    }

    private fun chargeBooleanWork() {
        val spent = booleanBudget?.spent ?: return
        state.work.clauseWork(spent - chargedBooleanWork)
        chargedBooleanWork = spent
    }

    private fun closeBooleanSearch() {
        val search = booleanSearch ?: return
        chargeBooleanWork()
        state.finishClauseProgress(search.stats.search)
        booleanSearch = null
        booleanBudget = null
        search.close()
    }

    private fun refuteCube() {
        // Only a complete conditioned-source refutation licenses this entire cube's exclusion.
        // Root axioms can omit assumption dependencies, so a smaller raw conflict is insufficient.
        val values = checkNotNull(cube)
        clauses += Clause(IntArray(values.size) { Lit.make(it, !values[it]) })
        observe("CLAUSAL_CUBE_REFUTED")
        val previous = checkNotNull(completion)
        state.capture(previous.session)
        completion = null
        traversal = null
        cube = null
        previous.close()
    }

    private fun stopped(includeResources: Boolean = false): SearchRunEvent.Indeterminate? {
        stopCause = when {
            params.timeout() -> SearchStopCause.Timeout
            cancellation() -> SearchStopCause.Cancelled
            includeResources && state.work.exhausted -> SearchStopCause.WorkAllowance
            includeResources && (params.nodeBudget?.exhausted() == true || state.remainingDecisions() == 0L) ->
                SearchStopCause.DecisionAllowance
            else -> return null
        }
        return if (stopCause == SearchStopCause.Timeout || stopCause == SearchStopCause.Cancelled) {
            SearchRunEvent.Indeterminate.Cancelled
        } else SearchRunEvent.Indeterminate.Budget
    }

    private fun observe(key: String) = state.smt.observeCompleteSearchStage(key)

    private fun finish(result: SearchRunEvent): SearchRunEvent = result.also { terminal = it }

    @Suppress("TooGenericExceptionCaught") // Release both private phases while preserving their primary failure.
    override fun close() {
        if (closed) return
        closed = true
        val resources = SearchInvocationResources()
        completion?.let(resources::own)
        booleanSearch?.let(resources::own)
        var failure: Throwable? = null
        try {
            booleanSearch?.let {
                chargeBooleanWork()
                state.finishClauseProgress(it.stats.search)
            }
        } catch (primary: Throwable) {
            failure = primary
            throw primary
        } finally {
            booleanSearch = null
            booleanBudget = null
            completion = null
            traversal = null
            resources.closeAfter(failure)
        }
    }
}
