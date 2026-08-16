package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayList

/**
 * Learned-constraint database for [PropagationState]: the constraints learned during search plus the
 * parallel policy columns the three-tier reduction and forgetting passes read. Registration,
 * lookup and pruning live in `ClauseDb.kt`. Every learned constraint is stored as a [Propagator] so the
 * database is agnostic to the learned-constraint kind: a [com.eignex.klause.factor.bool.ClausePropagator]
 * and a pseudo-Boolean cutting-planes propagator share the same policy columns and forgetting machinery.
 */
internal class LearnedClauseDb(
    /** Count of binary (2-literal) clauses known — original problem clauses plus learned ones.
     *  Gates binary-resolution minimization, which is a no-op without binary clauses.
     *  Over-approximates after forgetting (never decremented), which only costs a harmless no-op
     *  pass — never correctness. */
    var binaryClauseCount: Int,
) {
    /** Learned constraints accumulated during search (LCG-style nogoods produced by [ConflictAnalyzer]).
     *  Their factor ids live in `[problem.numFactors, totalFactorCount)` — treat them like any other
     *  [Propagator] via `factorAt`; they participate in propagation through the per-literal watcher index
     *  just like static factors. Survives `restore` (learned constraints are facts about the original
     *  problem, not trail state); pruned by `forgetLearnedClauses`. */
    val store = ArrayList<LearnedPropagator>()

    /** LBD (Literal Block Distance) per learned clause, parallel to [store]. The standard
     *  glue metric: lower = more re-usable. Forgetting policies key on this to decide which clauses to
     *  drop. */
    val lbds = IntArrayList()

    /** 1 for clauses that must survive every forgetting pass, parallel to [store].
     *  Solution-blocking nogoods are the main client: dropping one re-opens an already reported
     *  leaf and the search can revisit it forever. */
    val permanent = IntArrayList()

    /** Three-tier database tier per learned clause, parallel to [store], stored as
     *  [ClauseTier] ordinals. [ClauseTier.UNSET] until the reduction policy first classifies it by
     *  LBD; the policy then promotes/demotes clauses between tiers based on reuse, so the tier is
     *  persistent state rather than a pure function of LBD. */
    val tier = IntArrayList()

    /** 1 iff the learned clause has detected a conflict or forced a unit since the last reduction,
     *  parallel to [store]. The three-tier reduction policy reads this to promote reused clauses
     *  and demote idle ones, then clears it for survivors. */
    val usedFlags = IntArrayList()

    /** Number of learned clauses. */
    val size: Int get() = store.size
}
