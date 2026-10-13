package com.eignex.klause.solver.evidence

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.QuadraticCertificate
import com.eignex.klause.solver.result.QuadraticClaim
import com.eignex.klause.solver.result.QuadraticObjectiveBound
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.util.Cancellation

internal class SourceQuadraticResultCapture(
    private val context: SourceProofContext,
    private val producer: String,
    private val derivation: SourceDerivation?,
    private val boundDerivation: SourceDerivation?,
    private val mappings: List<SourceMappingEvidence>,
) {
    fun capture(result: QuadraticOptimizationResult): QuadraticOptimizationResult = when (result) {
        is QuadraticOptimizationResult.Optimal -> optimal(result)
        is QuadraticOptimizationResult.UnattainedLimit -> limit(result)
        is QuadraticOptimizationResult.Unbounded -> unbounded(result)
        is QuadraticOptimizationResult.Infeasible -> infeasible(result)
        is QuadraticOptimizationResult.BestFound -> bestFound(result)
        is QuadraticOptimizationResult.Unknown -> QuadraticOptimizationResult.Unknown(
            result.bound?.let { bound(it, null) }, result.reason, result.sense, result.stats,
        )
    }

    private fun optimal(result: QuadraticOptimizationResult.Optimal): QuadraticOptimizationResult.Optimal {
        val point = result.incumbent
        val claim = SourceClaim.Optimal(point.sample, SourceValue.Algebraic(point.value))
        val evidence = completion(claim, point.sample, result.certificate.derivation)
        return QuadraticOptimizationResult.Optimal(
            point, bound(result.bound, point.sample),
            certificate(result.certificate, evidence, QuadraticClaim.Optimal), result.sense, result.stats, evidence,
        )
    }

    private fun limit(
        result: QuadraticOptimizationResult.UnattainedLimit,
    ): QuadraticOptimizationResult.UnattainedLimit {
        val claim = SourceClaim.Limit(result.incumbent.sample, SourceValue.Algebraic(result.bound.value))
        val evidence = completion(claim, result.incumbent.sample, result.certificate.derivation)
        return QuadraticOptimizationResult.UnattainedLimit(
            result.incumbent, bound(result.bound, result.incumbent.sample),
            certificate(result.certificate, evidence, QuadraticClaim.UnattainedLimit),
            result.sense, result.stats, evidence,
        )
    }

    private fun unbounded(result: QuadraticOptimizationResult.Unbounded): QuadraticOptimizationResult.Unbounded {
        val evidence = completion(SourceClaim.Unbounded(result.incumbent.sample),
            result.incumbent.sample, result.certificate.derivation)
        return QuadraticOptimizationResult.Unbounded(
            result.incumbent, certificate(result.certificate, evidence, QuadraticClaim.Unbounded),
            result.sense, result.stats, evidence,
        )
    }

    private fun infeasible(result: QuadraticOptimizationResult.Infeasible): QuadraticOptimizationResult.Infeasible {
        val evidence = completion(SourceClaim.Refutation, null, result.certificate.derivation)
        return QuadraticOptimizationResult.Infeasible(
            certificate(result.certificate, evidence, QuadraticClaim.Infeasible), result.sense, result.stats, evidence,
        )
    }

    private fun bestFound(result: QuadraticOptimizationResult.BestFound): QuadraticOptimizationResult.BestFound {
        val evidence = completion(SourceClaim.Witness(result.incumbent.sample), result.incumbent.sample)
        return QuadraticOptimizationResult.BestFound(
            result.incumbent, result.bound?.let { bound(it, result.incumbent.sample) },
            result.reason, result.sense, result.stats, evidence,
        )
    }

    private fun completion(
        claim: SourceClaim, sample: Sample?, engineDerivation: SourceDerivation? = null,
    ): SourceResultEvidence {
        val proof = derivation ?: when {
            claim is SourceClaim.Witness -> SourceDerivation.SourceWitness
            claim is SourceClaim.Optimal && boundDerivation != null ->
                SourceDerivation.Optimum(SourceDerivation.SourceWitness, boundDerivation)
            else -> engineDerivation ?: SourceDerivation.Unavailable(
                "quadratic traversal or objective completion derivation not captured",
            )
        }
        return payload(claim, proof, sample)
    }

    private fun payload(claim: SourceClaim, proof: SourceDerivation, sample: Sample?): SourceResultEvidence {
        val derivations = ArrayList<SourceEvidence>()
        if (sample != null && claim !is SourceClaim.Witness) {
            derivations.add(SourceEvidence(context.scope, SourceClaim.Witness(sample), SourceDerivation.SourceWitness))
        }
        derivations.add(SourceEvidence(context.scope, claim, proof))
        return SourceResultEvidence(context.scope, derivations, listOf(SourceEngineAttestation(producer, claim)))
            .withMappings(context.scope, mappings)
    }

    private fun bound(value: QuadraticObjectiveBound, sample: Sample?): QuadraticObjectiveBound {
        val claim = SourceClaim.Bound(SourceValue.Algebraic(value.value), value.strict)
        val proof = boundDerivation ?: (derivation as? SourceDerivation.Optimum)?.bound
            ?: value.certificate.derivation
            ?: SourceDerivation.Unavailable("quadratic global bound derivation not captured")
        return QuadraticObjectiveBound(value.value, value.strict,
            certificate(value.certificate, payload(claim, proof, sample), QuadraticClaim.Bound))
    }

    private fun certificate(
        value: QuadraticCertificate, evidence: SourceResultEvidence, kind: QuadraticClaim,
    ): QuadraticCertificate = QuadraticCertificate.captured(value, kind, evidence)
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

internal fun QuadraticOptimizationResult.capturedScopes(): List<SourceScope> {
    val scopes = ArrayList<SourceScope>()
    capturedEvidence()?.scope?.let { scopes.add(it) }
    when (this) {
        is QuadraticOptimizationResult.Optimal -> {
            certificate.scope?.let { scopes.add(it) }
            bound.certificate.scope?.let { scopes.add(it) }
        }
        is QuadraticOptimizationResult.UnattainedLimit -> {
            certificate.scope?.let { scopes.add(it) }
            bound.certificate.scope?.let { scopes.add(it) }
        }
        is QuadraticOptimizationResult.Unbounded -> certificate.scope?.let { scopes.add(it) }
        is QuadraticOptimizationResult.Infeasible -> certificate.scope?.let { scopes.add(it) }
        is QuadraticOptimizationResult.BestFound -> bound?.certificate?.scope?.let { scopes.add(it) }
        is QuadraticOptimizationResult.Unknown -> bound?.certificate?.scope?.let { scopes.add(it) }
    }
    return scopes
}

private fun QuadraticOptimizationResult.capturedEvidence(): SourceResultEvidence? = when (this) {
    is QuadraticOptimizationResult.Optimal -> evidence
    is QuadraticOptimizationResult.UnattainedLimit -> evidence
    is QuadraticOptimizationResult.Unbounded -> evidence
    is QuadraticOptimizationResult.Infeasible -> evidence
    is QuadraticOptimizationResult.BestFound -> evidence
    is QuadraticOptimizationResult.Unknown -> evidence
}

internal fun QuadraticOptimizationResult.capturedInvocations(): List<QuadraticEngineInvocation> {
    val certificates = when (this) {
        is QuadraticOptimizationResult.Optimal -> listOf(certificate, bound.certificate)
        is QuadraticOptimizationResult.UnattainedLimit -> listOf(certificate, bound.certificate)
        is QuadraticOptimizationResult.Unbounded -> listOf(certificate)
        is QuadraticOptimizationResult.Infeasible -> listOf(certificate)
        is QuadraticOptimizationResult.BestFound -> listOfNotNull(bound?.certificate)
        is QuadraticOptimizationResult.Unknown -> listOfNotNull(bound?.certificate)
    }
    return certificates.mapNotNull { it.invocation }
}

internal fun QuadraticOptimizationResult.capturedEvidencePackages(): List<SourceResultEvidence> {
    val certificates = when (this) {
        is QuadraticOptimizationResult.Optimal -> listOf(certificate, bound.certificate)
        is QuadraticOptimizationResult.UnattainedLimit -> listOf(certificate, bound.certificate)
        is QuadraticOptimizationResult.Unbounded -> listOf(certificate)
        is QuadraticOptimizationResult.Infeasible -> listOf(certificate)
        is QuadraticOptimizationResult.BestFound -> listOfNotNull(bound?.certificate)
        is QuadraticOptimizationResult.Unknown -> listOfNotNull(bound?.certificate)
    }
    return listOfNotNull(capturedEvidence()) + certificates.mapNotNull { it.evidence }
}
