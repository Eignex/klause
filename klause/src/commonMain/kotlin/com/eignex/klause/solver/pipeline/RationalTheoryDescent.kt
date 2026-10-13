package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.optimization.linearCoordinatesSupported
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceResultEvidence
import com.eignex.klause.solver.incumbent.CompletedEngineResult
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
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
    private val state = OpenTheorySolveState(params)
    private var linear = context?.let {
        LinearAuthorityInvocation(it, params, external = { external()?.first }, state = state)
    }
    private var proofDeclined = false
    private var checkedIncumbent: Sample? = null
    private var checkedValue: BigFraction? = null
    private val conditioned by lazy { conditionedSource() }
    private var witnessContext: SourceProofContext? = null
    var linearResult: LinearOptimizationResult? = null
        private set
    private var round: ResumableOpenTheory? = null
    private var incumbent: Sample? = null
    private var value: BigFraction? = null
    private var nativeStats = SolveStats.EMPTY
    private var result: MinimizeResult? = null
    private var closed = false
    override val isDone: Boolean get() = result != null
    override val stats: SolveStats get() = linear?.stats ?: nativeStats.copy(openTheory = state.work.snapshot(),
        smt = state.smt.snapshot(), search = round?.progress ?: state.progress(null))
    override val work: Long get() = linear?.work ?: state.work.spent

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        result?.let { return it }
        check(!closed)
        val startWork = work
        val deadline = if (sliceMillis == Long.MAX_VALUE) null
            else TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
        val endWork = if (sliceNodes < 0) Long.MAX_VALUE else work + minOf(sliceNodes, Long.MAX_VALUE - work)
        linear?.let { invocation ->
            val sign = if (context?.scope?.objective?.sense == ObjectiveSense.MAXIMIZE) BigFraction.MINUS_ONE
                else BigFraction.ONE
            val completed = invocation.runSlice(global, sliceMillis, sliceNodes) { sample, exact ->
                attestWitness(sample, global or params.timeout or params.cancellation)
                onIncumbent(MinimizeResult.BestFound(sample, (exact * sign).toDouble(),
                    TerminationReason.BudgetExhausted, stats))
            } ?: return null
            val sourceContext = requireNotNull(context)
            val authority = LinearOptimizationResult(sourceContext.scope, completed.terminal, completed.bound,
                completed.incumbent, completed.value, completed.reason, completed.stats)
            linearResult = authority
            if (authority.terminal == null && authority.reason == TerminationReason.Unsupported &&
                sourceContext.affineObjectiveAvailable && sourceContext.linearCoordinatesSupported()
            ) {
                nativeStats = completed.stats
                checkedIncumbent = authority.incumbent
                checkedValue = authority.value
                incumbent = authority.incumbent
                value = incumbent?.let(objective::evaluateExact)
                invocation.close()
                linear = null
                proofDeclined = true
            } else {
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
        }
        var advanced = work > startWork
        while (!(global or params.cancellation or params.timeout)()) {
            if (!state.work.hasAllowance() || state.work.snapshot().openTheoryChecks >= params.maxLeaves) {
                return finish(TerminationReason.BudgetExhausted)
            }
            if (advanced && (work >= endWork || deadline?.hasPassedNow() == true)) return null
            val current = round ?: run {
                external()?.let { (sample, _) ->
                    if (!proofDeclined || retainChecked(sample, global or params.timeout or params.cancellation)) {
                        val exact = objective.evaluateExact(sample)
                        if (value == null || exact < requireNotNull(value)) {
                            incumbent = sample
                            value = exact
                        }
                    }
                }
                val base = if (proofDeclined) conditioned else source
                val model = value?.let { base.withFactors(base.factors + objective.below(it),
                    base.impliedFactorMask?.let { mask -> mask + false }) } ?: base
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
                    val standing = checkedIncumbent
                    if (proofDeclined && !retainChecked(sample, global or params.timeout or params.cancellation)) {
                        return finish(if (params.timeout()) TerminationReason.Timeout
                            else if ((global or params.cancellation)()) TerminationReason.Cancelled
                            else TerminationReason.Unsupported)
                    }
                    incumbent = sample
                    value = exact
                    attestWitness(sample, global or params.timeout or params.cancellation)
                    if (proofDeclined) linearResult = partial(TerminationReason.BudgetExhausted)
                    if (!proofDeclined || checkedIncumbent !== standing) {
                        publish(MinimizeResult.BestFound(sample, exact.toDouble(),
                            TerminationReason.BudgetExhausted, stats), onIncumbent)
                    }
                }
                is OpenTheoryResult.Unsat -> {
                    if (proofDeclined) return finish(TerminationReason.Unsupported)
                    result = incumbent?.let { MinimizeResult.Optimal(it, requireNotNull(value).toDouble(), stats) }
                        ?: MinimizeResult.Infeasible(stats = stats)
                    return result
                }
                is OpenTheoryResult.Unknown -> return finish(verdict.reason)
            }
        }
        return finish(if (params.timeout()) TerminationReason.Timeout else TerminationReason.Cancelled)
    }

    fun linearSnapshot(reason: TerminationReason): LinearOptimizationResult? {
        if (proofDeclined) return partial(reason)
        linearResult?.let { return it }
        val captured = context ?: return null
        val snapshot = linear?.snapshot(reason) ?: return null
        return LinearOptimizationResult(captured.scope, snapshot.terminal, snapshot.bound,
            snapshot.incumbent, snapshot.value, snapshot.reason, snapshot.stats)
    }

    fun matchesInvocation(invocation: EngineInvocation): Boolean =
        invocation.preparedPlan.originalSource === source &&
            invocation.model.matches(ModelIdentity.of(source, objective)) &&
            invocation.executionObjective === objective &&
            invocation.scope == context?.scope &&
            context?.scope?.condition?.implies(invocation.executionCondition) == true

    private fun attestWitness(sample: Sample, cancellation: Cancellation) {
        val original = witnessContext ?: source.linearAuthorityContext(LinearObjective(), ObjectiveSense.MINIMIZE,
            cancellation)?.also { witnessContext = it } ?: return
        val evidence = SourceEvidence(original.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)
        if (SourceEvidenceVerifier(original.withLinearAuthority()).verify(evidence, cancellation) !=
            SourceEvidenceCheck.Checked
        ) return
        val result = SolveResult.Sat(sample, evidence = SourceResultEvidence(original.scope, listOf(evidence)))
        sample.witnessCertificate = EvidenceCertificate.completed(ModelIdentity.of(source), EvidenceKind.Witness,
            CompletedEngineResult.Solve(original.scope, result))
    }

    private fun retainChecked(sample: Sample, cancellation: Cancellation): Boolean {
        val original = requireNotNull(context)
        val evidence = SourceEvidence(original.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)
        if (SourceEvidenceVerifier(original.withLinearAuthority()).verify(evidence, cancellation) !=
            SourceEvidenceCheck.Checked
        ) return false
        val exact = original.linearValue(sample, cancellation) ?: return false
        val standing = checkedValue
        val minimizes = original.scope.objective?.sense == ObjectiveSense.MINIMIZE
        if (standing == null || if (minimizes) exact < standing else exact > standing) {
            checkedIncumbent = sample
            checkedValue = exact
        }
        return true
    }

    private fun conditionedSource(): Problem {
        val condition = context?.scope?.condition ?: return source
        val rows = ArrayList<Factor>()
        for ((id, value) in condition.bools) {
            rows += ExactSearchRow.of(intArrayOf(Term.ofLit(Lit.make(id, true))), listOf(BigFraction.ONE),
                LinearOp.EQ, if (value) BigFraction.ONE else BigFraction.ZERO)
        }
        fun bound(coordinate: SourceCoordinate, value: BigFraction, relation: LinearOp) {
            val ref = when (coordinate.kind) {
                SourceCoordinate.Kind.Integer -> Term.ofIntVar(coordinate.id)
                SourceCoordinate.Kind.Real -> Term.ofRealVar(coordinate.id)
                SourceCoordinate.Kind.Boolean -> error("numeric condition cannot restrict a Boolean coordinate")
            }
            rows += ExactSearchRow.of(intArrayOf(ref), listOf(BigFraction.ONE), relation, value)
        }
        condition.lower.forEach { (coordinate, value) -> bound(coordinate, value, LinearOp.GE) }
        condition.upper.forEach { (coordinate, value) -> bound(coordinate, value, LinearOp.LE) }
        return if (rows.isEmpty()) source else source.withFactors(source.factors + rows,
            source.impliedFactorMask?.let { it + BooleanArray(rows.size) })
    }

    private fun partial(reason: TerminationReason): LinearOptimizationResult = LinearOptimizationResult(
        requireNotNull(context).scope, null, linearResult?.bound, checkedIncumbent, checkedValue, reason, stats)

    @Suppress("TooGenericExceptionCaught") // Publication failure closes the retained search before propagating.
    private fun publish(candidate: MinimizeResult.WithSample, callback: (MinimizeResult.WithSample) -> Unit) {
        try {
            callback(candidate)
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun finish(reason: TerminationReason): MinimizeResult {
        if (proofDeclined) linearResult = partial(reason)
        val sample = if (proofDeclined) checkedIncumbent else incumbent
        result = sample?.let { MinimizeResult.BestFound(it, objective.evaluateExact(it).toDouble(), reason, stats) }
            ?: MinimizeResult.Unknown(reason, stats)
        return requireNotNull(result)
    }

    override fun close() {
        if (closed) return
        if (linearResult == null) {
            linearResult = linearSnapshot(if (params.timeout()) TerminationReason.Timeout
                else TerminationReason.Cancelled)
        }
        linear?.close()
        round?.let { pending -> nativeStats = pending.stats.copy(lp = nativeStats.lp.mergedWith(pending.stats.lp)) }
        round?.close()
        round = null
        if (proofDeclined && result == null) linearResult = partial(if (params.timeout()) TerminationReason.Timeout
            else TerminationReason.Cancelled)
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
