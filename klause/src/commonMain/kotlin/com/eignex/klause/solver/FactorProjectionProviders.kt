package com.eignex.klause.solver

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.BuiltInScoringProjectionProvider
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.lp.BuiltInRelaxationProjectionProvider
import com.eignex.klause.lp.RelaxationProjectionProvider
import com.eignex.klause.propagation.BuiltInPropagationProjectionProvider
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncodingTarget

/**
 * Engine-owned mechanism selection for one preparation. Providers borrow immutable factor data;
 * their selected factories must keep mutable mechanisms private to each live engine.
 */
class FactorProjectionProviders(
    /** Selects finite deduction and its assignment-check authority. */
    val propagation: PropagationProjectionProvider = BuiltInPropagationProjectionProvider,
    /** Selects local-search scoring and its assignment-check authority. */
    val scoring: ScoringProjectionProvider = BuiltInScoringProjectionProvider,
    /** Selects sound model-facing linear relaxation emission. */
    val relaxation: RelaxationProjectionProvider = BuiltInRelaxationProjectionProvider,
    /** Selects complete source relation installation in shared arithmetic components. */
    val encoder: EncoderProvider,
    encodingTargets: Set<EncodingTarget> = emptySet(),
) {
    /** Construct the built-in provider selection. */
    constructor() : this(BuiltInPropagationProjectionProvider, BuiltInScoringProjectionProvider,
        BuiltInRelaxationProjectionProvider, BuiltInEncoderProvider)

    /** Preserve the original provider constructor and its built-in theory selection. */
    constructor(
        propagation: PropagationProjectionProvider = BuiltInPropagationProjectionProvider,
        scoring: ScoringProjectionProvider = BuiltInScoringProjectionProvider,
        relaxation: RelaxationProjectionProvider = BuiltInRelaxationProjectionProvider,
    ) : this(propagation, scoring, relaxation, BuiltInEncoderProvider)

    private val selectedEncodingTargets = EncodingTarget.entries.toSet() + encodingTargets

    /** Consumer languages captured at preparation, including every built-in target. */
    val encodingTargets: Set<EncodingTarget> get() = selectedEncodingTargets.toSet()

    /** Built-in mechanisms with their existing source behavior and numerical gates. */
    companion object {
        /** Shared stateless selectors; selected mutable work belongs to each engine. */
        val Default: FactorProjectionProviders = FactorProjectionProviders()
    }

    /** Capture selected descriptors for this immutable source, before opening mutable engine state. */
    fun prepare(problem: Problem): PreparedFactorProjections = PreparedFactorProjections(problem, this)

    internal fun capabilities(factor: Factor): FactorExecutionCapabilities {
        if (propagation === BuiltInPropagationProjectionProvider && scoring === BuiltInScoringProjectionProvider &&
            relaxation === BuiltInRelaxationProjectionProvider
        ) return factor.defaultExecutionCapabilities()
        val deduction = propagation.select(factor)
        val score = scoring.select(factor)
        val rows = relaxation.select(factor)
        return FactorExecutionCapabilities(
            deduction?.capability ?: PropagationCapability.UNSUPPORTED,
            deduction?.assignmentCheck ?: AssignmentCheckCapability.UNSUPPORTED,
            score?.assignmentCheck ?: AssignmentCheckCapability.UNSUPPORTED,
            score?.capability ?: ScoringCapability.UNSUPPORTED,
            rows?.capability ?: RelaxationCapability.NONE,
            deduction?.inertReason ?: score?.inertReason,
        )
    }
}
