package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.table.internals.TableGroupCache
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
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.argsortBy

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
class Table private constructor(
    /** The variable ids forming each candidate tuple. */
    val xs: IntArray,
    /** Allowed tuples, row-major; length is a multiple of `xs.size`. */
    val tuples: LongArray,
    /** Per-cell upper bound for a short-support table: cell `row * arity + col` accepts any value in
     *  `[tuples(cell), hi(cell)]`, intersected with the variable's domain. A point cell has
     *  `hi(cell) == tuples(cell)`; a `*` wildcard is the unbounded interval `[Long.MIN, Long.MAX]`.
     *  `null` for a fully-ground table (every cell a point — the common case). */
    val hi: LongArray?,
    /** The cached tuple-derived key fragment when copying from a factor over the *same* [tuples]
     *  (a pure variable [remap]); `null` forces a fresh computation when the tuples differ. */
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

    /** Upper bound of the interval cell (row, col); equals its lower bound [tuples] for a point. */
    internal fun cellHi(row: Int, col: Int): Long = hi?.get(row * arity + col) ?: tuples[row * arity + col]

    // The tuple-derived part of the key (arity, count, sorted tuple set) is invariant under a variable
    // remap, so it is computed once and carried across remaps — keeping the expensive row sort out of
    // symmetry refinement's per-round hot path. Cleared (recomputed) only when the tuples change.
    private var cachedTupleKey: LongArray? = cachedTupleKey

    /** Shared across the rows of a `<group>` over one relation so a full-table GAC sweep that prunes
     *  nothing is discovered once and skipped by the rest. Set by the front-end that shares [tuples];
     *  carried across [remap] (same relation). Null ⇒ a lone table, no group reuse. */
    internal var groupCache: TableGroupCache? = null

    private fun tupleKey(): LongArray = cachedTupleKey ?: run {
        // Rows are a set, so order-independence comes from sorting rows into a canonical order. Short
        // tables tie-break equal-lower-bound rows by their upper bound so the key stays canonical, and
        // interleave the upper-bound word after each lower bound so an interval cell can never collide
        // with a point (a point has hi == lo). A ground table keeps the compact one-word-per-cell form.
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
        // Fill the key fragment (arity, count, then the row-sorted tuple ints) straight into its backing
        // array. The equivalent StructuralKeyBuilder form appends one element at a time, each with a
        // capacity/grow check; a wide table's key is the dominant cost when presolve keys a table-heavy
        // model, so the inner loop stays a flat array write.
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

    // A remap keeps [tuples], so it carries the tuple key forward *if already computed* — but must not
    // force it: an affine alias-fold (or any remap before the key is first needed) would otherwise pay the
    // O(tuples) row sort on every table, which on a float-derived table over a wide scaled domain (a
    // bucket-index table with one row per bucket) is seconds. Pass the cached value (possibly `null`); the
    // remapped table computes it lazily, and identically, only when something actually reads the key.
    override fun remap(mapping: VarRemap): Factor = Table(
        mapping.ints(xs),
        tuples,
        hi,
        cachedTupleKey,
    ).also { it.groupCache = groupCache }

    // Affine substitution `x = scale·replacement + offset` rewrites every column holding x: a row's
    // required value v for x means replacement = (v − offset) / scale, so rows where (v − offset) is
    // not divisible by scale can never match and drop, and surviving rows store the replacement value.
    // Representable for any non-zero scale (a shift, negation, or stride). Declines (null) only when no
    // row survives — leaving the original table for propagation to refute.
    override fun substituteAffine(x: Int, scale: Int, offset: Int, replacement: Int): Factor? {
        // A short table's interval/wildcard cells don't participate in the divisibility filter and
        // would need bounds rewritten over surviving rows; decline (the affine fold just skips it)
        // rather than carry that complexity into a rarely-hit presolve path.
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

    // Substitutability test without building the rewritten table: a row survives iff x's value is
    // divisible (after the offset shift) in every column holding x, and the substitution succeeds iff any
    // row survives. `any` early-exits on the first survivor and allocates nothing — the affine scan calls
    // this per candidate check on a wide float-derived bucket table, where the full rewrite is O(tuples).
    override fun canSubstituteAffine(x: Int, scale: Int, offset: Int, replacement: Int): Boolean {
        if (hi != null) return false
        if (scale == 0 || x !in xs) return false
        val cols = xs.indices.filter { xs[it] == x }
        return (0 until numTuples).any { r -> cols.all { c -> (tuples[r * arity + c] - offset) % scale == 0L } }
    }

    // Column c ↔ xs[c], so xs order is kept (positional); rows are a set, so rows are sorted. Encodes
    // the full var sequence and tuple set — collision-free up to variable identity. Only `ints(xs)`
    // varies under a remap; the tuple part is the cached [tupleKey] fragment.
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.TABLE, structuralKeyWeight, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.TABLE, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.intVars(xs)
        sink.constWords(tupleKey())
    }

    /** Relabel every tuple entry: each column holds domain values of its variable, all in the
     *  one value universe, so a single map relabels the whole table. */
    override fun remapValues(valueMap: (Long) -> Long): Factor {
        val h = hi ?: return Table(xs, LongArray(tuples.size) { valueMap(tuples[it]) })

        // Interval bounds are real domain values and are relabelled; the unbounded wildcard sentinels
        // (a `[MIN, MAX]` cell) are not domain values and pass through unchanged.
        fun wild(i: Int) = tuples[i] == Long.MIN_VALUE && h[i] == Long.MAX_VALUE
        return Table(
            xs,
            LongArray(tuples.size) { if (wild(it)) tuples[it] else valueMap(tuples[it]) },
            LongArray(h.size) { if (wild(it)) h[it] else valueMap(h[it]) },
        )
    }

    override val variables: VarList = SpanIntVars(xs)

    // The key embeds the full sorted tuple set, so its cost is dominated by the flat tuple count, not
    // the variable count — a wide table is far more expensive to key than its arity suggests.
    override val structuralKeyWeight: Int get() = xs.size + tuples.size

    /** Var id → the single tuple column it occupies (the common case). Vars that appear in more than
     *  one column are absent here and listed in [multiColumnsByVar] instead. */
    internal val singleColumnByVar: IntIntMap

    /** Var id → all tuple columns it occupies, for vars that appear more than once in [xs]. */
    internal val multiColumnsByVar: MutableIntObjectMap<IntArray>

    init {
        val (single, multi) = tableColumnMaps(xs, arity)
        singleColumnByVar = single
        multiColumnsByVar = multi
    }

    /**
     * Drop tuples no assignment can use — those with a cell outside its variable's current domain — a
     * structural shrink the flat tuple set otherwise carries for the whole solve. A single survivor pins
     * every variable (the table becomes redundant equalities); no survivor is left to the propagator to
     * report. Ground tables only (a short-support table's ranges/wildcards need the propagator's cell
     * logic), and capped by [REDUCE_TUPLE_CAP] so a giant table isn't rescanned each presolve round.
     */
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        if (hi != null || numTuples > REDUCE_TUPLE_CAP) return Unchanged
        val survivors = LongArrayList()
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
            if (alive) for (k in 0 until arity) survivors.add(tuples[t * arity + k])
        }
        val survivorCount = survivors.size / arity
        return when {
            survivorCount == numTuples || survivorCount == 0 -> Unchanged

            survivorCount == 1 -> Rewrite(
                List(arity) { c -> Linear(longArrayOf(1L), intArrayOf(xs[c]), LinearOp.EQ, survivors[c]) },
            )

            else -> Rewrite(listOf(Table(xs, survivors.toLongArray())))
        }
    }

    private companion object {
        /** Above this many tuples [structuralReduce] skips the dead-tuple scan, so a giant table is not
         *  re-swept every presolve round. */
        const val REDUCE_TUPLE_CAP: Int = 4096
    }
}
