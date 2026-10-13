package com.eignex.klause.solver.incumbent

import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceResultEvidence
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason

internal sealed interface CompletedEngineResult {
    val scope: SourceScope?
    val invocation: EngineInvocation?
    val evidence: SourceResultEvidence?
    fun attests(kind: EvidenceKind): Boolean

    data class Solve(
        override val scope: SourceScope?, val result: SolveResult,
        override val invocation: EngineInvocation? = null,
    ) : CompletedEngineResult {
        constructor(invocation: EngineInvocation, result: SolveResult) : this(invocation.scope, result, invocation)

        init {
            require(scope != null || invocation != null) { "completion requires an actual invocation binding" }
            require(invocation == null || scope == invocation.scope) { "completion has a different capture scope" }
        }
        override val evidence: SourceResultEvidence? get() = result.evidence
        override fun attests(kind: EvidenceKind): Boolean = when (kind) {
            EvidenceKind.Witness -> result is SolveResult.Sat
            EvidenceKind.Infeasible -> result is SolveResult.Unsat
            EvidenceKind.Bound, EvidenceKind.Unbounded -> false
        }
    }

    class Minimize(
        override val scope: SourceScope?,
        result: MinimizeResult,
        val cutoff: SourceClaim.Bound? = null,
        override val invocation: EngineInvocation? = null,
        val executionCutoff: Double? = null,
    ) : CompletedEngineResult {
        private val capturedResult = snapshot(result)
        val result: MinimizeResult get() = snapshot(capturedResult)
        constructor(
            invocation: EngineInvocation, result: MinimizeResult,
            executionCutoff: Double? = null, cutoff: SourceClaim.Bound? = null,
        ) : this(invocation.scope, result, cutoff, invocation, executionCutoff)

        init {
            require(scope != null || invocation != null) { "completion requires an actual invocation binding" }
            require(invocation == null || scope == invocation.scope) { "completion has a different capture scope" }
            require(executionCutoff == null || executionCutoff.isFinite()) { "exhausted cutoff must be finite" }
        }
        override val evidence: SourceResultEvidence? get() = result.evidence
        val executionBound: Double? get() = when (val actual = capturedResult) {
            is MinimizeResult.Optimal -> actual.objective
            else -> executionCutoff
        }
        override fun attests(kind: EvidenceKind): Boolean = when (kind) {
            EvidenceKind.Witness -> result is MinimizeResult.WithSample
            EvidenceKind.Infeasible -> result is MinimizeResult.Infeasible
            EvidenceKind.Unbounded -> result is MinimizeResult.Unbounded
            EvidenceKind.Bound -> when (val actual = capturedResult) {
                is MinimizeResult.Optimal -> true
                is MinimizeResult.BestFound -> (cutoff != null || executionCutoff != null) &&
                    actual.reason == TerminationReason.SearchExhausted
                is MinimizeResult.Unknown -> (cutoff != null || executionCutoff != null) &&
                    actual.reason == TerminationReason.SearchExhausted
                else -> false
            }
        }

        private fun snapshot(value: MinimizeResult): MinimizeResult = when (value) {
            is MinimizeResult.Unbounded -> value.copy(direction = value.direction.toList())
            else -> value
        }
    }
}
