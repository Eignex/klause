package com.eignex.klause.factor.table

import com.eignex.klause.factor.table.internals.TableLsState
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.MutableIntObjectMap

/** LS invariant for [Table]. Constructed by [Table.asInvariant]. */
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
                if (target != cur && target in state.problem.intDomains[xs[col]]) {
                    sink.addChannelingIntSet(state, xs[col], target)
                }
            }
        }
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (numTuples < 2) return
        var emitted = 0
        var attempts = 0
        while (emitted < TABLE_STRUCTURED_JUMP_CAP &&
            attempts < TABLE_STRUCTURED_JUMP_CAP * TABLE_JUMP_ATTEMPT_STRIDE
        ) {
            attempts++
            val row = state.rng.nextInt(numTuples)
            val parts = tableBuildTupleMove(state, xs, tuples, arity, hi, row) ?: continue
            if (parts.isEmpty()) continue
            sink.addCompound(parts)
            emitted++
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        for (row in 0 until numTuples) {
            val base = row * arity
            var usable = true
            for (col in 0 until arity) {
                // Wildcards impose nothing; only a point cell can be landed on exactly, so a table with
                // interval cells is seeded only via its point rows (a heuristic, not a correctness path).
                if (tableCellIsFree(tuples, hi, arity, row, col)) continue
                if (!tableCellIsPoint(tuples, hi, arity, row, col)) {
                    usable = false
                    break
                }
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
                    if (tableCellIsFree(tuples, hi, arity, row, prev)) continue
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
                if (!tableCellIsPoint(tuples, hi, arity, row, col)) continue
                val v = xs[col]
                if (!state.assumptions.isFrozenInt(v)) state.assignment.setInt(v, tuples[base + col])
            }
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

/** Build the set-moves that land [xs] exactly on tuple [row], or null when unreachable. Only point
 *  and wildcard cells are landable exactly, so a tuple with an interval cell is skipped. */
internal fun tableBuildTupleMove(
    state: LocalSearchState,
    xs: IntArray,
    tuples: LongArray,
    arity: Int,
    hi: LongArray?,
    row: Int,
): List<Move>? {
    val base = row * arity
    for (col in 0 until arity) {
        if (tableCellIsFree(tuples, hi, arity, row, col)) continue
        if (!tableCellIsPoint(tuples, hi, arity, row, col)) return null
        val v = xs[col]
        val target = tuples[base + col]
        if (target !in state.problem.intDomains[v]) return null
        for (prev in 0 until col) {
            if (tableCellIsFree(tuples, hi, arity, row, prev)) continue
            if (xs[prev] == v && tuples[base + prev] != target) return null
        }
    }
    val parts = ArrayList<Move>(arity)
    for (col in 0 until arity) {
        if (!tableCellIsPoint(tuples, hi, arity, row, col)) continue
        val v = xs[col]
        var dup = false
        for (prev in 0 until col) {
            if (!tableCellIsPoint(tuples, hi, arity, row, prev)) continue
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
