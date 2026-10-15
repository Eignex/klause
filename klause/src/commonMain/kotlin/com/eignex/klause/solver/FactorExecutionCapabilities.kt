package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.*
import com.eignex.klause.factor.bool.*
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.*
import com.eignex.klause.factor.objective.ObjectiveBoundFactor
import com.eignex.klause.factor.scheduling.*
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.factor.table.*
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.difference.DifferenceSystem

/** Deductive participation declared by a selected propagation projection. */
enum class PropagationCapability {
    /** No finite deduction implementation is available. */
    UNSUPPORTED,
    /** Deduction is deliberately absent; another source check must enforce the factor. */
    INERT,
    /** Deductions preserve every source solution. */
    SOUND_FILTERING,
    /** Deductions filter finite interval bounds. */
    BOUNDS_FILTERING,
    /** Deductions can remove interior finite-domain values. */
    DOMAIN_FILTERING,
}

/** Authority of an assignment check supplied by a projection. */
enum class AssignmentCheckCapability {
    /** No assignment check is supplied by this role. */
    UNSUPPORTED,
    /** The role checks the exact source relation at a pinned assignment. */
    EXACT,
    /** A separate exact source completion must certify the assignment. */
    SOURCE_CERTIFICATION,
    /** Other installed source rows enforce this redundant role. */
    REDUNDANT,
}

/** Local-search participation declared by a selected scoring projection. */
enum class ScoringCapability {
    /** No scoring implementation is available. */
    UNSUPPORTED,
    /** This role contributes no score and requires another source check. */
    INERT,
    /** Scores and move deltas agree on discrete violation degree. */
    GRADED,
    /** Scores guide search but require exact source certification. */
    HEURISTIC,
}

/** Linear relaxation authority declared by a selected provider. */
enum class RelaxationCapability {
    /** This role emits no relaxation rows. */
    NONE,
    /** Emitted rows contain every source solution under their stated premises. */
    SOUND,
}

internal data class FactorExecutionCapabilities(
    val propagation: PropagationCapability,
    val propagationCheck: AssignmentCheckCapability,
    val assignmentCheck: AssignmentCheckCapability,
    val scoring: ScoringCapability,
    val relaxation: RelaxationCapability,
    val inertReason: String? = null,
)

private val finiteFiltering = FactorExecutionCapabilities(
    PropagationCapability.SOUND_FILTERING,
    AssignmentCheckCapability.EXACT,
    AssignmentCheckCapability.EXACT,
    ScoringCapability.GRADED,
    RelaxationCapability.NONE,
)
private val finiteBounds = finiteFiltering.copy(propagation = PropagationCapability.BOUNDS_FILTERING)
private val finiteDomains = finiteFiltering.copy(propagation = PropagationCapability.DOMAIN_FILTERING)
private val realArithmetic = FactorExecutionCapabilities(
    PropagationCapability.INERT,
    AssignmentCheckCapability.SOURCE_CERTIFICATION,
    AssignmentCheckCapability.SOURCE_CERTIFICATION,
    ScoringCapability.HEURISTIC,
    RelaxationCapability.SOUND,
    "Continuous arithmetic is enforced by exact source certification, outside finite CP.",
)

private val domainRelaxation = finiteDomains.copy(relaxation = RelaxationCapability.SOUND)
private val boundRelaxation = finiteBounds.copy(relaxation = RelaxationCapability.SOUND)
private val filteringRelaxation = finiteFiltering.copy(relaxation = RelaxationCapability.SOUND)
private val objectiveOverlay = finiteFiltering.copy(
    propagation = PropagationCapability.INERT,
    propagationCheck = AssignmentCheckCapability.UNSUPPORTED,
    assignmentCheck = AssignmentCheckCapability.SOURCE_CERTIFICATION,
    scoring = ScoringCapability.HEURISTIC,
    inertReason = "Objective cuts are enforced by the search objective, outside factor propagation.",
)
private val redundantRows = finiteFiltering.copy(
    assignmentCheck = AssignmentCheckCapability.REDUNDANT,
    scoring = ScoringCapability.INERT,
    inertReason = "Posted with the source rows that enforce the same assignments in local search.",
)
private val symmetryRestriction = redundantRows.copy(
    inertReason = "Symmetry restrictions select CP representatives without restricting source witnesses.",
)

// Filtering describes the implemented kind of deductions, not a promise of consistency at every size.
// SOUND declares a relaxation family; its engine still applies domain, resource and feature gates.
internal fun Factor.builtInExecutionCapabilities(): FactorExecutionCapabilities? = when (this) {
    is Table -> if (hi == null) domainRelaxation else finiteDomains
    is Element, is Mdd, is Regular, is GlobalCardinality, is NValue, is SymmetricAllDifferent -> domainRelaxation
    is AllDifferent -> if (boundsConsistent && presents.isEmpty() && exceptSet.isEmpty()) {
        boundRelaxation
    } else {
        domainRelaxation
    }
    is Abs -> domainRelaxation
    is Inverse, is ValuePrecede -> finiteDomains
    is Power -> if (base is Power.Coordinate.Integer) finiteDomains else FactorExecutionCapabilities(
        PropagationCapability.UNSUPPORTED, AssignmentCheckCapability.UNSUPPORTED,
        AssignmentCheckCapability.UNSUPPORTED, ScoringCapability.UNSUPPORTED, RelaxationCapability.NONE,
    )
    is ArrayMinMax, is Product, is Increasing, is ReifiedLinear -> boundRelaxation
    is Linear -> if (constants is RealConstants) realArithmetic else boundRelaxation
    is Clause, is Cardinality, is PseudoBoolean, is ReifiedCardinality, is ReifiedPseudoBoolean,
    is Circuit, is Cumulative, is Diffn,
    -> filteringRelaxation
    is ComparisonClause, is Xor, is LexLess, is Sort -> finiteFiltering
    is RealProduct, is ReifiedRealLinear -> realArithmetic
    is ContinuousProduct -> FactorExecutionCapabilities(
        PropagationCapability.UNSUPPORTED,
        AssignmentCheckCapability.UNSUPPORTED,
        AssignmentCheckCapability.UNSUPPORTED,
        ScoringCapability.UNSUPPORTED,
        RelaxationCapability.NONE,
    )
    is ObjectiveBoundFactor -> objectiveOverlay
    is GaussianXor, is DifferenceSystem -> redundantRows
    is SymmetryHandling -> symmetryRestriction
    else -> null
}

internal fun Factor.defaultExecutionCapabilities(): FactorExecutionCapabilities = builtInExecutionCapabilities()
    ?: FactorExecutionCapabilities(
        propagation = if (this is Propagator) {
            PropagationCapability.SOUND_FILTERING
        } else {
            PropagationCapability.UNSUPPORTED
        },
        propagationCheck = AssignmentCheckCapability.UNSUPPORTED,
        assignmentCheck = if (this is Invariant) {
            AssignmentCheckCapability.EXACT
        } else {
            AssignmentCheckCapability.UNSUPPORTED
        },
        scoring = when (this) {
            is Invariant -> ScoringCapability.GRADED
            is Propagator -> ScoringCapability.INERT
            else -> ScoringCapability.UNSUPPORTED
        },
        relaxation = if (impliedLinearRows.isNotEmpty()) RelaxationCapability.SOUND else RelaxationCapability.NONE,
        inertReason = if (this is Propagator && this !is Invariant) "Custom propagation-only factor." else null,
    )

internal fun Factor.executionCapabilities(
    providers: FactorProjectionProviders = FactorProjectionProviders.Default,
): FactorExecutionCapabilities = providers.capabilities(this)
