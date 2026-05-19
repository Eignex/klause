package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `lex_less(xs, ys)` / `lex_lesseq(xs, ys)` — lexicographic ordering on equal-length int
 * vectors. [strict] = `true` for strict less-than, `false` for less-or-equal.
 *
 *  - Strict: `xs <ₗₑₓ ys`  iff  there exists `k` with `xs[k] < ys[k]` and `xs[i] = ys[i]`
 *    for all `i < k`.
 *  - Non-strict: `xs ≤ₗₑₓ ys`  iff  the strict version holds *or* `xs[i] = ys[i]` for all `i`.
 *
 * If `xs.size != ys.size` the shorter array is treated as a prefix: a proper prefix
 * compares strictly less than the longer one (MiniZinc semantics).
 *
 * Propagation in this first cut is the default no-op — chained prefix tightening lands
 * with full propagator strength later. LS recomputes the relation on each query.
 */
class LexLess(
    val xs: IntArray,
    val ys: IntArray,
    val strict: Boolean,
) : LocalSearchFactor {

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + ys

    override fun initialize(state: LocalSearchState, factorId: Int) {
        // No payload — relation is recomputed each query in O(n).
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = !satisfied(state)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val wasViolated = !satisfied(state)
        val willViolate = !satisfiedWithOverride(state, intVar, newValue)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        // Stateless factor — delta queries are already correct against the current assignment.
        return 0
    }

    /** Compute `xs lex≤(or <) ys` against current assignment. */
    private fun satisfied(state: LocalSearchState): Boolean = compare(
        getX = { state.assignment.intValue(xs[it]) },
        getY = { state.assignment.intValue(ys[it]) },
    )

    /** Same but with a single var overridden by [override]. */
    private fun satisfiedWithOverride(state: LocalSearchState, intVar: Int, override: Int): Boolean = compare(
        getX = { val v = xs[it]; if (v == intVar) override else state.assignment.intValue(v) },
        getY = { val v = ys[it]; if (v == intVar) override else state.assignment.intValue(v) },
    )

    private inline fun compare(getX: (Int) -> Int, getY: (Int) -> Int): Boolean {
        val len = minOf(xs.size, ys.size)
        for (i in 0 until len) {
            val a = getX(i)
            val b = getY(i)
            if (a < b) return true
            if (a > b) return false
        }
        // Prefix equal. If lengths match: strict requires inequality somewhere → fail; non-
        // strict succeeds. If xs is shorter prefix of ys: strict succeeds, non-strict succeeds.
        // If ys is shorter prefix of xs: strict fails, non-strict fails.
        return when {
            xs.size == ys.size -> !strict
            xs.size < ys.size -> true
            else -> false  // xs.size > ys.size
        }
    }

    /**
     * Walks the prefix while both sides are singleton-equal (the lex relation is
     * undetermined there); at the first index `k` where they aren't both forced equal,
     * applies `xs[k] ≤ ys[k]`. If every paired index is singleton-equal, decides the
     * relation from the array-length tiebreak (strict requires a proper-prefix
     * relationship). Strong enough to detect singleton-pinned violations; per-suffix
     * Hall reasoning is deferred to the next strength pass.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val len = minOf(xs.size, ys.size)
        var i = 0
        while (i < len) {
            val dx = state.intDomains[xs[i]]
            val dy = state.intDomains[ys[i]]
            if (dx.min == dx.max && dy.min == dy.max) {
                when {
                    dx.min < dy.min -> return true   // relation fixed in our favour, tail unconstrained
                    dx.min > dy.min -> return false  // violated at index i
                    else -> { i++; continue }        // equal — advance into prefix
                }
            }
            // Mixed-or-non-singleton: apply the weakest sound bound xs[i] ≤ ys[i]. Per-
            // index strict-< inference at the *last* compared position would tighten by 1,
            // but the existence of a future witness keeps full strictness out of scope here.
            if (!state.tightenIntMax(xs[i], dy.max)) return false
            if (!state.tightenIntMin(ys[i], dx.min)) return false
            return true
        }
        // Walked the entire compared prefix with everything singleton-equal.
        return when {
            xs.size == ys.size -> !strict  // equal arrays: strict fails, non-strict succeeds
            xs.size < ys.size -> true       // xs is shorter prefix: succeeds both modes
            else -> false                   // ys is shorter prefix: fails both modes
        }
    }
}
