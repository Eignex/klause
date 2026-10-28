package com.eignex.klause.lp.optimization

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.LinearSourceSemanticsChecker
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceIntegerAggregateSemantics
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceSemanticsChecker
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.Cancellation

/** A checked affine path `endpoint + t * (base - endpoint)` for rational `0 < t <= 1`. */
class LinearApproach(
    /** Closure point with the exact limiting objective. */
    val endpoint: Sample,
    /** Strict global bound proving nonattainment. */
    val strictBound: SourceDerivation,
    /** Feasible approach base, or the limit claim's feasible point when absent. */
    val base: Sample? = null,
) : SourceDerivation.Extension

/** An affine objective bound checked after substituting the invocation's complete Boolean pins. */
class LinearPinnedObjective(
    /** Original-source combination, checked under the fixed Boolean substitution. */
    val bound: SourceDerivation,
) : SourceDerivation.Extension

/** A contradiction checked after substituting the invocation's complete Boolean pins. */
class LinearPinnedRefutation(
    /** Original-source contradiction combination. */
    val conflict: SourceDerivation,
) : SourceDerivation.Extension

/** Checks linear optimization evidence against complete, consumer-owned exact affine source semantics. */
class LinearAuthoritySemantics(private val source: SourceProofContext) : SourceSemanticsChecker {
    private val exact = LinearSourceSemanticsChecker(source.coordinates, source.premises.values.toList(),
        source.objective, source.objectiveConstant)

    override fun witness(sample: Sample, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck =
        exact.witness(sample, scope, cancellation).let { checked ->
            if (checked != SourceEvidenceCheck.Checked) checked
            else source.semanticsChecker?.witness(sample, scope, cancellation) ?: checked
        }

    override fun objectiveEquals(
        sample: Sample, value: SourceValue, scope: SourceScope, cancellation: Cancellation,
    ): SourceEvidenceCheck = if (source.affineObjectiveAvailable) {
        exact.objectiveEquals(sample, value, scope, cancellation)
    } else SourceEvidenceCheck.Unavailable("exact affine objective unavailable")

    override fun ray(claim: SourceClaim.Ray, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck {
        val base = witness(claim.sample, scope, cancellation)
        return if (base == SourceEvidenceCheck.Checked) exact.ray(claim, scope, cancellation) else base
    }

    override fun derivation(
        claim: SourceClaim, derivation: SourceDerivation.Extension, scope: SourceScope, cancellation: Cancellation,
    ): SourceEvidenceCheck {
        if (derivation is LinearActiveRefutation) {
            if (!compatible(scope) || (claim != SourceClaim.Refutation && claim !is SourceClaim.Bound) ||
                (claim is SourceClaim.Bound && scope.objective == null)) {
                return SourceEvidenceCheck.Rejected("active rows establish only a scoped contradiction")
            }
            val numeric = source.atScope(scope).activeNumericContext(cancellation,
                retainGuardedRows = derivation.alternatives)
                ?: return SourceEvidenceCheck.Unavailable("active row capture incomplete")
            return SourceEvidenceVerifier(numeric).verify(
                SourceEvidence(scope, SourceClaim.Refutation, derivation.conflict), cancellation)
        }
        if (claim != SourceClaim.Refutation && !source.affineObjectiveAvailable) {
            return SourceEvidenceCheck.Unavailable("exact affine objective unavailable")
        }
        if ((claim == SourceClaim.Refutation || claim is SourceClaim.Bound) &&
            derivation is LinearPinnedRefutation
        ) {
            if (!compatible(scope) || source.coordinates.any {
                    it.kind == SourceCoordinate.Kind.Boolean && it.id !in scope.condition.bools
                }
            ) return SourceEvidenceCheck.Rejected("linear refutation requires complete Boolean scope")
            return SourceEvidenceVerifier(source.atScope(scope).numericContext()).verify(
                SourceEvidence(scope, claim, derivation.conflict), cancellation)
        }
        if (claim is SourceClaim.Bound && derivation is LinearPinnedObjective) {
            if (!compatible(scope) || source.coordinates.any {
                    it.kind == SourceCoordinate.Kind.Boolean && it.id !in scope.condition.bools
                }
            ) return SourceEvidenceCheck.Rejected("linear bound requires complete Boolean scope")
            return SourceEvidenceVerifier(source.atScope(scope).numericContext()).verify(
                SourceEvidence(scope, claim, derivation.bound), cancellation)
        }
        if (claim !is SourceClaim.Limit || derivation !is LinearApproach) {
            return SourceEvidenceCheck.Unavailable("unsupported linear authority derivation")
        }
        if (!compatible(scope)) return SourceEvidenceCheck.Rejected("linear approach has a foreign scope")
        val incumbent = witness(claim.sample, scope, cancellation)
        if (incumbent != SourceEvidenceCheck.Checked) return incumbent
        val base = derivation.base ?: claim.sample
        val feasible = witness(base, scope, cancellation)
        if (feasible != SourceEvidenceCheck.Checked) return feasible
        val bound = SourceEvidenceVerifier(source.atScope(scope).withLinearAuthority()).verify(SourceEvidence(scope,
            SourceClaim.Bound(claim.value, strict = true), derivation.strictBound), cancellation)
        if (bound != SourceEvidenceCheck.Checked) return bound
        val endpoint = derivation.endpoint
        if (endpoint.numBoolVars != base.numBoolVars || endpoint.numIntVars != base.numIntVars ||
            endpoint.numRealVars != base.numRealVars
        ) return SourceEvidenceCheck.Rejected("linear approach coordinates are incomplete")
        for (id in 0 until endpoint.numBoolVars) {
            if (cancellation()) return SourceEvidenceCheck.Unavailable("linear approach check cancelled")
            if (endpoint.boolValue(id) != base.boolValue(id)) {
                return SourceEvidenceCheck.Rejected("linear approach changes a Boolean coordinate")
            }
        }
        for (id in 0 until endpoint.numIntVars) {
            if (cancellation()) return SourceEvidenceCheck.Unavailable("linear approach check cancelled")
            if (endpoint.exactIntValue(id) != base.exactIntValue(id)) {
                return SourceEvidenceCheck.Rejected("linear approach changes an integer coordinate")
            }
        }
        val closure = LinearSourceSemanticsChecker(source.coordinates, source.premises.values.map {
            SourcePremise(SourceInequality(it.inequality.coefficients, it.inequality.rhs), it.guards, it.integerPins)
        }, source.objective, source.objectiveConstant)
        // Positive mixtures preserve strict affine rows. Fixed discrete coordinates preserve the source lattice.
        return closure.objectiveEquals(endpoint, claim.value, scope, cancellation)
    }

    private fun compatible(scope: SourceScope): Boolean = scope.revision == source.scope.revision &&
        scope.objective == source.scope.objective && scope.condition.implies(source.scope.condition)
}

/**
 * Enable inspectable affine optimization derivations for a complete exact affine source context.
 * Every numeric source constraint and the exact affine objective must be represented by this context;
 * omitted nonlinear constraints or rounded source correspondence do not acquire authority here.
 */
fun SourceProofContext.withLinearAuthority(): SourceProofContext = SourceProofContext(
    scope, coordinates, premises, objective.takeIf { affineObjectiveAvailable }, objectiveConstant,
    LinearAggregateAuthoritySemantics(LinearAuthoritySemantics(this)), semantics,
)

private class LinearAggregateAuthoritySemantics(delegate: SourceSemanticsChecker) :
    SourceSemanticsChecker by delegate, SourceIntegerAggregateSemantics

internal fun SourceProofContext.atScope(scope: SourceScope): SourceProofContext = SourceProofContext(
    scope, coordinates, premises, objective.takeIf { affineObjectiveAvailable }, objectiveConstant, semanticsChecker,
    semantics,
)

internal fun SourceProofContext.linearCoordinatesSupported(): Boolean = coordinates.groupBy { it.kind }.values.all {
    it.map { coordinate -> coordinate.id }.sorted() == it.indices.toList()
}

internal fun SourceProofContext.numericCoordinates(): List<SourceCoordinate> = coordinates
    .filter { it.kind != SourceCoordinate.Kind.Boolean }.sortedWith(compareBy({ it.kind.ordinal }, { it.id }))

internal fun SourceProofContext.pinnedConstant(): BigFraction = objective.entries.fold(objectiveConstant) {
        sum, (id, value) ->
    if (id.kind == SourceCoordinate.Kind.Boolean) {
        sum + if (scope.condition.bools.getValue(id.id)) value else BigFraction.ZERO
    } else sum
}

internal fun SourceProofContext.numericContext(): SourceProofContext {
    val rows = premises.mapValues { (_, premise) ->
        val boolTerms = premise.inequality.coefficients.filterKeys { it.kind == SourceCoordinate.Kind.Boolean }
        val offset = boolTerms.entries.fold(BigFraction.ZERO) { sum, (id, value) ->
            sum + if (scope.condition.bools.getValue(id.id)) value else BigFraction.ZERO
        }
        SourcePremise(SourceInequality(premise.inequality.coefficients.filterKeys {
            it.kind != SourceCoordinate.Kind.Boolean
        }, premise.inequality.rhs - offset, premise.inequality.strict), premise.guards, premise.integerPins)
    }
    return SourceProofContext(scope, coordinates, rows, objective.filterKeys {
        it.kind != SourceCoordinate.Kind.Boolean
    }, pinnedConstant(), semantics = semantics)
}
