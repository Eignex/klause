package com.eignex.klause.factor.table

import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.Linearizer
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntIntMap
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
    val tuples: IntArray,
    /** The cached tuple-derived key fragment when copying from a factor over the *same* [tuples]
     *  (a pure variable [remap]); `null` forces a fresh computation when the tuples differ. */
    cachedTupleKey: LongArray?,
) : Factor {

    constructor(xs: IntArray, tuples: IntArray) : this(xs, tuples, null)

    /** Number of variables per tuple. */
    val arity: Int = xs.size

    /** Number of tuples. */
    val numTuples: Int = tuples.size / arity

    init {
        require(xs.isNotEmpty()) { "table: empty xs" }
        require(tuples.size % arity == 0) { "table: tuples length must be a multiple of xs.size" }
        require(numTuples > 0) { "table: at least one tuple required" }
    }

    // The tuple-derived part of the key (arity, count, sorted tuple set) is invariant under a variable
    // remap, so it is computed once and carried across remaps — keeping the expensive row sort out of
    // symmetry refinement's per-round hot path. Cleared (recomputed) only when the tuples change.
    private var cachedTupleKey: LongArray? = cachedTupleKey

    private fun tupleKey(): LongArray = cachedTupleKey ?: run {
        val order = argsortBy(numTuples) { r1, r2 ->
            var c = 0
            var d = 0
            while (c < arity && d == 0) {
                d = tuples[r1 * arity + c].compareTo(tuples[r2 * arity + c])
                c++
            }
            d
        }
        // Fill the key fragment (arity, count, then the row-sorted tuple ints) straight into its backing
        // array. The equivalent StructuralKeyBuilder form appends one element at a time, each with a
        // capacity/grow check; a wide table's key is the dominant cost when presolve keys a table-heavy
        // model, so the inner loop stays a flat array write.
        val words = LongArray(2 + numTuples * arity)
        words[0] = arity.toLong()
        words[1] = numTuples.toLong()
        var w = 2
        for (r in order) {
            val base = r * arity
            for (c in 0 until arity) words[w++] = tuples[base + c].toLong()
        }
        words
    }.also { cachedTupleKey = it }

    // A remap keeps [tuples], so it carries the tuple key forward *if already computed* — but must not
    // force it: an affine alias-fold (or any remap before the key is first needed) would otherwise pay the
    // O(tuples) row sort on every table, which on a float-derived table over a wide scaled domain (a
    // bucket-index table with one row per bucket) is seconds. Pass the cached value (possibly `null`); the
    // remapped table computes it lazily, and identically, only when something actually reads the key.
    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Table(
        xs.remapVars(intMap),
        tuples,
        cachedTupleKey,
    )

    // Affine substitution `x = scale·replacement + offset` rewrites every column holding x: a row's
    // required value v for x means replacement = (v − offset) / scale, so rows where (v − offset) is
    // not divisible by scale can never match and drop, and surviving rows store the replacement value.
    // Representable for any non-zero scale (a shift, negation, or stride). Declines (null) only when no
    // row survives — leaving the original table for propagation to refute.
    override fun substituteAffine(x: Int, scale: Int, offset: Int, replacement: Int): Factor? {
        if (scale == 0 || x !in xs) return null
        val cols = xs.indices.filter { xs[it] == x }
        val kept = (0 until numTuples).filter { r -> cols.all { c -> (tuples[r * arity + c] - offset) % scale == 0 } }
        if (kept.isEmpty()) return null
        val newXs = IntArray(arity) { if (xs[it] == x) replacement else xs[it] }
        val newTuples = IntArray(kept.size * arity)
        var w = 0
        for (r in kept) {
            for (c in 0 until arity) {
                val v = tuples[r * arity + c]
                newTuples[w++] = if (xs[c] == x) (v - offset) / scale else v
            }
        }
        return Table(newXs, newTuples)
    }

    // Column c ↔ xs[c], so xs order is kept (positional); rows are a set, so rows are sorted. Encodes
    // the full var sequence and tuple set — collision-free up to variable identity. Only `ints(xs)`
    // varies under a remap; the tuple part is the cached [tupleKey] fragment.
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.TABLE) {
        ints(xs)
        words(tupleKey())
    }

    /** Relabel every tuple entry (#374): each column holds domain values of its variable, all in the
     *  one value universe, so a single map relabels the whole table. */
    override fun remapValues(valueMap: (Int) -> Int): Factor = Table(xs, IntArray(tuples.size) { valueMap(tuples[it]) })

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    // The key embeds the full sorted tuple set, so its cost is dominated by the flat tuple count, not
    // the variable count — a wide table is far more expensive to key than its arity suggests.
    override val structuralKeyWeight: Int get() = xs.size + tuples.size

    /** Var id → the single tuple column it occupies (the common case). Vars that appear in more than
     *  one column are absent here and listed in [multiColumnsByVar] instead. */
    @Suppress("EXPOSED_PROPERTY_TYPE")
    val singleColumnByVar: IntIntMap

    /** Var id → all tuple columns it occupies, for vars that appear more than once in [xs]. */
    val multiColumnsByVar: Map<Int, IntArray>

    init {
        val (single, multi) = tableColumnMaps(xs, arity)
        singleColumnByVar = single
        multiColumnsByVar = multi
    }

    override fun asPropagator(): Propagator = TablePropagator(boolVars, intVars, xs, tuples, arity, numTuples)

    override fun asInvariant(): Invariant =
        TableInvariant(xs, tuples, arity, numTuples, singleColumnByVar, multiColumnsByVar)

    override fun asLinearizer(): Linearizer = TableLinearizer(xs, tuples, arity, numTuples)
}
