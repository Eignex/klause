package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `table_int(xs, tuples)` — the vector of `xs(i)` values must equal one of the rows of
 * [tuples]. The [tuples] array stores rows row-major: `tuples(i, j)` lives at
 * `tuples(i * arity + j)` in the flat representation, where `arity = xs.size`.
 *
 * Propagation: tighten each `xs(j)` to the union of `tuples(*, j)` values restricted to
 * rows whose every column is still domain-feasible.
 *
 * `table_bool` is supported via the same factor by channeling booleans to 0/1 ints upstream.
 */
class Table(
    /** The variable ids forming each candidate tuple. */
    val xs: IntArray,
    /** Allowed tuples, row-major; length is a multiple of `xs.size`. */
    val tuples: IntArray,
) : Factor {

    /** Number of variables per tuple. */
    val arity: Int = xs.size

    /** Number of tuples. */
    val numTuples: Int = tuples.size / arity

    init {
        require(xs.isNotEmpty()) { "table: empty xs" }
        require(tuples.size % arity == 0) { "table: tuples length must be a multiple of xs.size" }
        require(numTuples > 0) { "table: at least one tuple required" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Table(xs.remapVars(intMap), tuples)

    // Column c ↔ xs[c], so xs order is kept (positional); rows are a set, so row strings are sorted.
    // Encodes the full var sequence and tuple set — collision-free up to variable identity.
    override fun structuralKey(): String {
        val rows = ArrayList<String>(numTuples)
        for (r in 0 until numTuples) {
            rows.add((0 until arity).joinToString(".") { c -> tuples[r * arity + c].toString() })
        }
        rows.sort()
        return "table:" + xs.joinToString(",") + ":" + rows.joinToString(";")
    }

    /** Relabel every tuple entry (#374): each column holds domain values of its variable, all in the
     *  one value universe, so a single map relabels the whole table. */
    override fun remapValues(valueMap: (Int) -> Int): Factor = Table(xs, IntArray(tuples.size) { valueMap(tuples[it]) })

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    /** STR2 sparse-set state. [validTuples] holds tuple indices; the prefix
     *  `[0, numValid)` is live (still feasible). On push the engine clones via
     *  [snapshotCopy]; on pop the cloned state is restored, so [numValid] correctly
     *  reflects the level we backjumped to. */
    private class Str2State(
        val validTuples: IntArray,
        var numValid: Int,
        /** Column domain refs at the last successful propagate, for the unchanged-domains fast
         *  path. Snapshotted so the check reflects the level's fixpoint after a backtrack. */
        val cachedDoms: Array<IntDomain?>,
    ) : PropagationState.SnapshottablePayload {
        override fun snapshotCopy(): Str2State = Str2State(validTuples.copyOf(), numValid, cachedDoms.copyOf())
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        for (row in 0 until numTuples) {
            var match = true
            for (col in 0 until arity) {
                if (state.assignment.intValue(xs[col]) != tuples[row * arity + col]) {
                    match = false
                    break
                }
            }
            if (match) return false
        }
        return true
    }

    /** Graded violation: the **minimum Hamming distance** from the current `xs` assignment to
     *  any allowed tuple — i.e. the fewest columns that must change to satisfy the table. `0`
     *  iff some tuple matches exactly. Gives CBLS a gradient that rewards moves bringing `xs`
     *  closer to a tuple, instead of the flat all-or-nothing binary cost. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        minHamming(state, intVar = -1, newValue = 0)

    /** Min Hamming distance from the assignment (with [intVar] hypothetically set to
     *  [newValue], or no override when `intVar < 0`) to the nearest tuple. Early-exits a row
     *  once it exceeds the running best, and returns immediately on an exact match. */
    private fun minHamming(state: LocalSearchState, intVar: Int, newValue: Int): Int {
        var best = arity + 1
        for (row in 0 until numTuples) {
            val base = row * arity
            var dist = 0
            for (col in 0 until arity) {
                val v = if (xs[col] == intVar) newValue else state.assignment.intValue(xs[col])
                if (v != tuples[base + col]) {
                    dist++
                    if (dist >= best) break
                }
            }
            if (dist < best) {
                best = dist
                if (best == 0) return 0
            }
        }
        return best
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int =
        minHamming(state, intVar, newValue) - minHamming(state, -1, 0)

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Repair via per-tuple-support: find the tuple closest (by Hamming distance) to the
     *  current assignment, then propose IntSet moves that bring `xs` toward each matching
     *  column in that tuple. Caps proposals at the top-2 closest tuples to keep the
     *  candidate set focused while still giving strategies multiple repair directions. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        // Score each tuple by Hamming distance to the current assignment.
        data class Scored(val row: Int, val distance: Int)
        val scored = ArrayList<Scored>(numTuples)
        for (row in 0 until numTuples) {
            var dist = 0
            for (col in 0 until arity) {
                if (state.assignment.intValue(xs[col]) != tuples[row * arity + col]) dist++
            }
            scored.add(Scored(row, dist))
        }
        scored.sortBy { it.distance }
        val topK = minOf(2, scored.size)
        for (k in 0 until topK) {
            val row = scored[k].row
            for (col in 0 until arity) {
                val target = tuples[row * arity + col]
                val cur = state.assignment.intValue(xs[col])
                if (target != cur && target in state.problem.intDomains[xs[col]]) {
                    sink.addChannelingIntSet(state, xs[col], target)
                }
            }
        }
    }

    /** Hole-aware conflict reason — cites every post-bake domain hole and bound shift
     *  across [xs], matching the per-prune antecedent set used in [propagate]. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, xs)

    /**
     * STR2 (Lecoutre 2011). The propagator maintains a sparse set of currently-feasible
     * tuple indices in [Str2State] across propagator calls; on each fire it sweeps only
     * the live prefix to drop newly-infeasible tuples and gather column supports.
     * Backtrack correctness comes from [PropagationState.SnapshottablePayload]: push
     * clones the state, pop restores it.
     *
     * Per-prune antecedents and the conflict reason are hole-aware via
     * [collectHoleAndBoundAntecedents].
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val s = (state.refPayload[factorId] as? Str2State) ?: run {
            val fresh = Str2State(IntArray(numTuples) { it }, numTuples, arrayOfNulls(arity))
            state.refPayload[factorId] = fresh
            fresh
        }
        // Incremental fast path: if no column's domain ref changed since the last successful
        // propagate, STR2 already pruned to that fixpoint and would deduce nothing new. (IntDomain
        // is immutable — a tighten allocates a fresh object — so reference identity per column means
        // the column's domain is byte-for-byte unchanged.)
        var changed = false
        for (col in 0 until arity) {
            if (s.cachedDoms[col] !== state.intDomains[xs[col]]) {
                changed = true
                break
            }
        }
        if (!changed && s.cachedDoms[0] != null) return true
        // Per-column support bitsets: bit (value - lo[col]) is set iff some currently-feasible
        // tuple has that value at the column. Spans are bounded by the column's current
        // domain [min..max] (values outside this band cannot appear because infeasible
        // tuples were already filtered out).
        val lo = IntArray(arity)
        val hi = IntArray(arity)
        val supportBits = arrayOfNulls<LongArray>(arity)
        for (col in 0 until arity) {
            val d = state.intDomains[xs[col]]
            lo[col] = d.min
            hi[col] = d.max
            val span = hi[col] - lo[col] + 1
            supportBits[col] = LongArray((span + 63) ushr 6)
        }
        var i = 0
        while (i < s.numValid) {
            val row = s.validTuples[i]
            var feasible = true
            for (col in 0 until arity) {
                val v = tuples[row * arity + col]
                if (v !in state.intDomains[xs[col]]) {
                    feasible = false
                    break
                }
            }
            if (!feasible) {
                val last = s.numValid - 1
                if (i != last) {
                    s.validTuples[i] = s.validTuples[last]
                    s.validTuples[last] = row
                }
                s.numValid = last
                // Don't advance i — the swapped-in tuple at i hasn't been checked.
            } else {
                for (col in 0 until arity) {
                    val v = tuples[row * arity + col]
                    val off = v - lo[col]
                    val bits = requireNotNull(supportBits[col])
                    bits[off ushr 6] = bits[off ushr 6] or (1L shl (off and 63))
                }
                i++
            }
        }
        if (s.numValid == 0) return false
        val ant = collectHoleAndBoundAntecedents(state, xs)
        for (col in 0 until arity) {
            val bits = requireNotNull(supportBits[col])
            // First / last set bit ⇒ tightened bounds.
            var firstSet = -1
            for (w in bits.indices) {
                if (bits[w] != 0L) {
                    firstSet = (w shl 6) + bits[w].countTrailingZeroBits()
                    break
                }
            }
            if (firstSet < 0) return false // No supports — fail.
            var lastSet = -1
            for (w in bits.indices.reversed()) {
                if (bits[w] != 0L) {
                    lastSet = (w shl 6) + (63 - bits[w].countLeadingZeroBits())
                    break
                }
            }
            val minSup = lo[col] + firstSet
            val maxSup = lo[col] + lastSet
            if (!state.tightenIntMin(xs[col], minSup, ant)) return false
            if (!state.tightenIntMax(xs[col], maxSup, ant)) return false
            val d = state.intDomains[xs[col]]
            // Iterate the current domain; exclude values whose bit is not set.
            // Re-resolve bits / lo from snapshot — the domain ref may have shifted bounds
            // but the column's tuple-source values are still indexed off the original lo[col].
            val colLo = lo[col]
            val colHi = hi[col]
            // Collect first, then exclude — excludeIntValue invalidates the domain ref.
            var toRemoveCount = 0
            val toRemove = IntArray(d.size)
            d.forEach { value ->
                if (value in colLo..colHi) {
                    val off = value - colLo
                    if (((bits[off ushr 6] ushr (off and 63)) and 1L) == 0L) {
                        toRemove[toRemoveCount++] = value
                    }
                } else {
                    toRemove[toRemoveCount++] = value
                }
            }
            for (k in 0 until toRemoveCount) {
                if (!state.excludeIntValue(xs[col], toRemove[k], ant)) return false
            }
        }
        // Record the post-propagate domain refs so the next fire can short-circuit if nothing moved.
        for (col in 0 until arity) s.cachedDoms[col] = state.intDomains[xs[col]]
        return true
    }
}
