package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample

/** An exact affine inequality `sum(coefficients * coordinates) <= rhs`. */
class SourceInequality(
    coefficients: Map<SourceCoordinate, BigFraction>,
    val rhs: BigFraction,
    val strict: Boolean = false,
) {
    private val captured = coefficients.filterValues { !it.isZero }.toMap()
    /** Nonzero coefficients in original source coordinates. */
    val coefficients: Map<SourceCoordinate, BigFraction> get() = captured.toMap()

    internal fun contradicts(): Boolean = captured.isEmpty() && (rhs < BigFraction.ZERO || rhs.isZero && strict)

    internal fun entails(other: SourceInequality): Boolean = captured == other.captured &&
        (rhs < other.rhs || rhs == other.rhs && (strict || !other.strict))

    override fun equals(other: Any?): Boolean = other is SourceInequality &&
        captured == other.captured && rhs == other.rhs && strict == other.strict

    override fun hashCode(): Int = 31 * (31 * captured.hashCode() + rhs.hashCode()) + strict.hashCode()
}

/** Typed mathematical claim about an original-source invocation. */
sealed interface SourceClaim {
    /** A complete source assignment. Its coordinates are independently checked. */
    data class Witness(val sample: Sample) : SourceClaim
    /** Global objective bound in source units. */
    data class Bound(val value: BigFraction, val strict: Boolean = false) : SourceClaim
    /** No assignment exists in this invocation. */
    data object Refutation : SourceClaim
    /** Unlimited improvement from a feasible source point. */
    class Ray(val sample: Sample, direction: Map<SourceCoordinate, BigFraction>) : SourceClaim {
        private val captured = direction.toMap()
        /** Exact direction in original source coordinates. */
        val direction: Map<SourceCoordinate, BigFraction> get() = captured.toMap()
    }
    /** A justified source inequality, suitable for bound or clause import. */
    data class Consequence(val inequality: SourceInequality) : SourceClaim
}

/** A checkable derivation, or an explicit reason that capture lacks one. */
sealed interface SourceDerivation {
    /** Check complete coordinates against original declared semantics. */
    data object SourceWitness : SourceDerivation
    /** Check a point and direction against original declared semantics. */
    data object SourceRay : SourceDerivation
    /** A cited original row or declaration. */
    data class Premise(val id: String) : SourceDerivation
    /** An inclusive numeric invocation restriction. */
    data class Assumption(val coordinate: SourceCoordinate, val upper: Boolean) : SourceDerivation
    /** A nonnegative exact combination of inequalities. */
    class Combination(terms: List<Term>) : SourceDerivation {
        private val captured = terms.toList()
        /** Derivations and their nonnegative weights. */
        val terms: List<Term> get() = captured.toList()
        /** One weighted premise. */
        data class Term(val weight: BigFraction, val derivation: SourceDerivation)
    }
    /** Exhaustive Boolean alternatives under the current invocation. */
    data class BooleanSplit(val id: Int, val whenFalse: SourceDerivation, val whenTrue: SourceDerivation) : SourceDerivation
    /** Exhaustive integer alternatives `x <= threshold` and `x >= threshold + 1`. */
    data class IntegerSplit(
        val coordinate: SourceCoordinate,
        val threshold: BigFraction,
        val below: SourceDerivation,
        val above: SourceDerivation,
    ) : SourceDerivation
    /** Recheck an imported claim and all its premises under a compatible scope. */
    data class Import(val evidence: SourceEvidence) : SourceDerivation
    /** The engine decided the claim without retaining a checkable derivation. */
    data class Unavailable(val reason: String) : SourceDerivation {
        init { require(reason.isNotBlank()) }
    }
}

/** Captured scope, mathematical claim and supporting derivation. */
data class SourceEvidence(val scope: SourceScope, val claim: SourceClaim, val derivation: SourceDerivation)

/** Outcome of checking derivations against original source semantics. */
sealed interface SourceEvidenceCheck {
    /** Every required derivation and source check succeeded. */
    data object Checked : SourceEvidenceCheck
    /** Evidence disagrees with the claim or original source. */
    data class Rejected(val reason: String) : SourceEvidenceCheck
    /** Required evidence or a supported source checker is unavailable. */
    data class Unavailable(val reason: String) : SourceEvidenceCheck
}
