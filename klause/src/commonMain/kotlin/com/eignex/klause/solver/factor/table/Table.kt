package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.factor.linear.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.propagation.RevInt
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap

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

    // Var id → its tuple column, for the LS Hamming-distance maintenance. A var occupying exactly
    // one column maps to it here (the common case, unboxed); vars repeated across columns fall to
    // [multiColumnsByVar]. Together they cover every var in [xs].
    private val singleColumnByVar: IntIntMap
    private val multiColumnsByVar: Map<Int, IntArray>

    init {
        val occ = HashMap<Int, IntArrayList>()
        for (c in 0 until arity) occ.getOrPut(xs[c]) { IntArrayList() }.add(c)
        val singleKeys = IntArrayList()
        val singleVals = IntArrayList()
        val multi = HashMap<Int, IntArray>()
        for ((v, cols) in occ) {
            if (cols.size == 1) {
                singleKeys.add(v)
                singleVals.add(cols[0])
            } else {
                multi[v] = cols.toIntArray()
            }
        }
        singleColumnByVar = IntIntMap.build(singleKeys.toIntArray(), singleVals.toIntArray(), absent = -1)
        multiColumnsByVar = multi
    }

    /** Advisor subscription (#623): STR2 is hole-aware GAC (tuple feasibility tests membership, the
     *  prune drops interior values), so subscribe to every kind on every column variable and consume
     *  the dirty-variable delta (#624) — a fire re-sweeps only when a column actually changed, instead
     *  of the per-fire O(arity) domain-ref scan. */
    override val initialIntEventWatches: IntArray = run {
        val distinct = xs.toHashSet()
        val out = IntArray(distinct.size * IntEvent.COUNT)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
            out[w++] = IntEvent.pack(v, IntEvent.VALUE_REMOVED)
            out[w++] = IntEvent.pack(v, IntEvent.FIXED)
        }
        out
    }

    override val consumesIntEventDelta: Boolean = true

    /** STR2 sparse-set state. [validTuples] holds tuple indices; the prefix `[0, numValid)` is live
     *  (still feasible). [numValid] is a [RevInt] on the engine's reversible trail, so backtrack
     *  restores it in O(1); the sparse-set invariant — removals only swap a dead tuple to the
     *  current end and decrement — means restoring [numValid] alone restores the exact live *set*
     *  (the suffix `[numValid, oldNumValid)` holds precisely the tuples removed since the mark, in
     *  some order), so [validTuples] needs no copy or undo. This factor therefore no longer
     *  implements `SnapshottablePayload` — the O(numTuples) per-push snapshot is gone.
     *
     *  [started] gates the first full sweep; afterwards a fire that drains an empty dirty-variable
     *  delta has nothing to re-filter and returns immediately. The flag drifts across push/pop (a
     *  drained delta plus the reversible [numValid] make a stale `true` harmless — the live set is
     *  already restored, and any forward narrowing re-wakes via its event). */
    private class Str2State(val validTuples: IntArray, numValidInit: Int, state: PropagationState) {
        var started: Boolean = false
        private val numValidCell = RevInt(state, numValidInit)
        var numValid: Int
            get() = numValidCell.value
            set(value) = numValidCell.set(value)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        (state.refPayload[factorId] as LsState).minDist > 0

    /** Graded violation: the **minimum Hamming distance** from the current `xs` assignment to any
     *  allowed tuple — the fewest columns that must change to satisfy the table; `0` iff some tuple
     *  matches exactly. Gives CBLS a gradient toward the nearest tuple instead of a flat binary cost.
     *
     *  Maintained incrementally: [LsState.dist] holds each tuple's Hamming distance and
     *  [LsState.minDist] their minimum, so a query is O(1) and a move is O(numTuples) — the one
     *  changed column shifts each tuple's distance by at most one — rather than O(numTuples · arity). */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        (state.refPayload[factorId] as LsState).minDist

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val dist = IntArray(numTuples)
        var minD = arity
        for (row in 0 until numTuples) {
            val base = row * arity
            var d = 0
            for (col in 0 until arity) {
                if (state.assignment.intValue(xs[col]) != tuples[base + col]) d++
            }
            dist[row] = d
            if (d < minD) minD = d
        }
        state.refPayload[factorId] = LsState(dist, minD)
    }

    /** Recompute the minimum tuple distance for [intVar] changing [oldV] → [newV]. Only the
     *  column(s) [intVar] occupies shift a tuple's distance (by ±1), so each tuple is an O(1)
     *  update off its stored [LsState.dist]. When [commit], the new per-tuple distances are written
     *  back (used by [applyIntSet]); otherwise they are only probed (used by [deltaIfIntSet]). */
    private fun rescanForChange(s: LsState, intVar: Int, oldV: Int, newV: Int, commit: Boolean): Int {
        var minD = arity
        val col = singleColumnByVar[intVar]
        if (col >= 0) {
            for (row in 0 until numTuples) {
                val t = tuples[row * arity + col]
                val d = s.dist[row] + (if (newV != t) 1 else 0) - (if (oldV != t) 1 else 0)
                if (commit) s.dist[row] = d
                if (d < minD) minD = d
            }
        } else {
            val cols = multiColumnsByVar.getValue(intVar)
            for (row in 0 until numTuples) {
                val base = row * arity
                var d = s.dist[row]
                for (c in cols) {
                    val t = tuples[base + c]
                    d += (if (newV != t) 1 else 0) - (if (oldV != t) 1 else 0)
                }
                if (commit) s.dist[row] = d
                if (d < minD) minD = d
            }
        }
        return minD
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as LsState
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        return rescanForChange(s, intVar, old, newValue, commit = false) - s.minDist
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as LsState
        val newVal = state.assignment.intValue(intVar)
        if (newVal == oldValue) return 0
        val before = s.minDist
        val minD = rescanForChange(s, intVar, oldValue, newVal, commit = true)
        s.minDist = minD
        return minD - before
    }

    /** Per-worker LS state: [dist] is each tuple's Hamming distance from the current assignment;
     *  [minDist] their running minimum (the graded violation). Held in `refPayload`, so it is
     *  per-[LocalSearchState] and never shared across workers. */
    private class LsState(val dist: IntArray, var minDist: Int)

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
     * Backtrack correctness comes from [Str2State.numValid] being a reversible cell on the engine's
     * undo trail: a pop restores the live-set size (hence the live set) in O(1).
     *
     * Per-prune antecedents and the conflict reason are hole-aware via
     * [collectHoleAndBoundAntecedents].
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val s = (state.refPayload[factorId] as? Str2State) ?: run {
            val fresh = Str2State(IntArray(numTuples) { it }, numTuples, state)
            state.refPayload[factorId] = fresh
            fresh
        }
        // Incremental fast path (#624): a fire that drains an empty dirty-variable delta saw no column
        // change since the last sweep, so STR2 is still at its fixpoint and would deduce nothing new.
        // The first fire (not yet started) always sweeps. A prune below re-wakes its column, so the
        // delta carries the cascade across fires.
        val dirty = state.drainIntEventDirtyVars(factorId)
        if (s.started && dirty.isEmpty()) return true
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
        s.started = true
        return true
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    /** Feasibility-preserving neighbourhood: a *tuple jump*. When some allowed tuple matches
     *  exactly, moving the columns to any other allowed tuple lands on that tuple — still
     *  satisfied. Each jump is offered as a compound (single survivor demoted by the sink), so
     *  the engine can relocate columns to clear a coupled constraint without leaving the table. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (numTuples < 2) return
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_JUMP_CAP && attempts < STRUCTURED_JUMP_CAP * JUMP_ATTEMPT_STRIDE) {
            attempts++
            val row = state.rng.nextInt(numTuples)
            val parts = buildTupleMove(state, row) ?: continue
            if (parts.isEmpty()) continue // already on this tuple — no move.
            sink.addCompound(parts)
            emitted++
        }
    }

    /** Build the set-moves that land [xs] exactly on tuple [row], or null if the row is
     *  unreachable (a value out of domain, or a variable repeated across columns whose tuple
     *  entries disagree). Columns already matching are omitted. */
    private fun buildTupleMove(state: LocalSearchState, row: Int): List<Move>? {
        val base = row * arity
        for (col in 0 until arity) {
            val v = xs[col]
            val target = tuples[base + col]
            if (target !in state.problem.intDomains[v]) return null
            for (prev in 0 until col) {
                if (xs[prev] == v && tuples[base + prev] != target) return null
            }
        }
        val parts = ArrayList<Move>(arity)
        for (col in 0 until arity) {
            val v = xs[col]
            var dup = false
            for (prev in 0 until col) {
                if (xs[prev] == v) {
                    dup = true
                    break
                }
            }
            if (dup) continue
            val target = tuples[base + col]
            if (state.assignment.intValue(v) != target) parts.add(Move.IntSet(v, target))
        }
        return parts
    }

    /** Feasible init: set [xs] to the first allowed tuple all of whose entries are in domain,
     *  consistent across any repeated variable, and compatible with frozen assignments. Returns
     *  false (leaving the random assignment) if no such tuple exists. */
    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        for (row in 0 until numTuples) {
            val base = row * arity
            var usable = true
            for (col in 0 until arity) {
                val v = xs[col]
                val target = tuples[base + col]
                if (target !in state.problem.intDomains[v]) {
                    usable = false
                    break
                }
                if (state.assumptions.isFrozenInt(v) && state.assignment.intValue(v) != target) {
                    usable = false
                    break
                }
                var conflict = false
                for (prev in 0 until col) {
                    if (xs[prev] == v && tuples[base + prev] != target) {
                        conflict = true
                        break
                    }
                }
                if (conflict) {
                    usable = false
                    break
                }
            }
            if (!usable) continue
            for (col in 0 until arity) {
                val v = xs[col]
                if (!state.assumptions.isFrozenInt(v)) state.assignment.setInt(v, tuples[base + col])
            }
            return true
        }
        return false
    }

    private companion object {
        /** Cap on tuple-jump compounds offered per [proposeStructuredMoves] call. */
        const val STRUCTURED_JUMP_CAP: Int = 4

        /** Rejection-sampling attempts per requested jump before giving up. */
        const val JUMP_ATTEMPT_STRIDE: Int = 4
    }
}
