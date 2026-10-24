package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

internal class LinearCompanionSearch(
    private val search: ResumableSearch,
    context: SourceProofContext,
    private val binding: EngineInvocation,
    private val objective: LinearObjective,
    private val observations: () -> Map<String, Long> = { emptyMap() },
) : InstructionSlicedSearch, ReseedGuard, ResumableSearch by search {
    private val context = context.withLinearAuthority()
    init {
        if (binding.scope != context.scope || binding.executionObjective !== objective) {
            val failure = IllegalArgumentException("linear companion has another source scope or execution objective")
            runCatching { search.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    override val stats get() = search.stats.let { stats ->
        stats.copy(smt = stats.smt.copy(completeSearchStages =
            stats.smt.completeSearchStages.orEmpty() + observations()))
    }
    override val canReseed: Boolean get() = false
    override val chargedInstructions: Long get() =
        (search as? InstructionSlicedSearch)?.chargedInstructions ?: search.work

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? =
        advance(global, onIncumbent) { callback -> search.runSlice(global, sliceMillis, sliceNodes, callback) }

    override fun runInstructionSlice(global: Cancellation, sliceMillis: Long, sliceInstructions: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? =
        advance(global, onIncumbent) { callback ->
            val instructions = search as? InstructionSlicedSearch
            instructions?.runInstructionSlice(global, sliceMillis, sliceInstructions, callback)
                ?: if (instructions == null) search.runSlice(global, sliceMillis, sliceInstructions, callback) else null
        }

    private fun advance(global: Cancellation, onIncumbent: (MinimizeResult.WithSample) -> Unit,
        run: ((MinimizeResult.WithSample) -> Unit) -> MinimizeResult?): MinimizeResult? {
        fun checked(result: MinimizeResult.WithSample): MinimizeResult.BestFound? {
            val sample = result.sample
            val evidence = SourceEvidence(context.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness)
            if (SourceEvidenceVerifier(context).verify(evidence, global) != SourceEvidenceCheck.Checked) return null
            val published: Sample = sample.copy()
            val value = objective.evaluateExact(published).toDouble()
            published.witnessCertificate = EvidenceCertificate.published(
                CompletedEnginePublication.Witness(binding, published, value))
            return MinimizeResult.BestFound(published, value, TerminationReason.BudgetExhausted, stats)
        }
        val result = run { candidate -> checked(candidate)?.let(onIncumbent) } ?: return null
        return if (result is MinimizeResult.WithSample) checked(result)
            ?: MinimizeResult.Unknown(TerminationReason.Unsupported, stats)
        else MinimizeResult.Unknown(TerminationReason.Unsupported, stats)
    }
}
