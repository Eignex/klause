package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntObjectMap

/**
 * At-most-one clique merging. The scattered
 * pairwise exclusion constraints of a problem — a binary clause `(a ∨ b)` excludes the literal pair
 * `{¬a, ¬b}`, a `Cardinality(max = 1)` excludes every pair of its literals — form a conflict graph;
 * [PresolveShared.maximalPersistentAmoCliques] grows the base cliques into maximal ones. This pass
 * *materialises* each maximal clique as one `Cardinality(clique, 0, 1)` and drops the smaller
 * constraints it subsumes, collapsing `O(k²)` binary clauses into a single `O(k)` at-most-one.
 *
 * Solution-set preserving: every pair inside a maximal clique already had an exclusion edge from some
 * source constraint, so the materialised at-most-one is logically equivalent to the conjunction of the
 * pairwise exclusions it replaces — it adds no new exclusion, and no variable is eliminated (no
 * reconstruction needed).
 *
 * Only strictly-subsumed **pure** at-most-ones are dropped: a binary clause, or a `Cardinality(S, 0, 1)`
 * whose set `S` is a proper subset of a clique. A `Cardinality` with a positive lower bound (an
 * exactly/at-least-one) is never dropped — its lower bound is not implied by the at-most-one — though
 * its exclusion edges may still have seeded a clique. A new at-most-one is emitted only when it lets at
 * least two constraints drop, so the factor count never grows.
 */
internal object AmoCliqueMerge {

    /** A clique of at least this size is worth materialising; size 2 is already a single binary clause. */
    private const val MIN_CLIQUE_SIZE = 3

    fun mergeAmoCliques(problem: Problem, cancellation: Cancellation = Cancellation.Never): PassDelta {
        val factors = problem.factors
        val cliques = PresolveShared.maximalPersistentAmoCliques(factors.asList(), cancellation)
            .filter { it.size >= MIN_CLIQUE_SIZE }
        if (cliques.isEmpty()) return PassDelta()

        // Index cliques by member literal so each factor is matched only against the cliques that could
        // contain its footprint, rather than all of them.
        val litToCliques = MutableIntObjectMap<IntArrayList>()
        for (ci in cliques.indices) for (l in cliques[ci]) litToCliques.getOrPut(l) { IntArrayList() }.add(ci)

        // A clause whose literal set equals a clique is that clique's at-least-one; combined with the
        // clique's at-most-one it forms an exactly-one. Indexed by literal set for O(1) lookup per clique.
        val atLeastOne = HashMap<Set<Int>, Int>()
        // Per clique: the factor index that already materialises it as a pure at-most-one (identical
        // literal set), or -1; and the strictly-smaller pure at-most-ones it subsumes (candidates to drop).
        val materialisingAmo = IntArray(cliques.size) { -1 }
        val subsumed = Array(cliques.size) { IntArrayList() }
        for (i in factors.indices) {
            val f = factors[i]
            if (f is Clause && f.literals.size >= MIN_CLIQUE_SIZE) atLeastOne.getOrPut(f.literals.toHashSet()) { i }
            val fp = footprint(f) ?: continue
            val cand = litToCliques[fp.first()] ?: continue
            for (k in 0 until cand.size) {
                val ci = cand[k]
                val c = cliques[ci]
                if (!c.containsAll(fp)) continue
                if (fp.size == c.size) materialisingAmo[ci] = i else subsumed[ci].add(i)
            }
        }

        val dropped = IntHashSet()
        val added = ArrayList<Factor>()
        for (ci in cliques.indices) {
            val c = cliques[ci]
            val amo = materialisingAmo[ci]
            val subs = subsumed[ci]
            val alo = atLeastOne[c] ?: -1
            val lits = c.toIntArray().also { it.sort() } // deterministic literal order
            when {
                // At-most-one + at-least-one over the same literals ⇒ exactly-one. Fold both (and any
                // subsumed at-most-ones) into a single Cardinality(1, 1). Requires an at-most-one to
                // exist — materialised, or one we would otherwise emit (≥ 2 subsumed).
                alo >= 0 && (amo >= 0 || subs.size >= 2) -> {
                    dropped.add(alo)
                    if (amo >= 0) dropped.add(amo)
                    for (k in 0 until subs.size) dropped.add(subs[k])
                    added.add(Cardinality(lits, min = 1, max = 1))
                }

                // The clique already exists as an at-most-one: keep it, drop only the strictly-smaller subsumed.
                amo >= 0 -> for (k in 0 until subs.size) dropped.add(subs[k])

                // Emit the at-most-one only when it collapses ≥ 2 constraints (net factor reduction).
                subs.size >= 2 -> {
                    for (k in 0 until subs.size) dropped.add(subs[k])
                    added.add(Cardinality(lits, min = 0, max = 1))
                }
            }
        }

        if (dropped.isEmpty() && added.isEmpty()) return PassDelta()
        return PassDelta(droppedIndices = dropped.toIntArray(), addedFactors = added)
    }

    /**
     * The literal set [f] excludes pairwise, when [f] is a *pure* at-most-one a larger clique can
     * subsume — a two-literal clause `(a ∨ b)` → `{¬a, ¬b}`, or a `Cardinality(S, 0, 1)` → `S`. `null`
     * for anything else (including a `Cardinality` with a positive lower bound, whose lower bound the
     * at-most-one would not preserve).
     */
    private fun footprint(f: Factor): Set<Int>? = when {
        f is Clause && f.literals.size == 2 -> hashSetOf(Lit.negate(f.literals[0]), Lit.negate(f.literals[1]))
        f is Cardinality && f.min == 0 && f.max == 1 -> f.literals.toHashSet()
        else -> null
    }
}
