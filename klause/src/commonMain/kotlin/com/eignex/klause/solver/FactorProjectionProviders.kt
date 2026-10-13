package com.eignex.klause.solver

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.BuiltInScoringProjectionProvider
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.lp.BuiltInRelaxationProjectionProvider
import com.eignex.klause.lp.RelaxationProjectionProvider
import com.eignex.klause.propagation.BuiltInPropagationProjectionProvider
import com.eignex.klause.propagation.PropagationProjectionProvider

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
) {
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
