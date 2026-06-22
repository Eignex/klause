package com.eignex.klause.solver

import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * The local-search contract of a constraint: violation scoring, move delta computation,
 * move application, and move proposal. In the LS literature an *invariant* is a maintained
 * constraint with a graded violation score and an incremental update rule; this interface is
 * the klause realisation of that concept.
 *
 * Implemented by every factor in [Problem.factors] (via [Factor]), and the type the LS engine
 * ([com.eignex.klause.solver.localsearch.LocalSearchSolver]) uses when dispatching to invariants.
 *
 * Every method defaults to a sound no-op (always-satisfied, zero deltas, naive ±1 repair
 * moves), so a propagation-only factor needs to implement nothing here.
 *
 * See [Factor] for the full constraint contract (deductive + local-search + presolve).
 */
interface Invariant {
    /** Build this factor's payload from the current assignment. Called once per restart.
     *  Default no-op for stateless factors that maintain no payload. */
    fun initialize(state: LocalSearchState, factorId: Int) {}

    /** True iff this factor is violated under the current state. Default: never violated —
     *  the correct answer for a propagation-only factor that carries no LS semantics. */
    fun isViolated(state: LocalSearchState, factorId: Int): Boolean = false

    /**
     * **Graded violation degree**: `0` when satisfied, a positive magnitude that grows with
     * "how far" the constraint is from being satisfied. The LS hard cost is
     * `Σ violationDegree` over all factors — *not* a count of violated factors — so CBLS
     * sees a descent gradient on tight arithmetic and global constraints (a move that shrinks
     * an `int_lin_eq` residual from 1000 → 1 scores a real improvement instead of 0).
     *
     * The default delegates to the binary [isViolated] (degree `1` when violated, else `0`),
     * which is the *correct* degree for inherently-binary factors — a violated clause, a
     * single comparator, a table membership all genuinely have degree 1. Gradable factors
     * (linear, count/cardinality, packing, scheduling, all-different, …) override this with
     * a real magnitude.
     *
     * **Contract (must hold for cost/gradient consistency):** for every move kind,
     * `deltaIf*` and `apply*` must return exactly `violationDegree(after) - violationDegree(before)`.
     * The engine maintains `cost` and the per-factor degree incrementally from those deltas
     * and only calls [violationDegree] at [LocalSearchState.recompute]; a delta that disagrees
     * with this method silently desyncs the cost. Degrees should be clamped to a sane range
     * to avoid `Int` overflow when summed across the factor set.
     */
    fun violationDegree(state: LocalSearchState, factorId: Int): Int = if (isViolated(state, factorId)) 1 else 0

    /**
     * Δ in this factor's [violationDegree] if the given move were applied, computed without
     * mutating state. For a binary factor this is `+1` (satisfied → violated), `-1` (the
     * opposite), or `0`; for a graded factor it is the signed change in magnitude (any
     * integer). Default returns 0; factors override the methods relevant to the move kinds
     * they handle.
     */
    fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int = 0

    /** Δ violation-degree if [intVar] were set to [newValue], without mutating state. */
    fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int = 0

    /**
     * Apply a committed move to this factor's payload. The assignment has already been
     * updated, so factors compare current values against the saved `oldValue` (for int sets)
     * or recover the pre-flip value by inversion. Returns the same Δ[violationDegree] the
     * deltaIf* method would have returned before the move.
     */
    fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int = 0

    /** Apply a committed int-set of [intVar] from [oldValue]; returns the Δ violation-degree. */
    fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /**
     * Suggest moves that would (or might) repair this factor when violated. The default lists
     * a Boolean flip per boolVars member plus an `IntSet(±1)` per intVars member. Factors
     * with structural insight (e.g. a comparator can snap to its bound) override this.
     */
    fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        for (b in state.problem.factors[factorId].boolVars) sink.addBoolFlip(b)
        for (i in state.problem.factors[factorId].intVars) {
            val cur = state.assignment.intValue(i)
            val d = state.problem.intDomains[i]
            if (cur < d.max) sink.addChannelingIntSet(state, i, cur + 1)
            if (cur > d.min) sink.addChannelingIntSet(state, i, cur - 1)
        }
    }

    /**
     * Suggest moves that the factor *currently* knows would preserve its own satisfaction
     * (`isViolated → false` after the move). Called by the minimize engine during the
     * objective-descent phase to collect candidate moves that don't break the constraint
     * but might improve the LS objective. Pre-condition: `!isViolated(state, factorId)`
     * — engines only consult this on already-feasible states.
     *
     * Default: no proposals. Factors with structural insight (e.g. `Linear EQ`'s "shift
     * between two summed vars" or `Cardinality.exactlyOne`'s "swap a true literal with a
     * false one") override to push self-preserving moves. The engine combines proposals
     * from all factors and scores each against the objective; the constraint-aware
     * "structured" set typically dwarfs random pair-swap in hit rate on decomposed CP
     * models.
     */
    fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
    }

    /**
     * True iff this factor's [proposeStructuredMoves] generates a *feasibility-preserving*
     * neighbourhood for a structural global — moves that keep the constraint satisfied while
     * relocating values within its scope (e.g. an all-different value swap, a circuit tour
     * re-link). Such factors are candidates for implicit-solving: the engine seeds them
     * feasible and draws their structure-preserving moves even during infeasibility so they
     * can clear violations in *coupled* constraints without ever breaking themselves.
     *
     * Default `false`. Factors with arithmetic structured moves that only make sense at
     * feasibility (e.g. `Linear EQ` pair-shifts, `Cardinality` count-preserving swaps) leave
     * this `false` — they are objective-descent helpers, not implicit-solving neighbourhoods.
     */
    val providesImplicitNeighbourhood: Boolean get() = false

    /**
     * Overwrite this factor's variables in [state]'s assignment with a configuration that
     * satisfies the factor, used by the engine's implicit-solving feasible-init pass on a
     * scope-disjoint set of elected globals (so seeds never clobber one another). Must leave
     * variables frozen by [LocalSearchState.assumptions] untouched and may only write the
     * factor's own intVars / boolVars. Returns true if it produced a fully satisfying
     * configuration, false if the factor could not be seeded feasibly (over-constrained or
     * frozen out) — the engine then falls back to the random assignment for those vars.
     *
     * Default: no-op returning false. Structural globals that provide an implicit
     * neighbourhood ([providesImplicitNeighbourhood]) override this so the search can begin
     * inside their feasible region.
     */
    fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean = false

    /** True iff this factor maintains its contribution to [LocalSearchState.boolBreakCount]
     *  and [LocalSearchState.boolMakeCount] incrementally via [updateBoolBreakMakeForFlip],
     *  skipping the engine's brute-force O(arity²) per-flip subtract-add cycle.
     *
     *  Factors that override should: (a) set this to true, (b) implement
     *  [updateBoolBreakMakeForFlip] so the post-flip counts match what brute-force would
     *  produce, (c) maintain whatever internal state the update needs (e.g. `numTrueLits`
     *  in [LocalSearchState.intPayload]). Default `false` keeps the brute-force fallback. */
    val maintainsBreakMakeIncrementally: Boolean get() = false

    /** Adjust [LocalSearchState.boolBreakCount] / [LocalSearchState.boolMakeCount] after
     *  [flippedVar] has been flipped. Called *after* the assignment is updated and after
     *  this factor's own [applyBoolFlip] has run, so any internal payload is current.
     *
     *  Only invoked when [maintainsBreakMakeIncrementally] is true. Net adjustment must
     *  equal the brute-force "subtract pre-flip per-var deltas, add post-flip" pattern. */
    fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {}

    /** Mirror of [maintainsBreakMakeIncrementally] for the int-set path. When `true`, the
     *  LS engine skips its brute-force boolVars walk after an `intVar` is set and calls
     *  [updateIntBreakMakeForIntSet] instead. Factors whose [deltaIfBoolFlipped] doesn't
     *  depend on int values (e.g. pure Boolean constraints with no intVars) get no
     *  benefit from setting this flag — the engine already short-circuits when intVars
     *  is empty via [Problem.intOccurrences]. */
    val maintainsIntBreakMakeIncrementallyForIntSet: Boolean get() = false

    /** Adjust [LocalSearchState.boolBreakCount] / [LocalSearchState.boolMakeCount] after
     *  [intVar] has been set from [oldValue] to its new value (read via
     *  `state.assignment.intValue(intVar)`). Called *after* the assignment is updated and
     *  after this factor's own [applyIntSet] has run. Only invoked when
     *  [maintainsIntBreakMakeIncrementallyForIntSet] is true. Net adjustment must equal
     *  the brute-force "subtract pre-set per-bool deltas, add post-set" pattern. */
    fun updateIntBreakMakeForIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int) {}
}
