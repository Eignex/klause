package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException

/** An additional exact original-source check in retained source coordinates. */
fun interface SourceWitnessValidator {
    /** Check one complete candidate; unavailable checking cannot justify a witness or terminal claim. */
    fun validate(assignment: OpenTheoryAssignment, cancellation: ExecutionControl): SourceEvidenceCheck
}

@OptIn(ExperimentalAtomicApi::class)
internal class SourceWitnessPublication(
    private val source: SourceAttachment,
    private val cancellation: ExecutionControl,
) {
    private val failure = AtomicReference<TerminationReason?>(null)
    private val accepted = AtomicReference(false)
    val failed: Boolean get() = failure.load() != null
    val stop: ExecutionControl = ExecutionControl { failed }
    val reason: TerminationReason get() = failure.load() ?: TerminationReason.RepresentationDeclined

    fun accept(sample: Sample): Boolean = accept(OpenTheoryAssignment.Sampled(sample))

    fun accept(assignment: OpenTheoryAssignment): Boolean {
        if (failed) return false
        for (validator in source.witnessValidators) {
            var interrupted = false
            val checked = if (cancellation()) {
                interrupted = true
                SourceEvidenceCheck.Unavailable("original-source witness checking interrupted")
            } else try {
                validator.validate(assignment, cancellation).let {
                    if (cancellation()) {
                        interrupted = true
                        SourceEvidenceCheck.Unavailable("original-source witness checking interrupted")
                    } else it
                }
            } catch (_: CancellationException) {
                interrupted = true
                SourceEvidenceCheck.Unavailable("original-source witness checking interrupted")
            }
            if (checked != SourceEvidenceCheck.Checked) {
                val reason = if (interrupted) {
                    if (cancellation.deadline()?.hasPassedNow() == true) TerminationReason.Timeout
                    else TerminationReason.Cancelled
                } else TerminationReason.RepresentationDeclined
                failure.compareAndSet(null, reason)
                return false
            }
        }
        if (failed) return false
        accepted.store(true)
        return true
    }

    fun qualify(result: FiniteSolveResult): FiniteSolveResult {
        val completed = result.outcome as? FiniteSolveOutcome.Completed
        completed?.bestSample?.let(::accept)
        val requiresWitness = completed?.verdict in setOf(
            FiniteSolveVerdict.SAT, FiniteSolveVerdict.OPTIMAL, FiniteSolveVerdict.UNBOUNDED,
        )
        if (requiresWitness &&
            !accepted.load()
        ) failure.compareAndSet(null, TerminationReason.RepresentationDeclined)
        if (!failed) return result
        return FiniteSolveResult(result.preparation, result.preparationElapsed, FiniteSolveOutcome.Completed(
            FiniteSolveVerdict.UNKNOWN, completed?.stats ?: SolveStats.EMPTY, 0L, null, completed?.elapsedMs ?: 0L,
        )).also { it.bindCompleteSearchAdmission(result.completeSearchAdmitted) }
    }

    fun callbacks(original: SourceExecutionCallbacks): SourceExecutionCallbacks {
        val finite = FiniteSolveCallbacks(
            { sample -> if (accept(sample)) original.finite.onSample(sample) },
            { improvement -> if (accept(improvement.sample)) original.finite.onImprovement(improvement) },
        )
        val linear: (SourceIncumbentPublication) -> Unit = { publication ->
            if (accept(publication.sample)) original.onLinearIncumbent?.invoke(publication)
        }
        val quadratic: (QuadraticIncumbentPublication) -> Unit = { publication ->
            if (accept(publication.incumbent.sample)) original.onQuadraticIncumbent?.invoke(publication)
        }
        return original.onLinearSourceIncumbent?.let {
            SourceExecutionCallbacks(it, finite, linear, quadratic)
        } ?: SourceExecutionCallbacks(finite, linear, quadratic)
    }

    fun qualify(result: SourceExecutionResult): SourceExecutionResult {
        fun check(verdict: OpenTheoryResult) {
            if (verdict is OpenTheoryResult.Sat) accept(verdict.assignment)
        }
        when (result) {
            is SourceExecutionResult.Linear -> when (val execution = result.result) {
                is OpenTheoryExecution.Satisfy -> check(execution.result)
                is OpenTheoryExecution.Optimize -> {
                    execution.acceptedIncumbent?.sample?.let(::accept)
                    when (val optimum = execution.result) {
                        is OpenTheoryOptimum.Optimal -> accept(optimum.assignment)
                        is OpenTheoryOptimum.Unbounded -> accept(optimum.witness)
                        is OpenTheoryOptimum.Bounded -> optimum.incumbent?.let(::accept)
                        is OpenTheoryOptimum.Infeasible -> Unit
                    }
                }
            }
            is SourceExecutionResult.Polynomial -> check(result.result.result)
            is SourceExecutionResult.Transcendental -> check(result.result.result)
            is SourceExecutionResult.Quadratic -> {
                result.result.acceptedIncumbent?.incumbent?.sample?.let(::accept)
                (result.result.result as? QuadraticOptimizationResult.WithIncumbent)?.incumbent?.sample?.let(::accept)
            }
            is SourceExecutionResult.Finite, is SourceExecutionResult.Declined,
            is SourceExecutionResult.Prepared, is SourceExecutionResult.Refuted -> Unit
        }
        return if (failed) withhold(result).also {
            it.preparationStats = result.preparationStats
        } else result
    }

    private fun withhold(result: SourceExecutionResult): SourceExecutionResult {
        fun unknown(verdict: OpenTheoryResult) = OpenTheoryResult.Unknown(reason, verdict.stats, verdict.semantics)
        return when (result) {
            is SourceExecutionResult.Linear -> SourceExecutionResult.Linear(result.invocation,
                when (val execution = result.result) {
                    is OpenTheoryExecution.Satisfy -> OpenTheoryExecution.Satisfy(unknown(execution.result))
                    is OpenTheoryExecution.Optimize -> OpenTheoryExecution.Optimize(OpenTheoryOptimum.Bounded(
                        null, null, reason, execution.result.stats, semantics = execution.semantics,
                    ))
                })
            is SourceExecutionResult.Polynomial -> SourceExecutionResult.Polynomial(result.invocation,
                result.result.copy(result = unknown(result.result.result)))
            is SourceExecutionResult.Transcendental -> SourceExecutionResult.Transcendental(result.invocation,
                result.result.copy(result = unknown(result.result.result)))
            is SourceExecutionResult.Quadratic -> SourceExecutionResult.Quadratic(result.invocation,
                result.result.copy(result = QuadraticOptimizationResult.Unknown(null, reason,
                    result.result.result.sense, result.result.result.stats)))
            else -> SourceExecutionResult.Declined(result.invocation, reason)
        }
    }
}
