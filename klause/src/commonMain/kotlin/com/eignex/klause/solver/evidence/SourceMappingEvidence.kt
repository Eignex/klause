package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

/** Reduced-source evidence and optional ordered lowering or presolve justifications. */
class SourceMappingEvidence(
    /** Original invocation to which the mapping belongs. */
    val source: SourceScope,
    /** Actual retained reduced-source claims, including their imported derivations. */
    val target: SourceResultEvidence?,
    transformations: List<SourceTransformation?>,
) {
    private val captured = transformations.toList()
    /** Ordered source-to-target stages; a null entry represents missing capture. */
    val transformations: List<SourceTransformation?> get() = captured.toList()

    /** Translate supported citations into original premises and independently check the requested claim. */
    fun verify(
        claim: SourceClaim,
        context: SourceProofContext,
        cancellation: Cancellation = Cancellation.Never,
    ): SourceEvidenceCheck {
        if (context.scope != source) return SourceEvidenceCheck.Rejected("mapping has a foreign original invocation")
        val allowance = SourceProofAllowance(100_000, 128)
        return SourceEvidenceVerifier(context).verify(project(claim, cancellation, allowance), cancellation, allowance)
    }

    internal fun project(
        claim: SourceClaim,
        cancellation: Cancellation,
        allowance: SourceProofAllowance = SourceProofAllowance(100_000, 128),
    ): SourceEvidence {
        val proof = try {
            MappingProofTranslation(cancellation, allowance).project(source,
                target ?: missing("reduced-source evidence capture unavailable"), captured, claim)
        } catch (stop: MappingProofUnavailable) {
            SourceDerivation.Unavailable(stop.reason)
        }
        return SourceEvidence(source, claim, proof)
    }
}

private class MappingProofTranslation(
    private val cancellation: Cancellation,
    private val allowance: SourceProofAllowance,
) {

    private fun step(depth: Int) {
        if (cancellation() || !allowance.consume(depth)) missing("mapping evidence capture allowance exhausted")
    }

    fun project(
        source: SourceScope,
        target: SourceResultEvidence,
        transformations: List<SourceTransformation?>,
        claim: SourceClaim,
    ): SourceDerivation {
        step(0)
        if (claim != SourceClaim.Refutation && claim !is SourceClaim.Bound) {
            missing("mapping transfer supports original refutations and objective bounds")
        }
        if (target.derivations.any { it.scope != target.scope }) missing("mapped evidence has mixed target scopes")
        val bound = claim is SourceClaim.Bound
        val terminal = target.derivations.firstOrNull {
            if (bound) it.claim is SourceClaim.Bound || it.claim is SourceClaim.Optimal
            else it.claim == SourceClaim.Refutation
        } ?: missing("mapping has no captured target claim")
        var proof = if (terminal.claim is SourceClaim.Optimal) {
            (terminal.derivation as? SourceDerivation.Optimum)?.bound
                ?: missing("mapped optimum has no actual bound derivation")
        } else terminal.derivation
        var scope = target.scope
        if (scope == source) return proof
        if (transformations.isEmpty()) missing("mapping has no captured transformation stages")
        for (stage in transformations.asReversed()) {
            step(0)
            val transformation = stage ?: missing("mapping transformation justification unavailable")
            if (transformation.target != scope) missing("mapping target revision or invocation does not match")
            if (bound && (transformation.objectiveScale <= BigFraction.ZERO ||
                    transformation.source.objective == null || transformation.target.objective == null ||
                    transformation.source.objective.sense != transformation.target.objective.sense)
            ) missing("mapping objective direction or scale is incompatible")
            proof = translate(proof, transformation, scope, 0)
            if (bound && transformation.objectiveScale != BigFraction.ONE) {
                proof = SourceDerivation.Combination(listOf(
                    SourceDerivation.Combination.Term(transformation.objectiveScale, proof),
                ))
            }
            scope = transformation.source
        }
        if (scope != source) missing("mapping original revision or invocation does not match")
        return proof
    }

    private fun translate(
        proof: SourceDerivation, transformation: SourceTransformation, scope: SourceScope, depth: Int,
    ): SourceDerivation {
        step(depth)
        return when (proof) {
            is SourceDerivation.Premise -> transformation.premiseDerivations[proof.id]
                ?: missing("mapping original premise justification unavailable")
            is SourceDerivation.Assumption -> {
                val coordinate = proof.coordinate
                val side = if (proof.upper) "upper" else "lower"
                transformation.premiseDerivations["assumption/${coordinate.kind}/${coordinate.id}/$side"]
                    ?: missing("mapping invocation justification unavailable")
            }
            is SourceDerivation.Combination -> SourceDerivation.Combination(proof.terms.map {
                step(depth)
                SourceDerivation.Combination.Term(it.weight, translate(it.derivation, transformation, scope, depth + 1))
            })
            is SourceDerivation.Import -> {
                val imported = proof.evidence
                if (imported.scope.revision != scope.revision || imported.scope.objective != scope.objective ||
                    !scope.condition.implies(imported.scope.condition)
                ) missing("mapping import has a foreign revision, objective or stronger invocation")
                translate(imported.derivation, transformation, imported.scope, depth + 1)
            }
            is SourceDerivation.BooleanSplit -> {
                val coordinate = SourceCoordinate(SourceCoordinate.Kind.Boolean, proof.id)
                val image = transformation.images[coordinate]
                if (image?.constant != BigFraction.ZERO || image.coefficients != mapOf(coordinate to BigFraction.ONE)) {
                    missing("mapping Boolean coverage image unavailable")
                }
                SourceDerivation.BooleanSplit(proof.id,
                    translate(proof.whenFalse, transformation,
                        scope.copy(condition = scope.condition.pin(proof.id, false)), depth + 1),
                    translate(proof.whenTrue, transformation,
                        scope.copy(condition = scope.condition.pin(proof.id, true)), depth + 1))
            }
            is SourceDerivation.IntegerSplit -> {
                val image = transformation.images[proof.coordinate]
                if (image?.constant != BigFraction.ZERO ||
                    image.coefficients != mapOf(proof.coordinate to BigFraction.ONE)
                ) missing("mapping integer coverage image unavailable")
                SourceDerivation.IntegerSplit(proof.coordinate, proof.threshold,
                    translate(proof.below, transformation, scope.copy(condition =
                        scope.condition.bound(proof.coordinate, proof.threshold, true)), depth + 1),
                    translate(proof.above, transformation, scope.copy(condition =
                        scope.condition.bound(proof.coordinate, proof.threshold + BigFraction.ONE, false)), depth + 1))
            }
            is SourceDerivation.Unavailable -> missing(proof.reason)
            else -> missing("mapping source-specific or non-affine derivation transfer unavailable")
        }
    }
}

private class MappingProofUnavailable(val reason: String) : RuntimeException()
private fun missing(reason: String): Nothing = throw MappingProofUnavailable(reason)
