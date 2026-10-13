package com.eignex.klause.solver.evidence

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

/** Optional claim capture over independently supplied original-source verification data. */
class SourceResultCapture private constructor(
    /** Original-source context used for every captured result. */
    val context: SourceProofContext,
    private val sourceBinding: OriginalSourceBinding?,
) {
    /** Capture manually supplied source-scoped results using an independent context. */
    constructor(context: SourceProofContext) : this(context, null)

    internal fun accepts(invocation: QuadraticEngineInvocation): Boolean =
        sourceBinding?.matches(invocation) == true && invocation.condition == context.scope.condition &&
            invocation.sense == context.scope.objective?.sense

    internal fun belongsTo(source: Problem): Boolean = sourceBinding?.belongsTo(source) == true

    internal fun belongsTo(source: Problem, objective: LinearObjective): Boolean =
        sourceBinding?.matches(source, objective) == true

    internal companion object {
        fun forOriginal(
            context: SourceProofContext, source: Problem, objective: LinearObjective?,
        ): SourceResultCapture = SourceResultCapture(context, OriginalSourceBinding(source, null, objective))

        fun forQuadratic(
            context: SourceProofContext, source: Problem, objective: QuadraticObjectiveSpec,
        ): SourceResultCapture = SourceResultCapture(context, OriginalSourceBinding(source, objective))
    }

    /** Retain exact quadratic result claims with explicitly optional bound and traversal derivations. */
    fun quadratic(
        result: QuadraticOptimizationResult,
        producer: String,
        derivation: SourceDerivation? = null,
        boundDerivation: SourceDerivation? = null,
        cancellation: Cancellation = Cancellation.Never,
    ): QuadraticOptimizationResult {
        require(context.scope.objective?.sense == result.sense) {
            "quadratic capture requires the source objective direction"
        }
        if (cancellation() || result.capturedScopes().any { it != context.scope } ||
            result.capturedInvocations().any { !accepts(it) } ||
            result.capturedEvidencePackages().any { it.engineScope != context.scope }
        ) return result
        val expected = result::class
        val mappings = result.capturedEvidencePackages().flatMap { it.mappings }.distinct()
        val captured = SourceQuadraticResultCapture(context, producer, derivation, boundDerivation, mappings)
            .capture(result)
        check(captured::class == expected) {
            "quadratic capture changed $expected to ${captured::class}; input is ${result::class}"
        }
        return captured
    }

    /** Capture exact source assignments and explicitly classify missing complete-search proof data. */
    fun solve(result: SolveResult, producer: String, derivation: SourceDerivation? = null): SolveResult {
        if (result.evidence?.let { it.scope != context.scope || it.engineScope != context.scope } == true) return result
        val claim = when (result) {
            is SolveResult.Sat -> SourceClaim.Witness(result.assignment)
            is SolveResult.Unsat -> SourceClaim.Refutation
            is SolveResult.Unknown -> return result
        }
        val proof = derivation ?: if (claim is SourceClaim.Witness) SourceDerivation.SourceWitness
            else SourceDerivation.Unavailable("complete traversal derivation not captured")
        val payload = SourceResultEvidence(context.scope, listOf(SourceEvidence(context.scope, claim, proof)),
            listOf(SourceEngineAttestation(producer, claim))).withMappings(
                context.scope, result.evidence?.mappings.orEmpty(),
            )
        return when (result) {
            is SolveResult.Sat -> result.copy(evidence = payload)
            is SolveResult.Unsat -> result.copy(evidence = payload)
            is SolveResult.Unknown -> result
        }
    }

    /** Capture objective claims in exact source units, without using floating objective telemetry as authority. */
    fun minimize(
        result: MinimizeResult,
        objective: LinearObjective,
        producer: String,
        derivation: SourceDerivation? = null,
        cancellation: Cancellation = Cancellation.Never,
    ): MinimizeResult {
        require(context.scope.objective != null) { "objective result capture requires an objective scope" }
        if (cancellation() || result is MinimizeResult.Unknown ||
            result.evidence?.let { it.scope != context.scope || it.engineScope != context.scope } == true
        ) return result
        val sample = result.assignment
        val witness = sample?.let { SourceClaim.Witness(it) }
        val value = if (result is MinimizeResult.Optimal) {
            try {
                exactValue(objective, result.sample, cancellation)
            } catch (_: PolynomialInterrupted) {
                return result
            } catch (_: PolynomialResourceLimit) {
                return result
            }
        } else null
        val claim: SourceClaim = when (result) {
            is MinimizeResult.Optimal -> SourceClaim.Optimal(result.sample, checkNotNull(value))
            is MinimizeResult.Infeasible -> SourceClaim.Refutation
            is MinimizeResult.Unbounded -> SourceClaim.Ray(result.sample,
                result.direction.withIndex().associate { (id, step) ->
                    SourceCoordinate(SourceCoordinate.Kind.Real, id) to step
                })
            is MinimizeResult.BestFound -> checkNotNull(witness)
            is MinimizeResult.Unknown -> return result
        }
        val proof = derivation ?: when (claim) {
            is SourceClaim.Witness -> SourceDerivation.SourceWitness
            is SourceClaim.Ray -> SourceDerivation.SourceRay
            else -> SourceDerivation.Unavailable("objective bound or complete traversal derivation not captured")
        }
        val evidence = buildList {
            if (witness != null && claim !is SourceClaim.Witness) {
                add(SourceEvidence(context.scope, witness, SourceDerivation.SourceWitness))
            }
            add(SourceEvidence(context.scope, claim, proof))
        }
        val payload = SourceResultEvidence(context.scope, evidence,
            listOf(SourceEngineAttestation(producer, claim))).withMappings(
            context.scope, result.evidence?.mappings.orEmpty(),
        )
        return when (result) {
            is MinimizeResult.Optimal -> result.copy(evidence = payload)
            is MinimizeResult.BestFound -> result.copy(evidence = payload)
            is MinimizeResult.Unbounded -> result.copy(evidence = payload)
            is MinimizeResult.Infeasible -> result.copy(evidence = payload)
            is MinimizeResult.Unknown -> result
        }
    }

    private fun exactValue(
        objective: LinearObjective, sample: Sample, cancellation: Cancellation,
    ): SourceValue {
        if (sample.algebraicReals == null) return SourceValue.Rational(objective.evaluateExact(sample))
        val arithmetic = AlgebraicArithmetic(PolynomialArithmetic(cancellation))
        var value = arithmetic.rational(BigFraction.ofLong(objective.constant))
        for ((id, coefficient) in objective.boolWeights.withIndex()) {
            if (sample.boolValue(id)) value = arithmetic.add(value,
                arithmetic.rational(BigFraction.ofLong(coefficient)))
        }
        for ((id, coefficient) in objective.intCoefficients.withIndex()) {
            value = arithmetic.add(value, arithmetic.rational(
                BigFraction.of(sample.exactIntValue(id), BIG_ONE) * BigFraction.ofLong(coefficient),
            ))
        }
        for ((id, coefficient) in objective.realCoefficients.withIndex()) {
            val scalar = requireNotNull(BigFraction.ofDouble(coefficient))
            value = arithmetic.add(value, arithmetic.multiply(checkNotNull(sample.algebraicReals)[id],
                arithmetic.rational(scalar)))
        }
        return value.rational?.let { SourceValue.Rational(it) } ?: SourceValue.Algebraic(value)
    }
}

private class OriginalSourceBinding(
    private val source: Problem,
    private val objective: QuadraticObjectiveSpec?,
    private val affine: LinearObjective? = null,
) {
    fun belongsTo(model: Problem): Boolean = source === model
    fun matches(model: Problem, original: LinearObjective): Boolean = source === model && affine === original

    fun matches(invocation: QuadraticEngineInvocation): Boolean =
        objective != null && (invocation.source as? QuadraticEngineSource.ExactIr)?.problem === source &&
            invocation.objective === objective
}
