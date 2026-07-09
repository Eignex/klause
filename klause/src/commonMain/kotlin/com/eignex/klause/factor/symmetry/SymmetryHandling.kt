package com.eignex.klause.factor.symmetry

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.PermutationGroup
import com.eignex.klause.util.toSortedIntArray

/**
 * Whole-group symmetry handling as a single propagator-only factor (#896): it carries the verified
 * automorphism [generators] and defers all filtering to [SymmetryPropagator], which enforces every
 * generator's lex-leader at each search node. Replacing the static enumeration of one [Factor] per
 * group element with one factor that consults the generators dynamically keeps full-group coverage
 * without materialising hundreds of lex constraints.
 *
 * Each generator is a kind-preserving permutation given as `(intImage, boolImage)` over the current
 * variable ids (`intImage[i]` / `boolImage[b]` is the image of integer / Boolean variable `i` / `b`;
 * an identity entry means the variable is fixed). The factor watches only the moved variables (the
 * group's support). It has no local-search role — [asInvariant] is [NoInvariant] — so local search
 * skips it entirely (symmetry breaking is a backtrack-only device).
 */
class SymmetryHandling(
    /** The generators, each `(intImage, boolImage)` over the current variable ids. */
    val generators: List<Pair<IntArray, IntArray>>,
) : Factor {

    init {
        require(generators.isNotEmpty()) { "SymmetryHandling needs at least one generator" }
    }

    private companion object {
        /** Cap on the lex-leaders propagated per node: the generators plus stabiliser Schreier
         *  generators, bounded so per-node propagation stays cheap (one propagator, not one factor each). */
        const val STRONG_GENERATOR_CAP = 64
    }

    override val intVars: IntArray = support { it.first }
    override val boolVars: IntArray = support { it.second }

    private inline fun support(image: (Pair<IntArray, IntArray>) -> IntArray): IntArray {
        val moved = IntHashSet()
        for (g in generators) {
            val map = image(g)
            for (v in map.indices) if (map[v] != v) moved.add(v)
        }
        return if (moved.isEmpty()) EmptyIntArray else moved.toSortedIntArray()
    }

    private val nInt: Int = generators.first().first.size
    private val nBool: Int = generators.first().second.size

    override fun asPropagator(): Propagator {
        // Expand the raw generators with stabiliser-chain Schreier generators (still genuine group
        // elements, so every lex-leader stays sound) for fuller group coverage than the generators
        // alone — the dynamic, single-propagator replacement for a static lex closure.
        val unified = generators.map { toUnified(it) }
        val strong = PermutationGroup.strongGenerators(unified, nInt + nBool, STRONG_GENERATOR_CAP)
        return SymmetryPropagator(strong.map { toSequence(it) }, strong, nInt)
    }

    override fun asInvariant(): Invariant = NoInvariant

    /** Pack a `(intImage, boolImage)` generator into one permutation over `[0, nInt+nBool)`: integer
     *  ids stay in place, Boolean ids are offset by [nInt]. Detection is kind-preserving, so the
     *  packed permutation maps the integer block and Boolean block to themselves. */
    private fun toUnified(g: Pair<IntArray, IntArray>): IntArray {
        val out = IntArray(nInt + nBool)
        for (i in 0 until nInt) out[i] = g.first[i]
        for (b in 0 until nBool) out[nInt + b] = nInt + g.second[b]
        return out
    }

    /** Order a unified permutation's support `[moved ints by id, then moved bools by id]`, pairing each
     *  variable with its image — the fixed lex order the lex-leader compares over (any fixed order is
     *  sound). Integer positions are `< nInt`; Boolean positions are shifted back by [nInt]. */
    private fun toSequence(perm: IntArray): SymmetryPropagator.Generator {
        val movedInts = (0 until nInt).filter { perm[it] != it }
        val movedBools = (nInt until nInt + nBool).filter { perm[it] != it }
        val n = movedInts.size + movedBools.size
        val left = IntArray(n)
        val right = IntArray(n)
        val isBool = BooleanArray(n)
        var k = 0
        for (v in movedInts) {
            left[k] = v
            right[k] = perm[v]
            isBool[k] = false
            k++
        }
        for (v in movedBools) {
            left[k] = v - nInt
            right[k] = perm[v] - nInt
            isBool[k] = true
            k++
        }
        return SymmetryPropagator.Generator(left, right, isBool)
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor {
        // Conjugate each generator by the remap: σ' = m ∘ σ ∘ m⁻¹. A remap that is not injective on a
        // generator's support (variable elimination / column merge) cannot carry the permutation, so
        // that generator is dropped — sound, since dropping a symmetry break only forgoes pruning.
        val remapped = generators.mapNotNull { conjugate(it, boolMap, intMap) }
        if (remapped.isEmpty()) {
            val identity = IntArray(intMap.size) { it } to IntArray(boolMap.size) { it }
            return SymmetryHandling(listOf(identity))
        }
        return SymmetryHandling(remapped)
    }

    private fun conjugate(
        g: Pair<IntArray, IntArray>,
        boolMap: IntArray,
        intMap: IntArray,
    ): Pair<IntArray, IntArray>? {
        val newInt = conjugateOne(g.first, intMap, IntArray(intMap.size) { it }) ?: return null
        val newBool = conjugateOne(g.second, boolMap, IntArray(boolMap.size) { it }) ?: return null
        return newInt to newBool
    }

    /** Conjugate one permutation [perm] by [map] (`out[map[v]] = map[perm[v]]`); `null` if the result
     *  is not a permutation — a remap that merges or sends a moved variable out of range (variable
     *  elimination / column merge) cannot carry the symmetry. [identity] is the new identity array. */
    private fun conjugateOne(perm: IntArray, map: IntArray, identity: IntArray): IntArray? {
        val out = identity
        for (v in perm.indices) {
            if (perm[v] == v) continue
            val nv = map[v]
            val image = map[perm[v]]
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

    // Not migrated to the KeySink allocation-free hash: the key encodes generator permutation arrays,
    // not plain variable references, so the sink's var/const split doesn't model it.
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.SYMMETRY_HANDLING) {
        int(generators.size)
        for (g in generators) {
            ints(g.first)
            ints(g.second)
        }
    }
}
