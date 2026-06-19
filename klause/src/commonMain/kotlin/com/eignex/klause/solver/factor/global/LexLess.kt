package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.factor.linear.collectLinearTightenAntecedents
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntHashSet

/**
 * `lex_less(xs, ys)` / `lex_lesseq(xs, ys)` — lexicographic ordering on equal-length int
 * vectors. [strict] = `true` for strict less-than, `false` for less-or-equal.
 *
 *  - Strict: `xs <ₗₑₓ ys`  iff  there exists `k` with `xs`k` < ys`k`` and `xs`i` = ys`i``
 *    for all `i < k`.
 *  - Non-strict: `xs ≤ₗₑₓ ys`  iff  the strict version holds *or* `xs`i` = ys`i`` for all `i`.
 *
 * If `xs.size != ys.size` the shorter array is treated as a prefix: a proper prefix
 * compares strictly less than the longer one (MiniZinc semantics).
 *
 * LS recomputes the relation on each query.
 */
class LexLess(
    /** Left vector variable ids. */
    val xs: IntArray,
    /** Right vector variable ids, parallel to [xs]. */
    val ys: IntArray,
    /** When true the relation is strict (`xs < ys`); otherwise `xs ≤ ys`. */
    val strict: Boolean,
) : Factor {

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        LexLess(xs.remapVars(intMap), ys.remapVars(intMap), strict)

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + ys

    /**
     * Advisor subscription (#623): lexicographic propagation is bound-only (see [propagate], which
     * compares `min`/`max` at the deciding position and tightens bounds — its own comment notes it
     * "can't propagate further with bound-only reasoning"). An interior hole moves no bound, so the
     * factor subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] per variable and skips
     * interior `VALUE_REMOVED` wakes.
     */
    override val initialIntEventWatches: IntArray = run {
        val distinct = intVars.toHashSet()
        val out = IntArray(distinct.size * 2)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
        out
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        // No payload — relation is recomputed each query in O(n).
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = !satisfied(state)

    /** Graded violation: at the first position `k` that decides the comparison, the overshoot
     *  `xs[k] − ys[k]` when `xs[k] > ys[k]` (compressed) — a move shrinking that gap scores a
     *  real improvement. When the comparable prefix is fully equal the violation is structural
     *  (strict equal-length, or `xs` a longer extension), graded as `1`. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = lexDegree(
        getX = { state.assignment.intValue(xs[it]) },
        getY = { state.assignment.intValue(ys[it]) },
        softCap = state.violationSoftCap,
    )

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val cap = state.violationSoftCap
        val before = lexDegree(
            getX = { state.assignment.intValue(xs[it]) },
            getY = { state.assignment.intValue(ys[it]) },
            softCap = cap,
        )
        val after = lexDegree(
            getX = {
                val v = xs[it]
                if (v == intVar) newValue else state.assignment.intValue(v)
            },
            getY = {
                val v = ys[it]
                if (v == intVar) newValue else state.assignment.intValue(v)
            },
            softCap = cap,
        )
        return after - before
    }

    /** Graded lex violation under the given value accessors — mirrors [compare] but returns the
     *  first-deciding-position overshoot magnitude instead of a boolean. `0` iff satisfied. */
    private inline fun lexDegree(getX: (Int) -> Int, getY: (Int) -> Int, softCap: Int): Int {
        val len = minOf(xs.size, ys.size)
        for (i in 0 until len) {
            val a = getX(i)
            val b = getY(i)
            if (a < b) return 0
            if (a > b) return compressViolation(a.toLong() - b, softCap)
        }
        return when {
            xs.size == ys.size -> if (strict) 1 else 0
            xs.size < ys.size -> 0
            else -> 1
        }
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        // Stateless factor — delta queries are already correct against the current assignment.
        return 0
    }

    /**
     * Problem-aware repair: locate the first position `k` where the lex relation is decided
     * (xs`k` != ys`k`) — if violated there, propose targeted moves that restore `xs`k` ≤ ys`k``
     * (strict: `<`). When the comparable prefix is fully equal, the violation is structural
     * (strict + equal-length, or xs has an extra suffix); propose prefix-breaking moves at
     * the earliest position with domain slack. A Compound swap of `xs`k` ↔ ys`k`` is added
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
        // Lower xs`k` toward `needXLE` (preferred), or as close as the domain allows.
        if (needXLE in dx) {
            sink.addChannelingIntSet(state, xV, needXLE)
        } else if (dx.min <= needXLE) {
            sink.addChannelingIntSet(state, xV, dx.min)
        }
        // Raise ys`k` toward `needYGE` (preferred), or as close as the domain allows.
        if (needYGE in dy) {
            sink.addChannelingIntSet(state, yV, needYGE)
        } else if (dy.max >= needYGE) {
            sink.addChannelingIntSet(state, yV, dy.max)
        }
        // Lex-preserving swap: if each side's current value sits in the other's domain,
        // swapping resolves the violation (xs`k`=b, ys`k`=a → satisfies xs`k` < ys`k`).
        if (xV != yV && b in dx && a in dy) {
            sink.addCompound(listOf(Move.IntSet(xV, b), Move.IntSet(yV, a)))
        }
        // ±1 nudges at the violation point as cheap fallbacks for tight domains.
        if (a > dx.min) sink.addChannelingIntSet(state, xV, a - 1)
        if (b < dy.max) sink.addChannelingIntSet(state, yV, b + 1)
    }

    /** Add the first-available prefix-breaking move (lower xs`i` or raise ys`i`) at the
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

    override val providesImplicitNeighbourhood: Boolean get() = true

    /** Feasibility-preserving neighbourhood: once the comparison is strictly decided at the first
     *  differing index `k` (`xs[k] < ys[k]`), every position *after* `k` is irrelevant to the
     *  relation. Relabel such a free variable to any other in-domain value — the lex order is
     *  untouched, and the variable is free for a coupled constraint. Variables that also appear in
     *  the deciding prefix `[0, k]` are excluded (changing them could move `k`). */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val len = minOf(xs.size, ys.size)
        var k = -1
        for (i in 0 until len) {
            if (state.assignment.intValue(xs[i]) != state.assignment.intValue(ys[i])) {
                k = i
                break
            }
        }
        if (k < 0) return // all-equal prefix: no strictly-decided suffix to free.
        val prefix = IntHashSet()
        for (i in 0..k) {
            prefix.add(xs[i])
            prefix.add(ys[i])
        }
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_MOVE_CAP && attempts < STRUCTURED_MOVE_CAP * MOVE_ATTEMPT_STRIDE) {
            attempts++
            val arr = if (state.rng.nextBoolean()) xs else ys
            if (k + 1 >= arr.size) continue
            val vId = arr[k + 1 + state.rng.nextInt(arr.size - (k + 1))]
            if (prefix.contains(vId)) continue
            val cur = state.assignment.intValue(vId)
            val d = state.problem.intDomains[vId]
            var pick = -1
            var seen = 0
            d.forEach { w ->
                if (w != cur) {
                    seen++
                    if (state.rng.nextInt(seen) == 0) pick = w
                }
            }
            if (pick < 0) continue
            sink.addChannelingIntSet(state, vId, pick)
            emitted++
        }
    }

    /** Feasible init: decide the order strictly at position 0 by lowering `xs[0]` and raising
     *  `ys[0]`, then leave every later position at its domain minimum (free once position 0
     *  decides). Returns false — leaving the random assignment — when position 0 can't be made
     *  `xs[0] < ys[0]` (empty compared range, shared variable, or frozen vars that fix `≥`). */
    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        val len = minOf(xs.size, ys.size)
        if (len == 0) return false
        val x0 = xs[0]
        val y0 = ys[0]
        if (x0 == y0) return false
        val xv = if (state.assumptions.isFrozenInt(
                x0,
            )
        ) {
            state.assignment.intValue(x0)
        } else {
            state.problem.intDomains[x0].min
        }
        val yv = if (state.assumptions.isFrozenInt(
                y0,
            )
        ) {
            state.assignment.intValue(y0)
        } else {
            state.problem.intDomains[y0].max
        }
        if (xv >= yv) return false
        if (!state.assumptions.isFrozenInt(x0)) state.assignment.setInt(x0, xv)
        if (!state.assumptions.isFrozenInt(y0)) state.assignment.setInt(y0, yv)
        for (i in 1 until xs.size) {
            if (!state.assumptions.isFrozenInt(xs[i])) {
                state.assignment.setInt(xs[i], state.problem.intDomains[xs[i]].min)
            }
        }
        for (i in 1 until ys.size) {
            if (!state.assumptions.isFrozenInt(ys[i])) {
                state.assignment.setInt(ys[i], state.problem.intDomains[ys[i]].min)
            }
        }
        return true
    }

    private companion object {
        /** Cap on free-suffix relabel moves offered per [proposeStructuredMoves] call. */
        const val STRUCTURED_MOVE_CAP: Int = 4

        /** Rejection-sampling attempts per requested move before giving up. */
        const val MOVE_ATTEMPT_STRIDE: Int = 6
    }

    /** Compute `xs lex≤(or <) ys` against current assignment. */
    private fun satisfied(state: LocalSearchState): Boolean = compare(
        getX = { state.assignment.intValue(xs[it]) },
        getY = { state.assignment.intValue(ys[it]) },
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

    /*
     * Walks the prefix while both sides are singleton-equal (the lex relation is
     * undetermined there); at the first index `k` where they aren't both forced equal,
     * applies `xs`k` ≤ ys`k``. If every paired index is singleton-equal, decides the
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
     * Walk index `i` from 0 while `xs`i`` and `ys`i`` are "definitely equal" (both
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
                    dx.min < dy.min -> return true

                    // relation forced, tail unconstrained
                    dx.min > dy.min -> return false

                    // violated at i
                    else -> {
                        i++
                        continue
                    } // equal — advance
                }
            }
            // Forced satisfaction: xs`i` can't reach ys`i`'s range, so xs <_lex ys.
            if (dx.max < dy.min) return true
            // Forced failure: even the smallest xs`i` is above the largest ys`i`.
            if (dx.min > dy.max) return false
            // Bound tightening: xs`i` ≤ ys`i` is necessary for the relation to hold,
            // since position i would otherwise decide the comparison against us.
            // This deduction holds only because the prefix xs[0..i-1] = ys[0..i-1] is
            // pinned equal — index i is the deciding position precisely because of it.
            // Cite the prefix bound atoms alongside the deciding-index bounds so a learned
            // clause carries the prefix-equality premises and stays sound (#75).
            val prefixVars = IntArray(2 * i)
            for (j in 0 until i) {
                prefixVars[j] = xs[j]
                prefixVars[i + j] = ys[j]
            }
            val antFromY = state.composeIntVarAtomAntecedents(prefixVars + ys[i])
            val antFromX = state.composeIntVarAtomAntecedents(prefixVars + xs[i])
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
