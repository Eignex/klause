package com.eignex.klause.factor.table

import com.eignex.klause.factor.table.internals.TableLsState
import com.eignex.klause.ir.ceilingOrNull
import com.eignex.klause.ir.floorOrNull
import com.eignex.klause.ir.randomValue
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.MutableIntObjectMap

/** LS invariant for [Table]. Constructed by the local-search projection. */
internal class TableInvariant(
    private val xs: IntArray,
    private val tuples: LongArray,
    private val arity: Int,
    private val numTuples: Int,
    private val singleColumnByVar: IntIntMap,
    private val multiColumnsByVar: MutableIntObjectMap<IntArray>,
    /** Per-cell upper bound for a short-support table (see [com.eignex.klause.factor.table.Table.hi]);
     *  null when every cell is a point (a ground table). */
    private val hi: LongArray?,
) : Invariant {

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        (state.refPayload[factorId] as TableLsState).minDist > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        (state.refPayload[factorId] as TableLsState).minDist

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val dist = IntArray(numTuples)
        var minD = arity
        for (row in 0 until numTuples) {
            var d = 0
            for (col in 0 until arity) {
                if (!tableCellContains(tuples, hi, arity, row, col, state.assignment.intValue(xs[col]))) d++
            }
            dist[row] = d
            if (d < minD) minD = d
        }
        state.refPayload[factorId] = TableLsState(dist, minD)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val s = state.refPayload[factorId] as TableLsState
        data class Scored(val row: Int, val distance: Int)
        val scored = ArrayList<Scored>(numTuples)
        for (row in 0 until numTuples) scored.add(Scored(row, s.dist[row]))
        scored.sortBy { it.distance }
        val topK = minOf(2, scored.size)
        for (k in 0 until topK) {
            val row = scored[k].row
            for (col in 0 until arity) {
                val cur = state.assignment.intValue(xs[col])
                // Already inside the cell's interval (covers points that already match and wildcards).
                if (tableCellContains(tuples, hi, arity, row, col, cur)) continue
                // Move toward the nearest value the cell accepts (the point value, or the clamped bound).
                val target = cur.coerceIn(
                    tableCellLo(tuples, arity, row, col),
                    tableCellHi(tuples, hi, arity, row, col),
                )
                if (target != cur && target in state.rootDomains[xs[col]]) {
                    sink.addChannelingIntSet(state, xs[col], target)
                }
            }
        }
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (numTuples < 2 && hi == null) return
        var emitted = 0
        var attempts = 0
        while (emitted < TABLE_STRUCTURED_JUMP_CAP &&
            attempts < TABLE_STRUCTURED_JUMP_CAP * TABLE_JUMP_ATTEMPT_STRIDE
        ) {
            attempts++
            val row = state.rng.nextInt(numTuples)
            val parts = tableBuildTupleMove(state, xs, tuples, arity, hi, row, randomize = true) ?: continue
            if (parts.isEmpty()) continue
            sink.addCompound(parts)
            emitted++
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        for (row in 0 until numTuples) {
            val parts = tableBuildTupleMove(state, xs, tuples, arity, hi, row) ?: continue
            for (part in parts) state.assignment.setInt(part.varId, part.newValue)
            return true
        }
        return false
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val s = state.refPayload[factorId] as TableLsState
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        return tableRescanForChange(
            s, tuples, arity, numTuples, singleColumnByVar, multiColumnsByVar, hi, intVar, old, newValue,
            commit = false,
        ) - s.minDist
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val s = state.refPayload[factorId] as TableLsState
        val newVal = state.assignment.intValue(intVar)
        if (newVal == oldValue) return 0
        val before = s.minDist
        val minD = tableRescanForChange(
            s, tuples, arity, numTuples, singleColumnByVar, multiColumnsByVar, hi, intVar, oldValue, newVal,
            commit = true,
        )
        s.minDist = minD
        return minD - before
    }
}

private const val TABLE_STRUCTURED_JUMP_CAP: Int = 4
private const val TABLE_JUMP_ATTEMPT_STRIDE: Int = 4

/** Recompute the minimum Hamming distance after changing [intVar] from [oldV] to [newV], where a
 *  cell matches (costs 0) when the value lies in its interval `[lo, hi]`. If [commit] is true the
 *  per-row distances in [s] are updated in place. */
@Suppress("LongParameterList") // per-column rescan needs the full table view plus the interval bounds
internal fun tableRescanForChange(
    s: TableLsState,
    tuples: LongArray,
    arity: Int,
    numTuples: Int,
    singleColumnByVar: IntIntMap,
    multiColumnsByVar: MutableIntObjectMap<IntArray>,
    hi: LongArray?,
    intVar: Int,
    oldV: Long,
    newV: Long,
    commit: Boolean,
): Int {
    var minD = arity
    val col = singleColumnByVar[intVar]
    if (col >= 0) {
        for (row in 0 until numTuples) {
            val miss = if (tableCellContains(tuples, hi, arity, row, col, newV)) 0 else 1
            val was = if (tableCellContains(tuples, hi, arity, row, col, oldV)) 0 else 1
            val d = s.dist[row] + miss - was
            if (commit) s.dist[row] = d
            if (d < minD) minD = d
        }
    } else {
        val cols = multiColumnsByVar.getValue(intVar)
        for (row in 0 until numTuples) {
            var d = s.dist[row]
            for (c in cols) {
                val miss = if (tableCellContains(tuples, hi, arity, row, c, newV)) 0 else 1
                val was = if (tableCellContains(tuples, hi, arity, row, c, oldV)) 0 else 1
                d += miss - was
            }
            if (commit) s.dist[row] = d
            if (d < minD) minD = d
        }
    }
    return minD
}

// Repeated occurrences must choose one value from the intersection of all their cells.
internal fun tableBuildTupleMove(
    state: LocalSearchState,
    xs: IntArray,
    tuples: LongArray,
    arity: Int,
    hi: LongArray?,
    row: Int,
    randomize: Boolean = false,
): List<Move.IntSet>? {
    val parts = ArrayList<Move.IntSet>(arity)
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
        // An input move can rewrite a defined coordinate through its cone, outside the chosen row.
        if (state.invariants?.isDefinedInt(v) == true) return null
        val domain = state.rootDomains[v]
        var lo = domain.min
        var upper = domain.max
        for (other in col until arity) {
            if (xs[other] != v) continue
            lo = maxOf(lo, tableCellLo(tuples, arity, row, other))
            upper = minOf(upper, tableCellHi(tuples, hi, arity, row, other))
        }
        if (lo > upper) return null
        val first = domain.ceilingOrNull(lo)?.takeIf { it <= upper } ?: return null
        val current = state.assignment.intValue(v)
        val target = when {
            state.assumptions.isFrozenInt(v) -> {
                if (current !in lo..upper || current !in domain) return null
                current
            }
            randomize && first < upper -> {
                val last = domain.floorOrNull(upper) ?: return null
                domain.withMinAtLeast(first).withMaxAtMost(last).randomValue(state.rng)
            }
            current in lo..upper && current in domain -> current
            else -> first
        }
        if (current != target) parts.add(Move.IntSet(v, target))
    }
    return parts
}

/** Lower/upper bound of the interval cell (row, col); equal for a point. */
internal fun tableCellLo(tuples: LongArray, arity: Int, row: Int, col: Int): Long = tuples[row * arity + col]
internal fun tableCellHi(tuples: LongArray, hi: LongArray?, arity: Int, row: Int, col: Int): Long =
    hi?.get(row * arity + col) ?: tuples[row * arity + col]

/** Whether the cell (row, col) accepts [value] — i.e. `value ∈ [lo, hi]`. */
internal fun tableCellContains(
    tuples: LongArray,
    hi: LongArray?,
    arity: Int,
    row: Int,
    col: Int,
    value: Long,
): Boolean = value in tableCellLo(tuples, arity, row, col)..tableCellHi(tuples, hi, arity, row, col)

/** A point cell accepts exactly one value (`lo == hi`). */
internal fun tableCellIsPoint(tuples: LongArray, hi: LongArray?, arity: Int, row: Int, col: Int): Boolean =
    tableCellLo(tuples, arity, row, col) == tableCellHi(tuples, hi, arity, row, col)

/** A free (`*`) cell accepts any value — the unbounded interval `[MIN, MAX]`. */
internal fun tableCellIsFree(tuples: LongArray, hi: LongArray?, arity: Int, row: Int, col: Int): Boolean =
    tableCellLo(tuples, arity, row, col) == Long.MIN_VALUE && tableCellHi(tuples, hi, arity, row, col) == Long.MAX_VALUE

/** Build the var→column lookup structures. */
internal fun tableColumnMaps(xs: IntArray, arity: Int): Pair<IntIntMap, MutableIntObjectMap<IntArray>> {
    val occ = MutableIntObjectMap<IntArrayList>()
    for (c in 0 until arity) occ.getOrPut(xs[c]) { IntArrayList() }.add(c)
    val singleKeys = IntArrayList()
    val singleVals = IntArrayList()
    val multi = MutableIntObjectMap<IntArray>()
    occ.forEach { v, cols ->
        if (cols.size == 1) {
            singleKeys.add(v)
            singleVals.add(cols[0])
        } else {
            multi.put(v, cols.toIntArray())
        }
    }
    return Pair(
        IntIntMap.build(singleKeys.toIntArray(), singleVals.toIntArray(), absent = -1),
        multi,
    )
}
