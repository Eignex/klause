package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.HeuristicBooleanBranching
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.Vsids
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal data class LinearInvocationResult(
    val terminal: SourceEvidence?,
    val bound: SourceEvidence?,
    val incumbent: Sample?,
    val value: BigFraction?,
    val reason: TerminationReason,
    val stats: SolveStats,
)

internal class LinearAuthorityInvocation(
    private val context: SourceProofContext,
    private val params: TheoryParams,
    private val external: () -> Sample? = { null },
) : AutoCloseable {
    private val state = OpenTheorySolveState(params)
    private val started = TimeSource.Monotonic.markNow()
    private var closed = false
    private var currentStop = params.timeout or params.cancellation
    private val stop = object : Cancellation {
        override fun isCancelled(): Boolean = closed || currentStop()
        override fun deadline() = currentStop.deadline()
        override fun workMeter() = currentStop.workMeter()
    }
    private var sliceEnd: ComparableTimeMark? = null
    private var endWork = Long.MAX_VALUE
    private var startWork = 0L
    private var callback: (Sample, BigFraction) -> Unit = { _, _ -> }
    private var incumbent: Sample? = null
    private var value: BigFraction? = null
    private var result: LinearInvocationResult? = null
    val isDone: Boolean get() = result != null
    val work: Long get() = state.work.spent
    val stats: SolveStats get() = SolveStats(
        run = SolveStats.EMPTY.run.copy(backend = "linear-authority",
            wallMs = started.elapsedNow().inWholeMilliseconds, timedOut = params.timeout()),
        smt = state.smt.snapshot(), openTheory = state.work.snapshot())
    private val component = LinearAuthorityTraversal(context,
        SourceLpBudget(onWork = state.smt::observeSourceLp), stop, ::paused, ::offer)
    private val session = SearchComponentSet(listOf(component)).session(params.maxLeaves, stop).also {
        it.attachOpenTheoryWork(state.work)
    }
    private var initialized = false
    private val branching = if (params.openBranching == OpenBranching.SourceOrder)
        BooleanBranching.SourceOrder(context.coordinates.count {
            it.kind == com.eignex.klause.solver.evidence.SourceCoordinate.Kind.Boolean
        }) else HeuristicBooleanBranching(Vsids(), context.coordinates.count {
            it.kind == com.eignex.klause.solver.evidence.SourceCoordinate.Kind.Boolean
        })
    private val run = session.openRun(context.coordinates.count {
        it.kind == com.eignex.klause.solver.evidence.SourceCoordinate.Kind.Boolean
    }, SearchSolveParams(params.maxDecisions), booleanBranching = branching, nodePolicy = component)

    @Suppress("TooGenericExceptionCaught") // Callback failure retires the invocation and preserves cleanup failures.
    fun runSlice(
        cancellation: Cancellation,
        millis: Long,
        work: Long,
        onIncumbent: (Sample, BigFraction) -> Unit,
    ): LinearInvocationResult? {
        result?.let { return it }
        check(!closed)
        currentStop = params.timeout or params.cancellation or cancellation
        sliceEnd = if (millis == Long.MAX_VALUE) null else TimeSource.Monotonic.markNow() + millis.milliseconds
        startWork = this.work
        endWork = if (work < 0) Long.MAX_VALUE else this.work + minOf(work, Long.MAX_VALUE - this.work)
        callback = onIncumbent
        try {
            external()?.let(::offer)
            if (stop()) return finish(null, if (params.timeout()) TerminationReason.Timeout
                else TerminationReason.Cancelled)
            if (!initialized) {
                initialized = true
                if (session.initialize() !is ComponentResult.Consistent) {
                    return finish(null, TerminationReason.Unsupported)
                }
            }
            val event = run.next()
            component.ray?.let { return finish(it, TerminationReason.SearchExhausted) }
            return when (event) {
                SearchRunEvent.Paused -> null
                SearchRunEvent.Exhausted -> {
                    val terminal = component.completed()
                    finish(terminal, if (terminal == null) TerminationReason.Unsupported
                        else TerminationReason.SearchExhausted)
                }
                is SearchRunEvent.Indeterminate -> finish(null, when {
                    params.timeout() -> TerminationReason.Timeout
                    stop() -> TerminationReason.Cancelled
                    session.workExhausted() || session.checkBudgetExhausted() ||
                        session.decisionBudgetExhausted() -> TerminationReason.BudgetExhausted
                    else -> TerminationReason.Unsupported
                })
                is SearchRunEvent.Satisfied ->
                    error("linear authority resolves an optimized node before model assembly")
            }
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        } finally {
            callback = { _, _ -> }
            currentStop = params.timeout or params.cancellation
        }
    }

    private fun paused(): Boolean = work > startWork && (work >= endWork || sliceEnd?.hasPassedNow() == true)

    private fun offer(sample: Sample) {
        if (stop()) return
        if (sample.exactReals == null && context.objective.keys.any {
                it.kind == com.eignex.klause.solver.evidence.SourceCoordinate.Kind.Real
            }
        ) return
        val checked = SourceEvidenceVerifier(context.withLinearAuthority()).verify(SourceEvidence(context.scope,
            SourceClaim.Witness(sample), SourceDerivation.SourceWitness), stop)
        if (checked != SourceEvidenceCheck.Checked) return
        var exact = context.objectiveConstant
        for ((coordinate, weight) in context.objective) {
            val scalar = when (coordinate.kind) {
                com.eignex.klause.solver.evidence.SourceCoordinate.Kind.Boolean ->
                    if (sample.boolValue(coordinate.id)) BigFraction.ONE else BigFraction.ZERO
                com.eignex.klause.solver.evidence.SourceCoordinate.Kind.Integer ->
                    BigFraction.of(sample.exactIntValue(coordinate.id), BIG_ONE)
                com.eignex.klause.solver.evidence.SourceCoordinate.Kind.Real ->
                    requireNotNull(sample.exactReals)[coordinate.id]
            }
            exact += weight * scalar
        }
        val standing = value
        val minimizes = context.scope.objective?.sense == ObjectiveSense.MINIMIZE
        if (standing != null && if (minimizes) exact >= standing else exact <= standing) return
        incumbent = sample
        value = exact
        callback(sample, exact)
    }

    private fun finish(terminal: SourceEvidence?, reason: TerminationReason): LinearInvocationResult {
        val accepted = terminal?.takeUnless { stop() }
        val stopped = if (terminal != null && accepted == null) {
            if (params.timeout()) TerminationReason.Timeout else TerminationReason.Cancelled
        } else reason
        result = LinearInvocationResult(accepted, component.rootBound, incumbent, value, stopped, stats)
        return requireNotNull(result)
    }

    override fun close() {
        if (closed) return
        closed = true
        session.popTo(0)
        callback = { _, _ -> }
    }
}
