package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LinearOptimizationResult
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
    private val context: SourceProofContext? = source.linearAuthorityContext(
        objective, ObjectiveSense.MINIMIZE, params.cancellation or params.timeout),
    private val external: () -> Pair<Sample, BigFraction>? = { null },
) : ResumableSearch {
    private val linear = context?.let { LinearAuthorityInvocation(it, params, external = { external()?.first }) }
    var linearResult: LinearOptimizationResult? = null
        private set
    private val state = OpenTheorySolveState(params)
    private var round: ResumableOpenTheory? = null
    private var incumbent: Sample? = null
    private var value: BigFraction? = null
    private var nativeStats = SolveStats.EMPTY
    private var result: MinimizeResult? = null
    private var closed = false
    override val isDone: Boolean get() = result != null
    override val stats: SolveStats get() = linear?.stats ?: nativeStats.copy(openTheory = state.work.snapshot(),
        search = state.progress(null))
    override val work: Long get() = linear?.work ?: state.work.spent

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        result?.let { return it }
        check(!closed)
        linear?.let { invocation ->
            val sign = if (context?.scope?.objective?.sense == ObjectiveSense.MAXIMIZE) BigFraction.MINUS_ONE
                else BigFraction.ONE
            val completed = invocation.runSlice(global, sliceMillis, sliceNodes) { sample, exact ->
                onIncumbent(MinimizeResult.BestFound(sample, (exact * sign).toDouble(),
                    TerminationReason.BudgetExhausted, stats))
            } ?: return null
            val sourceContext = requireNotNull(context)
            val authority = LinearOptimizationResult(sourceContext.scope, completed.terminal, completed.bound,
                completed.incumbent, completed.value, completed.reason, completed.stats)
            linearResult = authority
            val sample = authority.incumbent
            val exact = authority.value?.let { it * sign }
            result = when (val claim = authority.terminal?.claim) {
                is SourceClaim.Optimal -> MinimizeResult.Optimal(claim.sample, requireNotNull(exact).toDouble(),
                    stats, authority.evidence)
                SourceClaim.Refutation -> MinimizeResult.Infeasible(stats = stats, evidence = authority.evidence)
                else -> {
                    // Scalar worker completion cannot express a limit or a lattice ray.
                    val reason = if (authority.terminal != null) TerminationReason.Unsupported else authority.reason
                    sample?.let { MinimizeResult.BestFound(it, requireNotNull(exact).toDouble(), reason,
                        stats, authority.evidence) } ?: MinimizeResult.Unknown(reason, stats, authority.evidence)
                }
            }
            return result
        }
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
        linear?.close()
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
