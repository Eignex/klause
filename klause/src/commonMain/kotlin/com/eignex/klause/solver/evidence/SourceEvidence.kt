package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample

/** An exact affine inequality `sum(coefficients * coordinates) <= rhs`. */
class SourceInequality(
    coefficients: Map<SourceCoordinate, BigFraction>,
    /** Exact right-hand side. */
    val rhs: BigFraction,
    /** Whether equality is excluded. */
    val strict: Boolean = false,
) {
    private val captured = coefficients.filterValues { !it.isZero }.toMap()
    /** Nonzero coefficients in original source coordinates. */
    val coefficients: Map<SourceCoordinate, BigFraction> get() = captured.toMap()

    internal fun contradicts(): Boolean = captured.isEmpty() && (rhs < BigFraction.ZERO || (rhs.isZero && strict))

    internal fun entails(other: SourceInequality): Boolean = captured == other.captured &&
        (rhs < other.rhs || (rhs == other.rhs && (strict || !other.strict)))

    override fun equals(other: Any?): Boolean = other is SourceInequality &&
        captured == other.captured && rhs == other.rhs && strict == other.strict

    override fun hashCode(): Int = 31 * (31 * captured.hashCode() + rhs.hashCode()) + strict.hashCode()
}

/** Typed mathematical claim about an original-source invocation. */
sealed interface SourceClaim {
    /** A complete source assignment. Its coordinates are independently checked. */
    data class Witness(
        /** Complete original-source coordinates. */
        val sample: Sample,
    ) : SourceClaim
    /** Global objective bound in source units. */
    data class Bound(
        /** Exact original-source objective bound. */
        val value: SourceValue,
        /** Whether every feasible objective lies strictly beyond the bound. */
        val strict: Boolean = false,
    ) : SourceClaim {
        /** Construct a rational objective bound without converting to floating point. */
        constructor(value: BigFraction, strict: Boolean = false) : this(SourceValue.Rational(value), strict)
    }
    /** A feasible point attaining a proved global bound in source objective units. */
    data class Optimal(
        /** Complete original-source witness. */
        val sample: Sample,
        /** Exact objective value attaining the global bound. */
        val value: SourceValue,
    ) : SourceClaim
    /** An exact unattained infimum or supremum, with a feasible source point. */
    data class Limit(
        /** Complete feasible original-source point. */
        val sample: Sample,
        /** Exact unattained objective limit. */
        val value: SourceValue,
    ) : SourceClaim
    /** A disjunction of original-source Boolean literals. */
    class Clause(literals: Map<Int, Boolean>) : SourceClaim {
        private val captured = literals.toMap()
        /** Boolean coordinates and the values satisfying their literals. */
        val literals: Map<Int, Boolean> get() = captured.toMap()

        init { require(captured.keys.all { it >= 0 }) }

        override fun equals(other: Any?): Boolean = other is Clause && captured == other.captured
        override fun hashCode(): Int = captured.hashCode()
    }
    /** Typed source-specific claims whose semantics belong to the source checker. */
    interface Extension : SourceClaim
    /** No assignment exists in this invocation. */
    data object Refutation : SourceClaim
    /** Unlimited improvement from a feasible source point. */
    class Ray(
        /** Complete feasible original-source base point. */
        val sample: Sample,
        direction: Map<SourceCoordinate, BigFraction>,
    ) : SourceClaim {
        private val captured = direction.toMap()
        /** Exact direction in original source coordinates. */
        val direction: Map<SourceCoordinate, BigFraction> get() = captured.toMap()
    }
    /** A justified source inequality, suitable for bound or clause import. */
    data class Consequence(
        /** Exact inequality established in this source invocation. */
        val inequality: SourceInequality,
    ) : SourceClaim
}

/** A checkable derivation, or an explicit reason that capture lacks one. */
sealed interface SourceDerivation {
    /** Check complete coordinates against original declared semantics. */
    data object SourceWitness : SourceDerivation
    /** Check a point and direction against original declared semantics. */
    data object SourceRay : SourceDerivation
    /** A cited original row or declaration. */
    data class Premise(
        /** Original-source premise identifier resolved by the consumer. */
        val id: String,
    ) : SourceDerivation
    /** A witness and an equal global bound establishing an attained optimum. */
    data class Optimum(
        /** Complete source witness check. */
        val witness: SourceDerivation,
        /** Global bound equal to the witness objective. */
        val bound: SourceDerivation,
    ) : SourceDerivation
    /** Discharge exactly the negations of a Boolean clause using a checked conditional refutation. */
    data class Discharge(
        /** Original-source refutation under precisely the negated clause literals. */
        val refutation: SourceEvidence,
    ) : SourceDerivation
    /** Source-specific derivation data; an unsupported semantics checker must decline it. */
    interface Extension : SourceDerivation
    /** An inclusive numeric invocation restriction. */
    data class Assumption(
        /** Original-source numeric coordinate. */
        val coordinate: SourceCoordinate,
        /** Whether to cite its upper or lower invocation bound. */
        val upper: Boolean,
    ) : SourceDerivation
    /** A nonnegative exact combination of inequalities. */
    class Combination(terms: List<Term>) : SourceDerivation {
        private val captured = terms.toList()
        /** Derivations and their nonnegative weights. */
        val terms: List<Term> get() = captured.toList()
        /** One weighted premise. */
        data class Term(
            /** Exact nonnegative multiplier. */
            val weight: BigFraction,
            /** Cited inequality derivation. */
            val derivation: SourceDerivation,
        )
    }
    /** Exhaustive Boolean alternatives under the current invocation. */
    data class BooleanSplit(
        /** Original-source Boolean coordinate. */
        val id: Int,
        /** Coverage when the Boolean is false. */
        val whenFalse: SourceDerivation,
        /** Coverage when the Boolean is true. */
        val whenTrue: SourceDerivation,
    ) : SourceDerivation
    /** Exhaustive integer alternatives `x <= threshold` and `x >= threshold + 1`. */
    data class IntegerSplit(
        /** Original-source integer coordinate. */
        val coordinate: SourceCoordinate,
        /** Integral threshold between adjacent alternatives. */
        val threshold: BigFraction,
        /** Coverage for values at or below the threshold. */
        val below: SourceDerivation,
        /** Coverage for values at or above the threshold plus one. */
        val above: SourceDerivation,
    ) : SourceDerivation
    /** Recheck an imported claim and all its premises under a compatible scope. */
    data class Import(
        /** Imported claim and complete cited derivation. */
        val evidence: SourceEvidence,
    ) : SourceDerivation
    /** The engine decided the claim without retaining a checkable derivation. */
    data class Unavailable(
        /** Missing derivation or unsupported capture mechanism. */
        val reason: String,
    ) : SourceDerivation {
        init { require(reason.isNotBlank()) }
    }
}

/** Captured scope, mathematical claim and supporting derivation. */
data class SourceEvidence(
    /** Original-source invocation and objective scope. */
    val scope: SourceScope,
    /** Mathematical claim requiring this derivation. */
    val claim: SourceClaim,
    /** Original-source supporting derivation or explicit missing capture. */
    val derivation: SourceDerivation,
)

/** Outcome of checking derivations against original source semantics. */
sealed interface SourceEvidenceCheck {
    /** Every required derivation and source check succeeded. */
    data object Checked : SourceEvidenceCheck
    /** Evidence disagrees with the claim or original source. */
    data class Rejected(
        /** Disagreement with the claim or original source. */
        val reason: String,
    ) : SourceEvidenceCheck
    /** Required evidence or a supported source checker is unavailable. */
    data class Unavailable(
        /** Missing evidence, unsupported checking or exhausted allowance. */
        val reason: String,
    ) : SourceEvidenceCheck
}
