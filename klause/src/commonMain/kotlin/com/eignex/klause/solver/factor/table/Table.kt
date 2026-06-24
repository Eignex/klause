package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.remapVars
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
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.TABLE) {
        ints(xs)
        int(arity)
        int(numTuples)
        val order = (0 until numTuples).sortedWith { r1, r2 ->
            var c = 0
            var d = 0
            while (c < arity && d == 0) {
                d = tuples[r1 * arity + c].compareTo(tuples[r2 * arity + c])
                c++
            }
            d
        }
        for (r in order) for (c in 0 until arity) int(tuples[r * arity + c])
    }

    /** Relabel every tuple entry (#374): each column holds domain values of its variable, all in the
     *  one value universe, so a single map relabels the whole table. */
    override fun remapValues(valueMap: (Int) -> Int): Factor = Table(xs, IntArray(tuples.size) { valueMap(tuples[it]) })

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

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
