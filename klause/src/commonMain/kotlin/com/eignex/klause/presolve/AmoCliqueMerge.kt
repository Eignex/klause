package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.internals.maximalPersistentAmoCliques
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.MutableIntObjectMap

// Materialises each maximal at-most-one clique of the conflict graph (binary clauses and
// `Cardinality(max = 1)` exclusions) as one `Cardinality(clique, 0, 1)` and drops the pure at-most-ones it
// strictly subsumes, collapsing `O(k²)` binary clauses into one `O(k)` constraint.
// Solution-set preserving: every pair in a maximal clique already had an exclusion edge, so the new
// at-most-one adds no exclusion and eliminates no variable. A `Cardinality` with a positive lower bound is
// never dropped, since the at-most-one does not imply it. A new at-most-one is emitted only when at least
// two constraints drop, so the factor count never grows.
internal object AmoCliqueMerge {

    // Size 2 is already a single binary clause.
    private const val MIN_CLIQUE_SIZE = 3

    fun mergeAmoCliques(problem: Problem, cancellation: ExecutionControl = ExecutionControl.Never): PassDelta {
        val factors = problem.factors
        val cliques = maximalPersistentAmoCliques(factors.asList(), cancellation)
            .filter { it.size >= MIN_CLIQUE_SIZE }
        if (cliques.isEmpty() || cancellation()) return PassDelta()

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
            if ((i and 0x3F) == 0 && cancellation()) return PassDelta()
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
            if ((ci and 0x3F) == 0 && cancellation()) return PassDelta()
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

        if (cancellation() || (dropped.isEmpty() && added.isEmpty())) return PassDelta()
        return PassDelta(droppedIndices = dropped.toIntArray(), addedFactors = added)
    }

    // The literal set [f] excludes pairwise when [f] is a pure at-most-one, else null: a `Cardinality` with a
    // positive lower bound is excluded because the at-most-one would not preserve that bound.
    private fun footprint(f: Factor): Set<Int>? = when {
        f is Clause && f.literals.size == 2 -> hashSetOf(Lit.negate(f.literals[0]), Lit.negate(f.literals[1]))
        f is Cardinality && f.min == 0 && f.max == 1 -> f.literals.toHashSet()
        else -> null
    }
}
