package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.validation.LinearSourceCheck
import com.eignex.klause.solver.validation.LinearSourceWitness
import com.eignex.klause.solver.validation.checkLinearSourceWitness
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt

/** Mathematical conclusion after checking the original linear source and its acceptance policy. */
enum class LinearSourceConclusion {
    /** No accepted source conclusion is available. */
    UNKNOWN,

    /** An original-source point is accepted. */
    SATISFIABLE,

    /** An accepted source point attains a checked global original-unit bound. */
    OPTIMAL,

    /** The accepted source has no feasible assignment. */
    UNSATISFIABLE,

    /** A checked source trajectory improves the original objective without limit. */
    UNBOUNDED,

    /** A checked original-source objective limit is unattained. */
    LIMIT,
}

/** Immutable original-unit qualification beside the complete retained native result. */
class LinearSourceOutcome internal constructor(
    /** Original source snapshot. */
    val source: LinearSourceProblem,
    /** Accepted original declaration coordinates and objective, if one was checked. */
    val incumbent: LinearSourceWitness?,
    /** Native conclusion in its own retained model scope. */
    val nativeConclusion: LinearSourceConclusion,
    /** Conclusion established for the original accepted source. */
    val conclusion: LinearSourceConclusion,
    /** Whether the native view retains the nominal rational source exactly. */
    val nominalSourceExact: Boolean,
    /** Whether nominal constraints retain source feasibility independently of objective projection. */
    val nominalConstraintsExact: Boolean,
    /** Whether nominal constraints and tolerance acceptance have the same feasible points. */
    val claimsCoverTolerance: Boolean,
    /** Independently checked global accepted-set objective bound in original units. */
    val acceptedObjectiveBound: BigFraction?,
    /** Whether the native terminal covers the whole nominal source rather than supplied conditions. */
    val nativeClaimsUnrestricted: Boolean,
    /** Positive multiplier from original objective units to the retained working objective. */
    val objectiveScale: BigFraction,
)

internal fun PreparedLinearSource.check(sample: Sample, stop: Cancellation): LinearSourceCheck {
    val values = ArrayList<BigFraction>(view.columns.size)
    for (reference in view.columns) {
        stop.charge(1L)
        if (stop()) return LinearSourceCheck.Declined
        val value = if (Term.isInt(reference)) {
            val id = Term.intVar(reference)
            if (id >= sample.numIntVars) return LinearSourceCheck.Rejected
            BigFraction.of(sample.exactIntValue(id), BIG_ONE)
        } else {
            val id = Term.realVar(reference)
            if (id >= sample.numRealVars) return LinearSourceCheck.Rejected
            when {
                sample.exactReals != null -> sample.exactReals[id]
                sample.algebraicReals != null -> sample.algebraicReals[id].linearRational()
                sample.symbolicReals != null -> sample.symbolicReals[id].algebraic?.linearRational()
                else -> BigFraction.ofDouble(sample.approximateRealValue(id))
            } ?: return LinearSourceCheck.Declined
        }
        values += value
    }
    return checkLinearSourceWitness(view.source, values, stop)
}

private fun PreparedLinearSource.check(assignment: OpenTheoryAssignment, stop: Cancellation): LinearSourceCheck {
    if (assignment is OpenTheoryAssignment.Sampled) return check(assignment.sample, stop)
    if (assignment is OpenTheoryAssignment.Difference) return check(assignment.sample, stop)
    val values = ArrayList<BigFraction>(view.columns.size)
    for (reference in view.columns) {
        stop.charge(1L)
        if (stop()) return LinearSourceCheck.Declined
        val value = if (Term.isInt(reference)) {
            BigFraction.of(parseBigInt(assignment.intValue(Term.intVar(reference))), BIG_ONE)
        } else {
            val algebraic = assignment.algebraicRealValue(Term.realVar(reference))
            if (algebraic != null) {
                values += algebraic.linearRational() ?: return LinearSourceCheck.Declined
                continue
            }
            val text = assignment.realValue(Term.realVar(reference))
            val parts = text.split('/')
            if (parts.size !in 1..2 || parts.any { !it.matches(Regex("-?[0-9]+")) }) {
                return LinearSourceCheck.Declined
            }
            BigFraction.of(parseBigInt(parts[0]), if (parts.size == 1) BIG_ONE else parseBigInt(parts[1]))
        }
        values += value
    }
    return checkLinearSourceWitness(view.source, values, stop)
}

private fun AlgebraicReal.linearRational(): BigFraction? {
    rational?.let { return it }
    val coefficients = definingCoefficients
    return if (coefficients.size == 2 && !coefficients[1].isZero) {
        coefficients[0].negated() * coefficients[1].reciprocal()
    } else null
}

internal fun executeLinearSource(
    request: SourceExecutionRequest,
    callbacks: SourceExecutionCallbacks,
    execute: (
        SourceExecutionRequest,
        SourceExecutionCallbacks,
        ((Cancellation, Boolean) -> Unit)?,
    ) -> SourceExecutionResult,
): SourceExecutionResult {
    val invocation = request.invocation
    val prepared = requireNotNull(invocation.linearSource)
    var stop = invocation.cancellation or request.theory.cancellation or request.theory.timeout or
        request.polynomial.cancellation or request.polynomial.timeout
    var incumbent: LinearSourceWitness? = null
    fun accept(check: LinearSourceCheck): LinearSourceWitness? {
        val point = (check as? LinearSourceCheck.Accepted)?.witness ?: return null
        val old = incumbent
        val value = point.objective
        val oldValue = old?.objective
        if (value == null && old != null && point.values == old.values) return null
        if (value != null && oldValue != null) {
            val better = if (invocation.maximizeSourceObjective) value > oldValue else value < oldValue
            if (!better) return null
        }
        incumbent = point
        callbacks.onLinearSourceIncumbent?.invoke(point)
        return point
    }
    val observed = SourceExecutionCallbacks(
        finite = FiniteSolveCallbacks(
            { sample -> if (accept(prepared.check(sample, stop)) != null) callbacks.finite.onSample(sample) },
            { improvement ->
                if (accept(prepared.check(improvement.sample, stop)) != null) {
                    callbacks.finite.onImprovement(improvement)
                }
            },
        ),
        onLinearIncumbent = { publication ->
            if (accept(prepared.check(publication.sample, stop)) != null) {
                callbacks.onLinearIncumbent?.invoke(publication)
            }
        },
        onQuadraticIncumbent = { publication ->
            if (accept(prepared.check(publication.incumbent.sample, stop)) != null) {
                callbacks.onQuadraticIncumbent?.invoke(publication)
            }
        },
    )
    var finiteUnrestricted = true
    val result = execute(request, observed) { scope, unrestricted ->
        stop = stop or scope
        finiteUnrestricted = unrestricted
    }
    var native = LinearSourceConclusion.UNKNOWN
    var nativeOptimalValue: BigFraction? = null
    var unboundedBaseExact = false
    fun sample(sample: Sample?) {
        if (sample != null) accept(prepared.check(sample, stop))
    }
    fun assignment(assignment: OpenTheoryAssignment?) {
        if (assignment != null) accept(prepared.check(assignment, stop))
    }
    fun linear(result: com.eignex.klause.solver.result.LinearOptimizationResult) {
        sample(result.incumbent)
        native = when (val claim = result.terminal?.claim) {
            is SourceClaim.Optimal -> {
                nativeOptimalValue = when (val value = claim.value) {
                    is SourceValue.Rational -> value.value
                    is SourceValue.Algebraic -> value.value.linearRational()
                    is SourceValue.Extension -> null
                }
                LinearSourceConclusion.OPTIMAL
            }

            SourceClaim.Refutation -> LinearSourceConclusion.UNSATISFIABLE

            is SourceClaim.Limit -> LinearSourceConclusion.LIMIT

            is SourceClaim.Unbounded -> {
                val point = (prepared.check(claim.sample, stop) as? LinearSourceCheck.Accepted)?.witness
                unboundedBaseExact = point?.exact == true
                LinearSourceConclusion.UNBOUNDED
            }

            else -> if (result.incumbent != null) LinearSourceConclusion.SATISFIABLE else LinearSourceConclusion.UNKNOWN
        }
    }
    when (result) {
        is SourceExecutionResult.Finite -> (result.result.outcome as? FiniteSolveOutcome.Completed)?.let { completed ->
            sample(completed.bestSample)
            native = when (completed.verdict) {
                FiniteSolveVerdict.OPTIMAL -> {
                    nativeOptimalValue = completed.bestSample?.let { prepared.objective?.evaluateExact(it) }
                    LinearSourceConclusion.OPTIMAL
                }

                FiniteSolveVerdict.UNSAT -> LinearSourceConclusion.UNSATISFIABLE

                FiniteSolveVerdict.UNBOUNDED -> {
                    unboundedBaseExact = completed.bestSample?.let {
                        (prepared.check(it, stop) as? LinearSourceCheck.Accepted)?.witness?.exact
                    } == true
                    LinearSourceConclusion.UNBOUNDED
                }

                FiniteSolveVerdict.SAT, FiniteSolveVerdict.BEST_FOUND -> LinearSourceConclusion.SATISFIABLE

                FiniteSolveVerdict.UNKNOWN -> LinearSourceConclusion.UNKNOWN
            }
        }

        is SourceExecutionResult.Numerical -> linear(result.result)

        is SourceExecutionResult.Linear -> when (val execution = result.result) {
            is OpenTheoryExecution.Satisfy -> when (val solved = execution.result) {
                is OpenTheoryResult.Sat -> {
                    assignment(solved.assignment)
                    native = LinearSourceConclusion.SATISFIABLE
                }

                is OpenTheoryResult.Unsat -> native = LinearSourceConclusion.UNSATISFIABLE

                is OpenTheoryResult.Unknown -> Unit
            }

            is OpenTheoryExecution.Optimize -> {
                val optimum = execution.result
                if (optimum.linearResult != null) {
                    linear(requireNotNull(optimum.linearResult))
                } else {
                    when (optimum) {
                    is OpenTheoryOptimum.Optimal -> {
                        assignment(optimum.assignment)
                        nativeOptimalValue = optimum.value
                        native = LinearSourceConclusion.OPTIMAL
                    }

                    is OpenTheoryOptimum.Infeasible -> native = LinearSourceConclusion.UNSATISFIABLE

                    is OpenTheoryOptimum.Unbounded -> {
                        assignment(optimum.witness)
                        unboundedBaseExact = (prepared.check(optimum.witness, stop) as? LinearSourceCheck.Accepted)
                            ?.witness?.exact == true
                        native = LinearSourceConclusion.UNBOUNDED
                    }

                    is OpenTheoryOptimum.Bounded -> assignment(optimum.incumbent)
                }
                }
            }
        }

        is SourceExecutionResult.Refuted -> native = LinearSourceConclusion.UNSATISFIABLE

        is SourceExecutionResult.Declined -> Unit

        is SourceExecutionResult.Polynomial -> when (val solved = result.result.result) {
            is OpenTheoryResult.Sat -> {
                assignment(solved.assignment)
                native = LinearSourceConclusion.SATISFIABLE
            }

            is OpenTheoryResult.Unsat -> native = LinearSourceConclusion.UNSATISFIABLE

            is OpenTheoryResult.Unknown -> Unit
        }

        is SourceExecutionResult.Quadratic -> {
            val optimum = result.result.result
            (optimum as? QuadraticOptimizationResult.WithIncumbent)?.let { sample(it.incumbent.sample) }
            native = when (optimum) {
                is QuadraticOptimizationResult.Optimal -> {
                    nativeOptimalValue = optimum.bound.value.linearRational()
                    LinearSourceConclusion.OPTIMAL
                }

                is QuadraticOptimizationResult.UnattainedLimit -> LinearSourceConclusion.LIMIT

                is QuadraticOptimizationResult.Unbounded -> {
                    unboundedBaseExact = (prepared.check(optimum.incumbent.sample, stop) as? LinearSourceCheck.Accepted)
                        ?.witness?.exact == true
                    LinearSourceConclusion.UNBOUNDED
                }

                is QuadraticOptimizationResult.BestFound -> LinearSourceConclusion.SATISFIABLE

                is QuadraticOptimizationResult.Infeasible -> LinearSourceConclusion.UNSATISFIABLE

                is QuadraticOptimizationResult.Unknown -> LinearSourceConclusion.UNKNOWN
            }
        }

        is SourceExecutionResult.Transcendental -> error("linear source selected function execution")
    }
    val strategy = request.polynomial.strategy
    val nativeClaimsUnrestricted = finiteUnrestricted && if (result is SourceExecutionResult.Polynomial ||
        result is SourceExecutionResult.Quadratic
    ) {
        listOf(strategy.assumptions, strategy.backtrack.assumptions, strategy.localSearch.assumptions)
            .all { it.isEmpty && it.deductions.isEmpty }
    } else {
        true
    }
    val equivalent = prepared.view.exact && prepared.claimsCoverTolerance && nativeClaimsUnrestricted
    val acceptedOptimal = incumbent?.objective?.let { value -> value == prepared.acceptedObjectiveBound } == true
    val nativeOptimalAttained = incumbent?.objective?.let { value ->
        value * prepared.view.objectiveScale == nativeOptimalValue
    } == true
    val conclusion = when {
        acceptedOptimal -> LinearSourceConclusion.OPTIMAL
        native == LinearSourceConclusion.UNSATISFIABLE && prepared.view.constraintsExact &&
            prepared.claimsCoverTolerance && nativeClaimsUnrestricted -> native
        invocation.operation != ExecutionOperation.OPTIMIZE && incumbent != null -> LinearSourceConclusion.SATISFIABLE
        native == LinearSourceConclusion.OPTIMAL && equivalent && incumbent?.exact == true &&
            nativeOptimalAttained -> native
        native == LinearSourceConclusion.LIMIT && equivalent -> native
        native == LinearSourceConclusion.UNBOUNDED && prepared.view.exact && unboundedBaseExact -> native
        incumbent != null -> LinearSourceConclusion.SATISFIABLE
        else -> LinearSourceConclusion.UNKNOWN
    }
    val snapshot = LinearSourceOutcome(
        prepared.view.source, incumbent, native, conclusion,
        prepared.view.exact, prepared.view.constraintsExact, prepared.claimsCoverTolerance,
        prepared.acceptedObjectiveBound,
        nativeClaimsUnrestricted, prepared.view.objectiveScale,
    )
    return when (result) {
        is SourceExecutionResult.Finite -> SourceExecutionResult.Finite(invocation, result.result, snapshot)
        is SourceExecutionResult.Linear -> SourceExecutionResult.Linear(invocation, result.result, snapshot)
        is SourceExecutionResult.Numerical -> SourceExecutionResult.Numerical(invocation, result.result, snapshot)
        is SourceExecutionResult.Refuted -> SourceExecutionResult.Refuted(invocation, snapshot)
        is SourceExecutionResult.Declined -> SourceExecutionResult.Declined(invocation, result.reason, snapshot)
        is SourceExecutionResult.Polynomial -> SourceExecutionResult.Polynomial(invocation, result.result, snapshot)
        is SourceExecutionResult.Quadratic -> SourceExecutionResult.Quadratic(invocation, result.result, snapshot)
        is SourceExecutionResult.Transcendental -> error("linear source selected function execution")
    }
}
