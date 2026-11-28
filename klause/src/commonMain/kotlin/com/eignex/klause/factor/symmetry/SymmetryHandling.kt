package com.eignex.klause.factor.symmetry

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.toSortedIntArray

/**
 * Whole-group symmetry handling as one propagator-only factor: it carries the verified automorphism [generators],
 * and [SymmetryPropagator] enforces every generator's lex-leader at each search node, covering the full group
 * without one lex factor per element.
 *
 * Each generator is a kind-preserving permutation `(intImage, boolImage)` over the current variable ids; an
 * identity entry fixes the variable. The factor watches only the group's support. Its local-search projection is
 * [NoInvariant]: symmetry breaking is backtrack-only.
 */
class SymmetryHandling internal constructor(
    /** The generators, each `(intImage, boolImage)` over the current variable ids. */
    val generators: List<Pair<IntArray, IntArray>>,
    internal val lexOrder: IntArray?,
) : Factor {

    /** Construct a restriction comparing integer coordinates before Boolean coordinates. */
    constructor(generators: List<Pair<IntArray, IntArray>>) : this(generators, null)

    init {
        require(generators.isNotEmpty()) { "SymmetryHandling needs at least one generator" }
        if (lexOrder != null) {
            val degree = generators.first().first.size + generators.first().second.size
            val seen = BooleanArray(degree)
            require(lexOrder.size == degree && lexOrder.all { v ->
                (v in 0 until degree && !seen[v]).also { if (it) seen[v] = true }
            })
        }
    }

    override val variables: VarList = MixedVars(spanInts = support { it.first }, boolVars = support { it.second })

    private inline fun support(image: (Pair<IntArray, IntArray>) -> IntArray): IntArray {
        val moved = IntHashSet()
        for (g in generators) {
            val map = image(g)
            for (v in map.indices) if (map[v] != v) moved.add(v)
        }
        return if (moved.isEmpty()) emptyIntArray() else moved.toSortedIntArray()
    }

    override fun remap(mapping: VarRemap): Factor {
        // Conjugate each generator by the remap: σ' = m ∘ σ ∘ m⁻¹. A remap that is not injective on a
        // generator's support (variable elimination / column merge) cannot carry the permutation, so
        // that generator is dropped — sound, since dropping a symmetry break only forgoes pruning.
        val remapped = generators.mapNotNull { conjugate(it, mapping) }
        if (remapped.isEmpty()) {
            val identity = IntArray(mapping.intCount) { it } to IntArray(mapping.boolCount) { it }
            return SymmetryHandling(listOf(identity))
        }
        val order = lexOrder?.let { original ->
            val nInt = generators.first().first.size
            val degree = mapping.intCount + mapping.boolCount
            val mapped = original.map { v ->
                when {
                    v < nInt && v < mapping.intCount -> mapping.int(v).takeIf { it in 0 until mapping.intCount }
                    v >= nInt && v - nInt < mapping.boolCount ->
                        mapping.bool(v - nInt).takeIf { it in 0 until mapping.boolCount }?.plus(mapping.intCount)
                    else -> null
                }
            }.filterNotNull().distinct()
            val present = mapped.toHashSet()
            (mapped + (0 until degree).filter { it !in present }).toIntArray()
        }
        return SymmetryHandling(remapped, order)
    }

    private fun conjugate(g: Pair<IntArray, IntArray>, mapping: VarRemap): Pair<IntArray, IntArray>? {
        val newInt = conjugateOne(g.first, mapping::int, IntArray(mapping.intCount) { it }) ?: return null
        val newBool = conjugateOne(g.second, mapping::bool, IntArray(mapping.boolCount) { it }) ?: return null
        return newInt to newBool
    }

    private fun conjugateOne(perm: IntArray, imageOf: (Int) -> Int, identity: IntArray): IntArray? {
        val out = identity
        for (v in perm.indices) {
            if (perm[v] == v) continue
            val nv = imageOf(v)
            val image = imageOf(perm[v])
            if (nv !in out.indices || image !in out.indices) return null
            out[nv] = image
        }
        val seen = BooleanArray(out.size)
        for (x in out) {
            if (seen[x]) return null // not a bijection
            seen[x] = true
        }
        return out
    }

    // Builder form rather than the allocation-free KeySink: the key encodes generator permutation arrays,
    // not plain variable references, so the sink's var/const split doesn't model it.
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.SYMMETRY_HANDLING) {
        int(generators.size)
        for (g in generators) {
            ints(g.first)
            ints(g.second)
        }
        if (lexOrder != null) {
            int(-1)
            ints(lexOrder)
        }
    }
}
