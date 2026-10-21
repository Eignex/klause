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
import com.eignex.klause.solver.validation.capturedSourceSlack
import com.eignex.klause.solver.validation.checkLinearSourceWitness
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt

/** Conclusion under the captured linear source certification and acceptance policy. */
enum class LinearSourceConclusion {
    /** No accepted source conclusion is available. */
    UNKNOWN,

    /** An original-source point is accepted. */
    SATISFIABLE,

    /** An accepted source point is optimal under the captured certification policy. */
    OPTIMAL,

    /** The source is infeasible under the captured certification policy. */
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
    /** Conclusion established under the original source certification and acceptance policy. */
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
) {
    /** Whether terminal statuses use numerical tolerance policy rather than exact source certification. */
    val numericalClaims: Boolean get() = source.semantics.effective.feasibilityTolerance?.isExact == false
}

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
            sample.linearSourceReal(id) ?: return LinearSourceCheck.Declined
        }
        values += value
    }
    return checkLinearSourceWitness(view.source, values, stop)
}

internal fun PreparedLinearSource.check(assignment: OpenTheoryAssignment, stop: Cancellation): LinearSourceCheck {
    if (assignment is OpenTheoryAssignment.Sampled) return check(assignment.sample, stop)
    if (assignment is OpenTheoryAssignment.Difference) return check(assignment.sample, stop)
    var realAssignment = assignment
    while (realAssignment is OpenTheoryAssignment.Rebuilt) {
        stop.charge(1L)
        if (stop()) return LinearSourceCheck.Declined
        realAssignment = realAssignment.base
    }
    val realSample = when (realAssignment) {
        is OpenTheoryAssignment.Sampled -> realAssignment.sample
        is OpenTheoryAssignment.Difference -> realAssignment.sample
        else -> null
    }
    val values = ArrayList<BigFraction>(view.columns.size)
    for (reference in view.columns) {
        stop.charge(1L)
        if (stop()) return LinearSourceCheck.Declined
        val value = if (Term.isInt(reference)) {
            BigFraction.of(parseBigInt(assignment.intValue(Term.intVar(reference))), BIG_ONE)
        } else {
            val id = Term.realVar(reference)
            if (realSample != null) {
                if (id >= realSample.numRealVars) return LinearSourceCheck.Rejected
                values += realSample.linearSourceReal(id) ?: return LinearSourceCheck.Declined
                continue
            }
            val algebraic = realAssignment.algebraicRealValue(id)
            if (algebraic != null) {
                values += algebraic.linearRational() ?: return LinearSourceCheck.Declined
                continue
            }
            val text = realAssignment.realValue(id)
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

private fun Sample.linearSourceReal(id: Int): BigFraction? = when {
    exactReals != null -> exactReals[id]
    algebraicReals != null -> algebraicReals[id].linearRational()
    symbolicReals != null -> symbolicReals[id].rational ?: symbolicReals[id].algebraic?.linearRational()
    else -> BigFraction.ofDouble(approximateRealValue(id))
}

private fun AlgebraicReal.linearRational(): BigFraction? {
    rational?.let { return it }
    val coefficients = definingCoefficients
    return if (coefficients.size == 2 && !coefficients[1].isZero) {
        coefficients[0].negated() * coefficients[1].reciprocal()
    } else {
        null
    }
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
    var nativeOptimum: LinearSourceWitness? = null
    var unboundedBaseExact = false
    fun checked(check: LinearSourceCheck): LinearSourceWitness? {
        accept(check)
        return (check as? LinearSourceCheck.Accepted)?.witness
    }
    fun sample(sample: Sample?): LinearSourceWitness? = sample?.let { checked(prepared.check(it, stop)) }
    fun assignment(assignment: OpenTheoryAssignment?): LinearSourceWitness? =
        assignment?.let { checked(prepared.check(it, stop)) }
    fun linear(result: com.eignex.klause.solver.result.LinearOptimizationResult) {
        val point = sample(result.incumbent)
        native = when (val claim = result.terminal?.claim) {
            is SourceClaim.Optimal -> {
                nativeOptimum = point
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
        is SourceExecutionResult.Prepared -> return result
        is SourceExecutionResult.Finite -> (result.result.outcome as? FiniteSolveOutcome.Completed)?.let { completed ->
            val point = sample(completed.bestSample)
            native = when (completed.verdict) {
                FiniteSolveVerdict.OPTIMAL -> {
                    nativeOptimum = point
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
                            nativeOptimum = assignment(optimum.assignment)
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
            val point = (optimum as? QuadraticOptimizationResult.WithIncumbent)?.let { sample(it.incumbent.sample) }
            native = when (optimum) {
                is QuadraticOptimizationResult.Optimal -> {
                    nativeOptimum = point
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
    }.also { it.preparationStats = result.preparationStats }
    val strategy = request.polynomial.strategy
    val nativeClaimsUnrestricted = finiteUnrestricted && if (result is SourceExecutionResult.Polynomial ||
        result is SourceExecutionResult.Quadratic
    ) {
        listOf(strategy.assumptions, strategy.backtrack.assumptions, strategy.localSearch.assumptions)
            .all { it.isEmpty && it.deductions.isEmpty }
    } else {
        true
    }
    val numerical = prepared.view.source.semantics.effective.feasibilityTolerance?.isExact == false
    val equivalent = prepared.view.exact && prepared.claimsCoverTolerance && nativeClaimsUnrestricted
    val acceptedOptimal = incumbent?.objective?.let { value -> value == prepared.acceptedObjectiveBound } == true
    val nativeOptimalAttained = nativeOptimum?.let { point ->
        prepared.matchesNativeObjective(point, nativeOptimalValue, stop) && incumbent?.let {
            prepared.matchesNativeObjective(it, nativeOptimalValue, stop)
        } == true
    } == true
    val conclusion = when {
        acceptedOptimal -> LinearSourceConclusion.OPTIMAL

        native == LinearSourceConclusion.UNSATISFIABLE && incumbent == null && nativeClaimsUnrestricted &&
            (numerical || (prepared.view.constraintsExact && prepared.claimsCoverTolerance)) -> native

        invocation.operation != ExecutionOperation.OPTIMIZE && incumbent != null -> LinearSourceConclusion.SATISFIABLE

        native == LinearSourceConclusion.OPTIMAL && nativeClaimsUnrestricted && nativeOptimalAttained &&
            (numerical || (equivalent && incumbent?.exact == true)) -> native

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
        is SourceExecutionResult.Prepared -> result
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

private fun PreparedLinearSource.matchesNativeObjective(
    point: LinearSourceWitness,
    nativeValue: BigFraction?,
    stop: Cancellation,
): Boolean {
    val value = point.objective ?: return false
    val native = nativeValue?.let { it * view.objectiveScale.reciprocal() } ?: return false
    val difference = (value - native).magnitude()
    if (difference.isZero) return !stop()
    val objective = view.source.objective ?: return false
    val policy = view.source.semantics.effective.feasibilityTolerance
    if (policy == null || policy.isExact) return false
    val values = point.values
    var magnitude = native.magnitude() + BigFraction.of(objective.constant.numerator, objective.constant.denominator)
        .magnitude()
    for (term in objective.terms) {
        stop.charge(1L)
        if (stop()) return false
        magnitude += (BigFraction.of(term.coefficient.numerator, term.coefficient.denominator) * values[term.column])
            .magnitude()
    }
    return !stop() && difference <= capturedSourceSlack(policy, native, magnitude)
}

private fun BigFraction.magnitude(): BigFraction = if (signum() < 0) negated() else this
