package com.eignex.klause.factor.global

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarRemap

/**
 * Pairwise distinct ordered integer tuples. Coordinates may repeat within and across [tuples].
 * Storage and preparation depend on the number of coordinates, without enumerating tuple pairs or
 * domain products. Propagation rejects equal fixed tuples; local search counts duplicate tuples.
 */
class AllDifferentTuples(
    /** Equal-width ordered variable rows, borrowed read-only for the factor's lifetime. */
    val tuples: List<IntArray>,
) : Factor {
    init {
        require(tuples.size >= 2) { "AllDifferentTuples needs at least two tuples" }
        require(tuples.all { it.size == tuples[0].size }) { "AllDifferentTuples needs equal-width tuples" }
    }

    override val variables: IntVars = IntVars(tuples.flatMap { it.asIterable() }.distinct().toIntArray())

    internal val repeatedTuple: Boolean = run {
        val seen = HashSet<List<Int>>()
        tuples.any { !seen.add(it.toList()) }
    }

    override fun remap(mapping: VarRemap): AllDifferentTuples = AllDifferentTuples(tuples.map(mapping::ints))

    override fun structuralKey(): StructuralKey = key()

    override fun remapStructuralHash(mapping: VarRemap): Int = key(mapping).hashCode()

    private fun key(mapping: VarRemap? = null): StructuralKey = StructuralKey.of(FactorKind.ALL_DIFFERENT_TUPLES) {
        val rows = if (mapping == null) tuples else tuples.map(mapping::ints)
        val ordered = rows.sortedWith { a, b ->
            var order = 0
            for (k in a.indices) {
                order = a[k].compareTo(b[k])
                if (order != 0) break
            }
            order
        }
        int(ordered.size)
        for (row in ordered) ints(row)
    }

    override fun isValueAnonymous(): Boolean = true

    override fun remapValues(valueMap: (Long) -> Long): Factor = this
}

internal class TupleValues(val values: LongArray) {
    private val hash = values.contentHashCode()

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean = other is TupleValues && values.contentEquals(other.values)
}
