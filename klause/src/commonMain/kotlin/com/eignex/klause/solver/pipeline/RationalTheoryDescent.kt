package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class RationalTheoryDescent(
    private val source: Problem,
    private val objective: LinearObjective,
    private val params: TheoryParams,
    private val external: () -> Pair<Sample, BigFraction>? = { null },
) : ResumableSearch {
    private val state = OpenTheorySolveState(params)
    private var round: ResumableOpenTheory? = null
    private var incumbent: Sample? = null
    private var value: BigFraction? = null
    private var nativeStats = SolveStats.EMPTY
    private var result: MinimizeResult? = null
    private var closed = false
    override val isDone: Boolean get() = result != null
    override val stats: SolveStats get() = nativeStats.copy(openTheory = state.work.snapshot(),
        search = state.progress(null))
    override val work: Long get() = state.work.spent

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        result?.let { return it }
        check(!closed)
        val deadline = if (sliceMillis == Long.MAX_VALUE) null
            else TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
        val endWork = if (sliceNodes < 0) Long.MAX_VALUE else work + minOf(sliceNodes, Long.MAX_VALUE - work)
        var advanced = false
        while (!(global or params.cancellation or params.timeout)()) {
            if (advanced && (work >= endWork || deadline?.hasPassedNow() == true)) return null
            val current = round ?: run {
                external()?.let { (sample, exact) ->
                    if (value == null || exact < requireNotNull(value)) { incumbent = sample; value = exact }
                }
                val model = value?.let { source.withFactors(source.factors + objective.below(it),
                    source.impliedFactorMask?.let { mask -> mask + false }) } ?: source
                val route = model.componentPlan().theoryPipeline
                if (route == ProblemPipeline.UNSUPPORTED_OPEN || route == ProblemPipeline.FINITE_CP) {
                    return finish(TerminationReason.Unsupported)
                }
                val engine = OpenTheoryEngine(model, route, PresolveConfig.NONE, true, Cancellation.Never, null)
                ResumableOpenTheory(engine, params.copy(
                    maxLeaves = (params.maxLeaves - state.work.snapshot().openTheoryChecks).coerceAtLeast(0)), state)
                    .also { round = it }
            }
            val remainingMillis = deadline?.let {
                (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds.coerceAtLeast(0)
            }
                ?: Long.MAX_VALUE
            val verdict = current.runSlice(global, remainingMillis, (endWork - work).coerceAtLeast(0)) ?: return null
            advanced = true
            nativeStats = verdict.stats.copy(lp = nativeStats.lp.mergedWith(verdict.stats.lp))
            round = null
            current.close()
            when (verdict) {
                is OpenTheoryResult.Sat -> {
                    val sample = verdict.assignment.toSample(source)
                    val exact = objective.evaluateExact(sample)
                    check(value == null || exact < requireNotNull(value))
                    incumbent = sample
                    value = exact
                    onIncumbent(MinimizeResult.BestFound(
                        sample, exact.toDouble(), TerminationReason.BudgetExhausted, stats,
                    ))
                }
                is OpenTheoryResult.Unsat -> {
                    result = incumbent?.let { MinimizeResult.Optimal(it, requireNotNull(value).toDouble(), stats) }
                        ?: MinimizeResult.Infeasible(stats = stats)
                    return result
                }
                is OpenTheoryResult.Unknown -> return finish(verdict.reason)
            }
        }
        return finish(if (params.timeout()) TerminationReason.Timeout else TerminationReason.Cancelled)
    }

    private fun finish(reason: TerminationReason): MinimizeResult {
        result = incumbent?.let { MinimizeResult.BestFound(it, requireNotNull(value).toDouble(), reason, stats) }
            ?: MinimizeResult.Unknown(reason, stats)
        return requireNotNull(result)
    }

    override fun close() {
        round?.let { pending -> nativeStats = pending.stats.copy(lp = nativeStats.lp.mergedWith(pending.stats.lp)) }
        round?.close()
        round = null
        closed = true
    }
}

internal fun MinimizeResult.openOptimum(objective: LinearObjective): OpenTheoryOptimum = when (this) {
    is MinimizeResult.Optimal -> OpenTheoryOptimum.Optimal(OpenTheoryAssignment.Sampled(sample),
        objective.evaluateExact(sample), stats)
    is MinimizeResult.BestFound -> OpenTheoryOptimum.Bounded(OpenTheoryAssignment.Sampled(sample),
        objective.evaluateExact(sample), reason, stats)
    is MinimizeResult.Infeasible -> OpenTheoryOptimum.Infeasible(stats)
    is MinimizeResult.Unknown -> OpenTheoryOptimum.Bounded(null, null, reason, stats)
    is MinimizeResult.Unbounded -> error("rational feasibility descent supplies no recession certificate")
}
