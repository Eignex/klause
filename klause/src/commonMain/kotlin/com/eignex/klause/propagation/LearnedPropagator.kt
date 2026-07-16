package com.eignex.klause.propagation

/**
 * A constraint learned during search and stored in the [LearnedClauseDb]: a [Propagator] that also
 * exposes its variable footprint, which conflict-factor extraction ([PropagationState.extractConflictFactors])
 * walks to trace causation through the propagation graph. This is the stored counterpart of the
 * [ConflictResolvent] — the learned nogood after it has been materialised into a propagating
 * factor.
 *
 * Naming the contract (rather than typing the store to a concrete clause) is what lets the learned database
 * hold more than one learned-constraint kind (#1119): a [com.eignex.klause.factor.bool.ClausePropagator]
 * today, a pseudo-Boolean cutting-planes propagator alongside it once PB learning lands, with the policy
 * columns and forgetting machinery unchanged.
 */
internal interface LearnedPropagator : Propagator {
    /** Boolean (and atom-lit) variable ids this learned constraint watches. */
    val boolVars: IntArray

    /** Integer variable ids this learned constraint watches. */
    val intVars: IntArray
}
