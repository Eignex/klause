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
    ): SourceEvidenceCheck = select(claim, context, cancellation, SourceProofAllowance(100_000, 128)).second

    internal fun project(
        claim: SourceClaim,
        context: SourceProofContext,
        cancellation: Cancellation,
        allowance: SourceProofAllowance = SourceProofAllowance(100_000, 128),
    ): SourceEvidence = select(claim, context, cancellation, allowance).first

    private fun select(
        claim: SourceClaim, context: SourceProofContext, cancellation: Cancellation, allowance: SourceProofAllowance,
    ): Pair<SourceEvidence, SourceEvidenceCheck> {
        fun absent(reason: String) = SourceEvidence(source, claim, SourceDerivation.Unavailable(reason)) to
            SourceEvidenceCheck.Unavailable(reason)
        if (context.scope != source) return SourceEvidence(source, claim,
            SourceDerivation.Unavailable("mapping has a foreign original invocation")) to
            SourceEvidenceCheck.Rejected("mapping has a foreign original invocation")
        val payload = target ?: return absent("reduced-source evidence capture unavailable")
        if (payload.derivations.any { it.scope != payload.scope }) {
            return absent("mapped evidence has mixed target scopes")
        }
        var stopped = false
        val limited = Cancellation {
            stopped = stopped || cancellation() || !allowance.consume(0)
            stopped
        }
        val translator = MappingProofTranslation(limited, allowance)
        val verifier = SourceEvidenceVerifier(context)
        var unavailable = absent("mapping has no captured target claim")
        for (terminal in payload.derivations) {
            if (limited()) return absent("mapping evidence capture allowance exhausted")
            if (claim is SourceClaim.Bound) {
                if (terminal.claim !is SourceClaim.Bound && terminal.claim !is SourceClaim.Optimal) continue
            } else if (claim != SourceClaim.Refutation || terminal.claim != SourceClaim.Refutation) continue
            val proof = try {
                translator.project(source, payload.scope, captured, terminal, claim)
            } catch (stop: MappingProofUnavailable) {
                unavailable = absent(stop.reason)
                continue
            }
            val evidence = SourceEvidence(source, claim, proof)
            when (val checked = verifier.verify(evidence, limited, allowance)) {
                SourceEvidenceCheck.Checked -> return evidence to checked
                is SourceEvidenceCheck.Rejected -> return evidence to checked
                is SourceEvidenceCheck.Unavailable -> unavailable = absent(checked.reason)
            }
        }
        return unavailable
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
        target: SourceScope,
        transformations: List<SourceTransformation?>,
        terminal: SourceEvidence,
        claim: SourceClaim,
    ): SourceDerivation {
        step(0)
        val bound = claim is SourceClaim.Bound
        if (claim is SourceClaim.Bound) matchBound(source, target, transformations, terminal.claim, claim)
        var proof = if (terminal.claim is SourceClaim.Optimal) optimumBound(terminal, target, 0)
            else terminal.derivation
        var scope = target
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

    private fun matchBound(
        source: SourceScope, target: SourceScope, transformations: List<SourceTransformation?>,
        terminal: SourceClaim, claim: SourceClaim.Bound,
    ) {
        val value = when (terminal) {
            is SourceClaim.Bound -> terminal.value
            is SourceClaim.Optimal -> terminal.value
            else -> missing("mapping has no target bound")
        }
        val strict = (terminal as? SourceClaim.Bound)?.strict ?: false
        if (source == target) {
            if (matchSourceClaims(SourceClaim.Bound(value, strict), claim, cancellation) !=
                SourceEvidenceCheck.Checked
            ) {
                missing("mapped bound does not match the requested original claim")
            }
            return
        }
        var rational = when (value) {
            is SourceValue.Rational -> value.value
            is SourceValue.Algebraic -> value.value.rational
            is SourceValue.Extension -> null
        } ?: missing("mapped non-rational bound adjustment unavailable")
        var scope = target
        for (stage in transformations.asReversed()) {
            step(0)
            val transformation = stage ?: missing("mapping transformation justification unavailable")
            if (transformation.target != scope) missing("mapping target revision or invocation does not match")
            rational = transformation.objectiveScale * rational + transformation.objectiveOffset
            scope = transformation.source
        }
        if (scope != source || matchSourceClaims(SourceClaim.Bound(rational, strict), claim, cancellation) !=
            SourceEvidenceCheck.Checked
        ) missing("mapped bound does not match the requested original claim")
    }

    private fun optimumBound(evidence: SourceEvidence, scope: SourceScope, depth: Int): SourceDerivation {
        step(depth)
        return when (val proof = evidence.derivation) {
            is SourceDerivation.Optimum -> proof.bound
            is SourceDerivation.Import -> {
                val imported = proof.evidence
                if (imported.scope.revision != scope.revision || imported.scope.objective != scope.objective ||
                    !scope.condition.implies(imported.scope.condition) ||
                    matchSourceClaims(imported.claim, evidence.claim, cancellation) != SourceEvidenceCheck.Checked
                ) missing("mapped optimum import has a foreign invocation or claim")
                optimumBound(imported, imported.scope, depth + 1)
            }
            else -> missing("mapped optimum has no actual bound derivation")
        }
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
