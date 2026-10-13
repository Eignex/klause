@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.portfolio.PortfolioTerminalCheck
import com.eignex.klause.portfolio.PortfolioTerminalPublication
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class OpenLinearPortfolioResults(
    private val binding: EngineInvocation,
    private val original: SourceProofContext,
    private val values: OpenSourceObjective,
    private val incumbents: PortfolioIncumbents<OpenIncumbentValue>,
    private val cancellation: Cancellation,
    private val timeout: Cancellation = Cancellation.Never,
) : PortfolioTerminalCheck {
    private data class Accepted(val result: LinearOptimizationResult, val rayValue: BigFraction?)
    private val accepted = AtomicReference<Accepted?>(null)
    private val handles = ArrayList<Pair<PortfolioWorker, AtomicReference<LinearPortfolioSearch?>>>()

    fun retain(worker: PortfolioWorker, handle: AtomicReference<LinearPortfolioSearch?>) {
        handles += worker to handle
    }

    override fun check(worker: PortfolioWorker, search: ResumableSearch?): PortfolioTerminalPublication? {
        val native = when (search) {
            is OpenLinearPortfolioSearch -> search.native
            is LinearPortfolioSearch -> search
            else -> return null
        }
        val completed = native.result ?: return null
        val claim = completed.terminal?.claim ?: return null
        val rayValue = (claim as? SourceClaim.Ray)?.let { original.linearValue(it.sample, cancellation) }
        val mathematical = LinearPortfolioTerminal(binding, original, cancellation) {
            accepted.store(Accepted(it, rayValue))
        }.check(worker, native) ?: return null
        if (mathematical.verification !is Verification.Accepted) return mathematical
        if (claim is SourceClaim.Ray && rayValue == null) return PortfolioTerminalPublication(
            Verification.Indeterminate("linear ray lacks its exact source base value")) {}
        val standing = incumbents.exchange.current()?.objective?.source
        val boundary = when (claim) {
            is SourceClaim.Optimal -> (claim.value as? SourceValue.Rational)?.value
            is SourceClaim.Limit -> (claim.value as? SourceValue.Rational)?.value
            else -> null
        }
        val contradicts = standing != null && (claim == SourceClaim.Refutation ||
            (boundary != null && (values.improves(standing, boundary) ||
                (claim is SourceClaim.Limit && standing == boundary))))
        return if (contradicts) PortfolioTerminalPublication(
            Verification.Rejected("linear terminal contradicts an accepted source incumbent")) {} else mathematical
    }

    fun fold(result: MinimizeResult): OpenTheoryOptimum {
        val terminal = accepted.load()
        val pooled = incumbents.exchange.current()
        val bound = strongestBound()
        val reason = terminal?.result?.reason ?: when {
            timeout() -> TerminationReason.Timeout
            cancellation() -> TerminationReason.Cancelled
            result is MinimizeResult.BestFound -> result.reason
            result is MinimizeResult.Unknown -> result.reason
            else -> TerminationReason.BudgetExhausted
        }
        val authority = LinearOptimizationResult(original.scope, terminal?.result?.terminal, bound,
            pooled?.assignment, pooled?.objective?.source, reason, result.stats)
        val point = pooled?.assignment?.let { OpenTheoryAssignment.Sampled(it) }
        val score = pooled?.objective?.source?.let(::minimized)
        return when (val claim = authority.terminal?.claim) {
            is SourceClaim.Optimal -> OpenTheoryOptimum.Optimal(checkNotNull(point), checkNotNull(score),
                result.stats, authority)
            SourceClaim.Refutation -> OpenTheoryOptimum.Infeasible(result.stats, authority)
            is SourceClaim.Ray -> OpenTheoryOptimum.Unbounded(OpenTheoryAssignment.Sampled(claim.sample),
                minimized(checkNotNull(terminal?.rayValue)), result.stats, authority)
            else -> OpenTheoryOptimum.Bounded(point, score, reason, result.stats, authority)
        }
    }

    private fun strongestBound(): SourceEvidence? {
        var standing: SourceEvidence? = accepted.load()?.result?.bound?.takeIf { it.scope == original.scope }
        for ((worker, reference) in handles) {
            if (!worker.permitsSourceProofs) continue
            val handle = reference.load() ?: continue
            if (!binding.matches(handle.invocation)) continue
            val snapshot = handle.result ?: continue
            if (snapshot.scope != original.scope) continue
            val candidate = snapshot.bound?.takeIf { it.scope == original.scope } ?: continue
            val claim = candidate.claim as? SourceClaim.Bound ?: continue
            val value = (claim.value as? SourceValue.Rational)?.value ?: continue
            val old = standing?.claim as? SourceClaim.Bound
            val oldValue = (old?.value as? SourceValue.Rational)?.value
            if (oldValue == null || values.improves(oldValue, value) ||
                (oldValue == value && claim.strict && old?.strict != true)
            ) standing = candidate
        }
        return standing
    }

    private fun minimized(value: BigFraction): BigFraction = values.minimizedSource(value)
}
