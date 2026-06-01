package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
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
 * LS recomputes the relation on each query.
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

    /**
     * Problem-aware repair: locate the first position `k` where the lex relation is decided
     * (xs[k] != ys[k]) — if violated there, propose targeted moves that restore `xs[k] ≤ ys[k]`
     * (strict: `<`). When the comparable prefix is fully equal, the violation is structural
     * (strict + equal-length, or xs has an extra suffix); propose prefix-breaking moves at
     * the earliest position with domain slack. A Compound swap of `xs[k] ↔ ys[k]` is added
     * when both target values fit the opposite domain — the lex-preserving "swap neighbourhood".
     */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (satisfied(state)) return
        val len = minOf(xs.size, ys.size)
        var k = -1
        for (i in 0 until len) {
            val a = state.assignment.intValue(xs[i])
            val b = state.assignment.intValue(ys[i])
            if (a != b) {
                k = i
                break
            }
        }
        if (k < 0) {
            // Comparable prefix fully equal — break it at the earliest slot with room.
            proposePrefixBreak(state, sink, 0)
            return
        }
        val a = state.assignment.intValue(xs[k])
        val b = state.assignment.intValue(ys[k])
        if (a < b) return // unreachable: satisfied() would have returned true
        val xV = xs[k]
        val yV = ys[k]
        val dx = state.problem.intDomains[xV]
        val dy = state.problem.intDomains[yV]
        val needXLE = if (strict) b - 1 else b // xs[k] must reach ≤ this for the relation to hold
        val needYGE = if (strict) a + 1 else a // ys[k] must reach ≥ this
        // Lower xs[k] toward `needXLE` (preferred), or as close as the domain allows.
        if (needXLE in dx) {
            sink.addChannelingIntSet(state, xV, needXLE)
        } else if (dx.min <= needXLE) sink.addChannelingIntSet(state, xV, dx.min)
        // Raise ys[k] toward `needYGE` (preferred), or as close as the domain allows.
        if (needYGE in dy) {
            sink.addChannelingIntSet(state, yV, needYGE)
        } else if (dy.max >= needYGE) sink.addChannelingIntSet(state, yV, dy.max)
        // Lex-preserving swap: if each side's current value sits in the other's domain,
        // swapping resolves the violation (xs[k]=b, ys[k]=a → satisfies xs[k] < ys[k]).
        if (xV != yV && b in dx && a in dy) {
            sink.addCompound(listOf(Move.IntSet(xV, b), Move.IntSet(yV, a)))
        }
        // ±1 nudges at the violation point as cheap fallbacks for tight domains.
        if (a > dx.min) sink.addChannelingIntSet(state, xV, a - 1)
        if (b < dy.max) sink.addChannelingIntSet(state, yV, b + 1)
    }

    /** Add the first-available prefix-breaking move (lower xs[i] or raise ys[i]) at the
     *  earliest paired index starting at [startK] with domain slack. Used when the
     *  comparable prefix is fully singleton-equal and the violation is structural. */
    private fun proposePrefixBreak(state: LocalSearchState, sink: MoveSink, startK: Int) {
        val len = minOf(xs.size, ys.size)
        for (i in startK until len) {
            val a = state.assignment.intValue(xs[i])
            val b = state.assignment.intValue(ys[i])
            val dx = state.problem.intDomains[xs[i]]
            val dy = state.problem.intDomains[ys[i]]
            var added = false
            if (a > dx.min) {
                sink.addChannelingIntSet(state, xs[i], a - 1)
                added = true
            }
            if (b < dy.max) {
                sink.addChannelingIntSet(state, ys[i], b + 1)
                added = true
            }
            if (added) return
        }
    }

    /** Compute `xs lex≤(or <) ys` against current assignment. */
    private fun satisfied(state: LocalSearchState): Boolean = compare(
        getX = { state.assignment.intValue(xs[it]) },
        getY = { state.assignment.intValue(ys[it]) },
    )

    /** Same but with a single var overridden by [override]. */
    private fun satisfiedWithOverride(state: LocalSearchState, intVar: Int, override: Int): Boolean = compare(
        getX = {
            val v = xs[it]
            if (v == intVar) override else state.assignment.intValue(v)
        },
        getY = {
            val v = ys[it]
            if (v == intVar) override else state.assignment.intValue(v)
        },
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
            else -> false // xs.size > ys.size
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
    /** Reason when [propagate] returns false: the current bound atoms of every paired
     *  index up to the first non-singleton-equal position, since the propagator
     *  decides exclusively on bounds (no interior-hole pruning). Sound. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val combined = IntArray(xs.size + ys.size).also {
            xs.copyInto(it, 0)
            ys.copyInto(it, xs.size)
        }
        return collectLinearTightenAntecedents(state, combined, excludeIdx = -1, extraLit = 0)
    }

    /**
     * Chained-prefix bound propagator (Frisch et al. 2002, simplified).
     *
     * Walk index `i` from 0 while `xs[i]` and `ys[i]` are "definitely equal" (both
     * singleton with the same value). At the first ambiguous position `alpha`:
     *  - If `xs[α].max < ys[α].min`: relation forced satisfied, return true.
     *  - If `xs[α].min > ys[α].max`: relation forced failed, return false.
     *  - Else: tighten `xs[α].max ≤ ys[α].max`, `ys[α].min ≥ xs[α].min`.
     *  - If after tightening both become singleton-equal, advance to `α+1` and repeat
     *    — the "chained" part of the propagator that fires when each round forces
     *    enough to expose the next ambiguous position.
     *
     * Strict mode: if we reach the end of the compared prefix without finding any
     * position that could strictly decide the relation, then the prefix is fixed-
     * equal; strict-mode forces a strict break somewhere, so the last comparable
     * position must satisfy `xs[end] < ys[end]`. This is handled by the loop's
     * tail: when `i == len` (all-equal prefix), apply the length-based tiebreak.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val len = minOf(xs.size, ys.size)
        var i = 0
        while (i < len) {
            val dx = state.intDomains[xs[i]]
            val dy = state.intDomains[ys[i]]
            if (dx.min == dx.max && dy.min == dy.max) {
                when {
                    dx.min < dy.min -> return true // relation forced, tail unconstrained
                    dx.min > dy.min -> return false // violated at i
                    else -> {
                        i++
                        continue
                    } // equal — advance
                }
            }
            // Forced satisfaction: xs[i] can't reach ys[i]'s range, so xs <_lex ys.
            if (dx.max < dy.min) return true
            // Forced failure: even the smallest xs[i] is above the largest ys[i].
            if (dx.min > dy.max) return false
            // Bound tightening: xs[i] ≤ ys[i] is necessary for the relation to hold,
            // since position i would otherwise decide the comparison against us.
            val antFromY = state.composeIntVarAtomAntecedents(intArrayOf(ys[i]))
            val antFromX = state.composeIntVarAtomAntecedents(intArrayOf(xs[i]))
            if (!state.tightenIntMax(xs[i], dy.max, antFromY)) return false
            if (!state.tightenIntMin(ys[i], dx.min, antFromX)) return false
            // Re-read after the tightening.
            val dx2 = state.intDomains[xs[i]]
            val dy2 = state.intDomains[ys[i]]
            // If still ambiguous, we can't propagate further with bound-only reasoning.
            if (!(dx2.min == dx2.max && dy2.min == dy2.max)) return true
            // Now both singleton: equal means advance into the prefix; otherwise the
            // earlier checks would have caught the inequality.
            if (dx2.min != dy2.min) return dx2.min < dy2.min
            i++
        }
        // Walked the entire compared prefix with everything singleton-equal.
        return when {
            xs.size == ys.size -> !strict
            xs.size < ys.size -> true
            else -> false
        }
    }
}
