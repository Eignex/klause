package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecisionBudget
import com.eignex.klause.solver.search.SearchRun
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.theory.qfnia.QuadraticIntegerComponent
import com.eignex.klause.theory.qfnia.QuadraticIntegerNode
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class QuadraticIntegerTraversalCursor(
    private val boolCount: Int,
    private val component: QuadraticIntegerComponent,
    private val params: SearchSolveParams,
    private val branching: BooleanBranching,
    private val decisionBudget: SearchDecisionBudget,
    private val lifetime: Cancellation,
    private val work: () -> Long,
) : AutoCloseable {
    private var global = lifetime
    private val session = SearchSession(
        listOf(component), cancellation = Cancellation { global() },
        branchers = listOf(component), atoms = SearchAtomRegistry(boolCount),
    )
    private var run: SearchRun? = null
    private var initialized = false
    private var terminal: SearchRunEvent? = null
    private var closed = false
    val isDone: Boolean get() = terminal != null
    val outcome: QuadraticIntegerNode? get() = component.outcome

    fun advance(
        cancellation: Cancellation,
        sliceMillis: Long,
        sliceWork: Long,
        onModel: (QuadraticIntegerNode.Closed) -> Unit,
    ): SearchRunEvent? {
        check(!closed) { "quadratic integer traversal cursor is closed" }
        terminal?.let { return it }
        global = lifetime or cancellation
        if (global()) {
            terminal = SearchRunEvent.Indeterminate.Cancelled
            return terminal
        }
        if (sliceWork == 0L || sliceMillis == 0L) return null
        val started = work()
        val deadline = if (sliceMillis == Long.MAX_VALUE) null
            else TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
        val paused = {
            deadline?.hasPassedNow() == true || (sliceWork >= 0 && work() - started >= sliceWork)
        }
        if (!initialized) {
            when (session.initialize()) {
                is ComponentResult.Conflict -> terminal = if (global()) SearchRunEvent.Indeterminate.Cancelled
                    else SearchRunEvent.Exhausted
                ComponentResult.Indeterminate -> terminal = SearchRunEvent.Indeterminate.Component
                ComponentResult.Consistent -> Unit
            }
            initialized = true
            terminal?.let { return it }
            run = session.openRun(boolCount, params, branching, decisionBudget, nodePolicy = component)
        }
        val cursor = requireNotNull(run)
        cursor.pauseBeforeDecision = paused
        cursor.fixedCancellationCadence = sliceWork >= 0
        while (!global() && !paused()) {
            when (val event = cursor.next()) {
                is SearchRunEvent.Satisfied -> onModel(requireNotNull(event.model.valueOf(component.modelKey)))
                SearchRunEvent.Paused -> {
                    if (component.outcome is QuadraticIntegerNode.Unbounded) terminal = event
                    return terminal
                }
                SearchRunEvent.Exhausted, is SearchRunEvent.Indeterminate -> {
                    terminal = event
                    return event
                }
            }
        }
        return null
    }

    override fun close() {
        if (closed) return
        closed = true
        run = null
        session.popTo(0)
    }
}
