package com.eignex.klause.util

/**
 * Permutations over `[0, n)` and the group they generate, for symmetry handling. A permutation is an
 * `IntArray` of length `n` mapping each point to its image; composition is `(a ∘ b)[i] = a[b[i]]`.
 *
 * The whole symmetry group is far too large to enumerate, but a few extra *genuine* group elements
 * beyond the raw generators sharpen lex-leader propagation. [strongGenerators] returns the input
 * generators plus the Schreier generators of the pointwise-stabiliser chain (Schreier's lemma) along
 * a base — every element a real product of generators, so every lex-leader posted from one stays
 * sound. The set is deduplicated and bounded by a cap so per-node propagation cost stays controlled.
 */
object PermutationGroup {

    /** The orbit of [point] under [generators] (the points reachable by repeated application). */
    fun orbit(generators: List<IntArray>, point: Int): Set<Int> {
        val seen = HashSet<Int>()
        val frontier = ArrayDeque<Int>()
        seen.add(point)
        frontier.addLast(point)
        while (frontier.isNotEmpty()) {
            val p = frontier.removeFirst()
            for (g in generators) {
                val q = g[p]
                if (seen.add(q)) frontier.addLast(q)
            }
        }
        return seen
    }

    /**
     * The orbit of [point] under the pointwise stabiliser of [prefix] in `<generators>`: peel each
     * prefix point off the generating set via Schreier's lemma (bounding the carried set at [cap]),
     * then take the orbit of [point] under the resulting stabiliser. Every element used is a product
     * of [generators], so an ordering `x_point ≤ x_j` derived for `j` in this orbit is sound (it
     * follows from the lex-leader of a group element that fixes the prefix positions). [n] is the
     * permutation degree.
     */
    fun orbitUnderStabilizer(
        generators: List<IntArray>,
        prefix: IntArray,
        point: Int,
        n: Int,
        cap: Int = 256,
    ): Set<Int> {
        var h: List<IntArray> = dedup(generators)
        for (p in prefix) {
            if (h.isEmpty()) break
            h = dedup(schreierGenerators(h, p, n))
            if (h.size > cap) h = h.subList(0, cap)
        }
        return orbit(h, point)
    }

    /**
     * A strong-ish generating set: the [generators] plus the Schreier generators of the stabiliser
     * chain over [base] (default `0,1,…,n-1`), deduplicated (identity dropped) and capped at [cap].
     * Each Schreier generator is a product of [generators] (Schreier's lemma), so it is a real group
     * element. Stops early once [cap] elements are collected or the chain is exhausted.
     */
    fun strongGenerators(
        generators: List<IntArray>,
        n: Int,
        cap: Int,
        base: IntArray = IntArray(
            n,
        ) { it },
    ): List<IntArray> {
        if (generators.isEmpty()) return generators
        val out = ArrayList<IntArray>()
        val seen = HashSet<String>()
        fun offer(p: IntArray): Boolean {
            if (isIdentity(p)) return false
            if (!seen.add(key(p))) return false
            out.add(p)
            return out.size < cap
        }
        for (g in generators) if (!offer(g)) return out
        // Peel the base: at each level, add the Schreier generators of the current group's stabiliser
        // of the base point, then descend into that stabiliser. Capped depth and width keep it bounded.
        var current: List<IntArray> = dedup(generators)
        for (b in base) {
            if (out.size >= cap || current.isEmpty()) break
            val schreier = dedup(schreierGenerators(current, b, n))
            for (s in schreier) if (!offer(s)) return out
            current = if (schreier.size > cap) schreier.subList(0, cap) else schreier
        }
        return out
    }

    /** Schreier generators of the stabiliser of [point] in `<gens>` (Schreier's lemma): for a
     *  transversal `u` of the orbit of [point], `u(s·γ)⁻¹ ∘ s ∘ u(γ)` over every generator `s` and
     *  orbit point `γ`. Each fixes [point] and lies in `<gens>`. */
    private fun schreierGenerators(gens: List<IntArray>, point: Int, n: Int): List<IntArray> {
        val transversal = MutableIntObjectMap<IntArray>()
        transversal.put(point, IntArray(n) { it })
        val frontier = ArrayDeque<Int>()
        frontier.addLast(point)
        while (frontier.isNotEmpty()) {
            val gamma = frontier.removeFirst()
            val ug = transversal.getValue(gamma)
            for (s in gens) {
                val image = s[gamma]
                if (!transversal.containsKey(image)) {
                    transversal.put(image, compose(s, ug))
                    frontier.addLast(image)
                }
            }
        }
        val result = ArrayList<IntArray>()
        val orbit = ArrayList<Int>(transversal.size)
        transversal.forEach { gamma, _ -> orbit.add(gamma) }
        for (gamma in orbit) {
            val ug = transversal.getValue(gamma)
            for (s in gens) {
                val sug = compose(s, ug)
                val usg = transversal.getValue(s[gamma])
                result.add(compose(inverse(usg), sug))
            }
        }
        return result
    }

    private fun dedup(list: List<IntArray>): ArrayList<IntArray> {
        val seen = HashSet<String>()
        val out = ArrayList<IntArray>()
        for (p in list) if (!isIdentity(p) && seen.add(key(p))) out.add(p)
        return out
    }

    private fun compose(a: IntArray, b: IntArray): IntArray = IntArray(a.size) { a[b[it]] }

    private fun inverse(p: IntArray): IntArray {
        val out = IntArray(p.size)
        for (i in p.indices) out[p[i]] = i
        return out
    }

    private fun isIdentity(p: IntArray): Boolean {
        for (i in p.indices) if (p[i] != i) return false
        return true
    }

    private fun key(p: IntArray): String = p.joinToString(",")
}
