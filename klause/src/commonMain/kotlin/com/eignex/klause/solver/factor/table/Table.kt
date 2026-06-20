package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.factor.table.internals.TableLsState
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.IntEvent
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
    override val xs: IntArray,
    /** Allowed tuples, row-major; length is a multiple of `xs.size`. */
    override val tuples: IntArray,
) : Factor,
    TablePropagator,
    TableInvariant {

    /** Number of variables per tuple. */
    override val arity: Int = xs.size

    /** Number of tuples. */
    override val numTuples: Int = tuples.size / arity

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
        val (single, multi) = tableColumnMaps(xs, arity)
        singleColumnByVar = single
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

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as TableLsState
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        return tableRescanForChange(
            s, tuples, arity, numTuples, singleColumnByVar, multiColumnsByVar, intVar, old, newValue, commit = false,
        ) - s.minDist
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as TableLsState
        val newVal = state.assignment.intValue(intVar)
        if (newVal == oldValue) return 0
        val before = s.minDist
        val minD = tableRescanForChange(
            s, tuples, arity, numTuples, singleColumnByVar, multiColumnsByVar, intVar, oldValue, newVal, commit = true,
        )
        s.minDist = minD
        return minD - before
    }
}
