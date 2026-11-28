package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.FactorReduction.Rewrite
import com.eignex.klause.ir.FactorReduction.Unchanged
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.SpanIntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.util.arrays.argsortBy
import com.eignex.util.collections.LongArrayList

/**
 * The values of [xs] equal one row of [tuples], stored row-major: `tuples(i, j)` is at `tuples(i * arity + j)`.
 *
 * Propagation is STR2 simple tabular reduction: each `xs(j)` is tightened to the column values of the rows
 * whose every cell is still domain-feasible.
 */
class Table private constructor(
    /** The variable ids forming each candidate tuple. */
    val xs: IntArray,
    /** Allowed tuples, row-major; length is a multiple of `xs.size`. */
    val tuples: LongArray,
    /** Per-cell upper bound for a short-support table: a cell accepts `[tuples(cell), hi(cell)]`, and a `*`
     *  wildcard is `[Long.MIN_VALUE, Long.MAX_VALUE]`. `null` for a ground table. */
    val hi: LongArray?,
    cachedTupleKey: LongArray?,
) : Factor {

    constructor(xs: IntArray, tuples: LongArray) : this(xs, tuples, null, null)

    constructor(xs: IntArray, tuples: LongArray, hi: LongArray?) : this(xs, tuples, hi, null)

    /** Number of variables per tuple. */
    val arity: Int = xs.size

    /** Number of tuples. */
    val numTuples: Int = tuples.size / arity

    init {
        require(xs.isNotEmpty()) { "table: empty xs" }
        require(tuples.size % arity == 0) { "table: tuples length must be a multiple of xs.size" }
        require(numTuples > 0) { "table: at least one tuple required" }
        require(hi == null || hi.size == tuples.size) { "table: hi bounds must align with tuples" }
    }

    internal fun cellHi(row: Int, col: Int): Long = hi?.get(row * arity + col) ?: tuples[row * arity + col]

    // Invariant under a variable remap, so it is carried across remaps, keeping the row sort out of symmetry
    // refinement's per-round hot path.
    private var cachedTupleKey: LongArray? = cachedTupleKey

    private fun tupleKey(): LongArray = cachedTupleKey ?: run {
        // Rows are a set, so they are sorted. Short tables tie-break by upper bound and interleave it after each
        // lower bound so an interval cell never collides with a point.
        val order = argsortBy(numTuples) { r1, r2 ->
            var c = 0
            var d = 0
            while (c < arity && d == 0) {
                d = tuples[r1 * arity + c].compareTo(tuples[r2 * arity + c])
                if (d == 0 && hi != null) d = cellHi(r1, c).compareTo(cellHi(r2, c))
                c++
            }
            d
        }
        // A flat array write: keying a wide table dominates presolve on table-heavy models.
        val perCell = if (hi == null) 1 else 2
        val words = LongArray(2 + numTuples * arity * perCell)
        words[0] = arity.toLong()
        words[1] = numTuples.toLong()
        var w = 2
        for (r in order) {
            val base = r * arity
            for (c in 0 until arity) {
                words[w++] = tuples[base + c]
                if (hi != null) words[w++] = cellHi(r, c)
            }
        }
        words
    }.also { cachedTupleKey = it }

    // Carries the tuple key forward only if already computed: forcing it would pay the O(tuples) row sort on
    // every remap, even when nothing reads the key.
    override fun remap(mapping: VarRemap): Factor = Table(
        mapping.ints(xs),
        tuples,
        hi,
        cachedTupleKey,
    )

    // Rows where `(v − offset)` is not divisible by scale can never match and drop; survivors store
    // `(v − offset) / scale`. Null when no row survives, leaving the table for propagation to refute.
    override fun substituteAffine(x: Int, scale: Int, offset: Int, replacement: Int): Factor? {
        // Interval and wildcard cells would need their bounds rewritten; not worth it on a rare path.
        if (hi != null) return null
        if (scale == 0 || x !in xs) return null
        val cols = xs.indices.filter { xs[it] == x }
        val kept = (0 until numTuples).filter { r -> cols.all { c -> (tuples[r * arity + c] - offset) % scale == 0L } }
        if (kept.isEmpty()) return null
        val newXs = IntArray(arity) { if (xs[it] == x) replacement else xs[it] }
        val newTuples = LongArray(kept.size * arity)
        var w = 0
        for (r in kept) {
            for (c in 0 until arity) {
                val v = tuples[r * arity + c]
                newTuples[w++] = if (xs[c] == x) (v - offset) / scale else v
            }
        }
        return Table(newXs, newTuples)
    }

    // Allocation-free and early-exiting: the affine scan calls this per candidate, where a rewrite is O(tuples).
    override fun canSubstituteAffine(x: Int, scale: Int, offset: Int, replacement: Int): Boolean {
        if (hi != null) return false
        if (scale == 0 || x !in xs) return false
        val cols = xs.indices.filter { xs[it] == x }
        return (0 until numTuples).any { r -> cols.all { c -> (tuples[r * arity + c] - offset) % scale == 0L } }
    }

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.TABLE, structuralKeyWeight, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.TABLE, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.intVars(xs)
        sink.constWords(tupleKey())
    }

    override fun remapValues(valueMap: (Long) -> Long): Factor {
        val h = hi ?: return Table(xs, LongArray(tuples.size) { valueMap(tuples[it]) })

        // Wildcard sentinels are not domain values and pass through unchanged.
        fun wild(i: Int) = tuples[i] == Long.MIN_VALUE && h[i] == Long.MAX_VALUE
        return Table(
            xs,
            LongArray(tuples.size) { if (wild(it)) tuples[it] else valueMap(tuples[it]) },
            LongArray(h.size) { if (wild(it)) h[it] else valueMap(h[it]) },
        )
    }

    override val variables: VarList = SpanIntVars(xs)

    // Keying cost is dominated by the flat tuple count, not the arity.
    override val structuralKeyWeight: Int get() = xs.size + tuples.size

    // Drops tuples with a cell outside its domain. A single survivor pins every variable; no survivor is left to
    // the propagator to refute. Short-support cells need the propagator's cell logic, so only ground tables.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        if (hi != null || numTuples > REDUCE_TUPLE_CAP) return Unchanged
        var survivors: LongArrayList? = null
        for (t in 0 until numTuples) {
            var alive = true
            var c = 0
            while (c < arity) {
                if (tuples[t * arity + c] !in domains[xs[c]]) {
                    alive = false
                    break
                }
                c++
            }
            if (alive) {
                survivors?.let { kept ->
                    for (k in 0 until arity) kept.add(tuples[t * arity + k])
                }
            } else if (survivors == null) {
                survivors = LongArrayList(t * arity)
                for (k in 0 until t * arity) survivors.add(tuples[k])
            }
        }
        val kept = survivors ?: return Unchanged
        val survivorCount = kept.size / arity
        return when {
            survivorCount == 0 -> Unchanged

            survivorCount == 1 -> Rewrite(
                List(arity) { c -> Linear(longArrayOf(1L), intArrayOf(xs[c]), LinearOp.EQ, kept[c]) },
            )

            else -> Rewrite(listOf(Table(xs, kept.toLongArray())))
        }
    }

    private companion object {
        // A giant table is not re-swept every presolve round.
        const val REDUCE_TUPLE_CAP: Int = 4096
    }
}
