package com.eignex.klause.propagation

/**
 * A constraint learned during search and stored in the [LearnedClauseDb]: a [Propagator] that also
 * exposes its variable footprint, which conflict-factor extraction ([PropagationState.extractConflictFactors])
 * walks to trace causation through the propagation graph. This is the stored counterpart of the
 * [ConflictResolvent] — the learned nogood after it has been materialised into a propagating
 * factor.
 *
 * Naming the contract (rather than typing the store to a concrete clause) is what lets the learned database
 * hold more than one learned-constraint kind — a [com.eignex.klause.factor.bool.ClausePropagator] and a
 * pseudo-Boolean cutting-planes propagator side by side — over one set of policy columns and one
 * forgetting pass.
 */
internal interface LearnedPropagator : Propagator {
    /** Boolean (and atom-lit) variable ids this learned constraint watches. */
    val boolVars: IntArray

    /** Integer variable ids this learned constraint watches. */
    val intVars: IntArray
}
