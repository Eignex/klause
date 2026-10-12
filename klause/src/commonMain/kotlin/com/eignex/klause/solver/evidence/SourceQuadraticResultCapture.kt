package com.eignex.klause.solver.evidence

import com.eignex.klause.solver.result.QuadraticCertificate
import com.eignex.klause.solver.result.QuadraticClaim
import com.eignex.klause.solver.result.QuadraticObjectiveBound
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.util.Cancellation

/** Retain exact quadratic result claims with explicitly optional bound and traversal derivations. */
fun SourceResultCapture.quadratic(
    result: QuadraticOptimizationResult,
    producer: String,
    derivation: SourceDerivation? = null,
    boundDerivation: SourceDerivation? = null,
    cancellation: Cancellation = Cancellation.Never,
): QuadraticOptimizationResult {
    require(context.scope.objective?.sense == result.sense) {
        "quadratic capture requires the source objective direction"
    }
    if (cancellation() || result.capturedScopes().any { it != context.scope }) return result
    val point = (result as? QuadraticOptimizationResult.WithIncumbent)?.incumbent
    val witness = point?.let { SourceClaim.Witness(it.sample) }
    fun payload(claim: SourceClaim, proof: SourceDerivation): SourceResultEvidence {
        val derivations = buildList {
            if (witness != null && claim !is SourceClaim.Witness) {
                add(SourceEvidence(context.scope, witness, SourceDerivation.SourceWitness))
            }
            add(SourceEvidence(context.scope, claim, proof))
        }
        return SourceResultEvidence(context.scope, derivations, listOf(SourceEngineAttestation(producer, claim)))
    }
    fun bound(value: QuadraticObjectiveBound): QuadraticObjectiveBound {
        val claim = SourceClaim.Bound(SourceValue.Algebraic(value.value), value.strict)
        val proof = boundDerivation ?: (derivation as? SourceDerivation.Optimum)?.bound
            ?: SourceDerivation.Unavailable("quadratic global bound derivation not captured")
        return value.copy(certificate = QuadraticCertificate.captured(value.certificate.model,
            QuadraticClaim.Bound, payload(claim, proof)))
    }
    val claim = when (result) {
        is QuadraticOptimizationResult.Optimal -> SourceClaim.Optimal(result.incumbent.sample,
            SourceValue.Algebraic(result.incumbent.value))
        is QuadraticOptimizationResult.UnattainedLimit -> SourceClaim.Limit(result.incumbent.sample,
            SourceValue.Algebraic(result.bound.value))
        is QuadraticOptimizationResult.Unbounded -> SourceClaim.Unbounded(result.incumbent.sample)
        is QuadraticOptimizationResult.Infeasible -> SourceClaim.Refutation
        is QuadraticOptimizationResult.BestFound -> checkNotNull(witness)
        is QuadraticOptimizationResult.Unknown -> null
    }
    val proof = derivation ?: when {
        claim is SourceClaim.Witness -> SourceDerivation.SourceWitness
        claim is SourceClaim.Optimal && boundDerivation != null ->
            SourceDerivation.Optimum(SourceDerivation.SourceWitness, boundDerivation)
        else -> SourceDerivation.Unavailable("quadratic traversal or objective completion derivation not captured")
    }
    val evidence = claim?.let { payload(it, proof) }
    fun certificate(value: QuadraticCertificate, kind: QuadraticClaim): QuadraticCertificate =
        QuadraticCertificate.captured(value.model, kind, checkNotNull(evidence))
    return when (result) {
        is QuadraticOptimizationResult.Optimal -> result.copy(bound = bound(result.bound),
            certificate = certificate(result.certificate, QuadraticClaim.Optimal), evidence = evidence)
        is QuadraticOptimizationResult.UnattainedLimit -> result.copy(bound = bound(result.bound),
            certificate = certificate(result.certificate, QuadraticClaim.UnattainedLimit), evidence = evidence)
        is QuadraticOptimizationResult.Unbounded -> result.copy(
            certificate = certificate(result.certificate, QuadraticClaim.Unbounded), evidence = evidence,
        )
        is QuadraticOptimizationResult.Infeasible -> result.copy(
            certificate = certificate(result.certificate, QuadraticClaim.Infeasible), evidence = evidence,
        )
        is QuadraticOptimizationResult.BestFound -> result.copy(bound = result.bound?.let(::bound), evidence = evidence)
        is QuadraticOptimizationResult.Unknown -> result.copy(bound = result.bound?.let(::bound))
    }
}

/** Check the completed quadratic result's actual coordinates, values, strictness and source direction. */
fun QuadraticOptimizationResult.verifySourceEvidence(
    context: SourceProofContext,
    cancellation: Cancellation = Cancellation.Never,
): SourceEvidenceCheck {
    if (capturedScopes().any { it != context.scope }) {
        return SourceEvidenceCheck.Rejected("quadratic result contains a foreign source invocation")
    }
    if (context.scope.objective?.sense != sense) {
        return SourceEvidenceCheck.Rejected("quadratic result has a different source objective direction")
    }
    val claim = when (this) {
        is QuadraticOptimizationResult.Optimal -> {
            val matching = matchSourceClaims(SourceClaim.Bound(SourceValue.Algebraic(incumbent.value)),
                SourceClaim.Bound(SourceValue.Algebraic(bound.value), bound.strict), cancellation)
            if (matching != SourceEvidenceCheck.Checked) return matching
            SourceClaim.Optimal(incumbent.sample, SourceValue.Algebraic(incumbent.value))
        }
        is QuadraticOptimizationResult.UnattainedLimit -> {
            if (!bound.strict) return SourceEvidenceCheck.Rejected("unattained result has no strict global bound")
            SourceClaim.Limit(incumbent.sample, SourceValue.Algebraic(bound.value))
        }
        is QuadraticOptimizationResult.Unbounded -> SourceClaim.Unbounded(incumbent.sample)
        is QuadraticOptimizationResult.Infeasible -> SourceClaim.Refutation
        is QuadraticOptimizationResult.BestFound -> SourceClaim.Witness(incumbent.sample)
        is QuadraticOptimizationResult.Unknown -> return SourceEvidenceCheck.Unavailable("no completed quadratic claim")
    }
    val proof = capturedEvidence() ?: return SourceEvidenceCheck.Unavailable(
        "quadratic source derivation capture unavailable",
    )
    val checked = proof.verify(claim, context, cancellation)
    if (checked != SourceEvidenceCheck.Checked) return checked
    if (this is QuadraticOptimizationResult.BestFound) {
        return context.semanticsChecker?.objectiveEquals(incumbent.sample, SourceValue.Algebraic(incumbent.value),
            context.scope, cancellation)
            ?: SourceEvidenceCheck.Unavailable("quadratic original-source objective checker unavailable")
    }
    return SourceEvidenceCheck.Checked
}

private fun QuadraticOptimizationResult.capturedScopes(): List<SourceScope> = buildList {
    capturedEvidence()?.scope?.let(::add)
    when (val result = this@capturedScopes) {
        is QuadraticOptimizationResult.Optimal -> {
            result.certificate.scope?.let(::add)
            result.bound.certificate.scope?.let(::add)
        }
        is QuadraticOptimizationResult.UnattainedLimit -> {
            result.certificate.scope?.let(::add)
            result.bound.certificate.scope?.let(::add)
        }
        is QuadraticOptimizationResult.Unbounded -> result.certificate.scope?.let(::add)
        is QuadraticOptimizationResult.Infeasible -> result.certificate.scope?.let(::add)
        is QuadraticOptimizationResult.BestFound -> result.bound?.certificate?.scope?.let(::add)
        is QuadraticOptimizationResult.Unknown -> result.bound?.certificate?.scope?.let(::add)
    }
}

private fun QuadraticOptimizationResult.capturedEvidence(): SourceResultEvidence? = when (this) {
    is QuadraticOptimizationResult.Optimal -> evidence
    is QuadraticOptimizationResult.UnattainedLimit -> evidence
    is QuadraticOptimizationResult.Unbounded -> evidence
    is QuadraticOptimizationResult.Infeasible -> evidence
    is QuadraticOptimizationResult.BestFound -> evidence
    is QuadraticOptimizationResult.Unknown -> evidence
}
