package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.StructuralKey

/** Small math and problem-rebuild helpers shared across the presolve passes. */
internal object PresolveShared {

    /** At-most-one cliques (each a set of Lit-encoded literals, at most one satisfied) recognised
     *  soundly from a single factor:
     *   - a [Cardinality] `Σ lit ≤ max` with `max == 1` (covers both at-most-one and exactly-one);
     *   - any binary [Clause] `(l1 ∨ l2)` ⟺ at most one of `{¬l1, ¬l2}`;
     *   - a positive-weight [PseudoBoolean] knapsack `Σ wⱼ lⱼ ≤ b` (or `= b`): the largest-weight
     *     literals whose two smallest weights already exceed `b` are pairwise exclusive (see
     *     [pbKnapsackClique]).
     *
     *  These are the *base* cliques; [maximalAmoCliques] grows them across factors into larger ones. */
    fun amoCliques(factors: List<Factor>): List<Set<Int>> = collectCliques(factors, includeKnapsacks = true)

    /** [amoCliques] restricted to cliques backed by a [Cardinality] or [Clause] — factors a knapsack
     *  drop never removes. A clique implied by a [PseudoBoolean] knapsack only holds while that knapsack
     *  is present, so it is unsound for [RedundantConstraints] to use one to *drop* a knapsack (it could
     *  drop the very constraint the clique rests on); this restricted set is the one safe to drop by. */
    fun persistentAmoCliques(factors: List<Factor>): List<Set<Int>> = collectCliques(factors, includeKnapsacks = false)

    private fun collectCliques(factors: List<Factor>, includeKnapsacks: Boolean): List<Set<Int>> {
        val cliques = ArrayList<Set<Int>>()
        for (f in factors) {
            when {
                f is Cardinality && f.max == 1 -> cliques.add(f.literals.toHashSet())

                f is Clause && f.literals.size == 2 ->
                    cliques.add(hashSetOf(Lit.negate(f.literals[0]), Lit.negate(f.literals[1])))

                includeKnapsacks && f is PseudoBoolean -> pbKnapsackClique(f)?.let { cliques.add(it) }
            }
        }
        return cliques
    }

    /** The at-most-one clique implied by a positive-weight `≤`/`=` knapsack `Σ wⱼ lⱼ ⟨op⟩ b`, or
     *  `null` when none of size ≥ 2 exists. Two literals `i, j` cannot both hold when `wᵢ + wⱼ > b`;
     *  taking the literals in *descending* weight, the largest prefix whose two smallest members still
     *  sum past `b` is pairwise exclusive — an at-most-one clique. Only `≤` and `=` bound the activity
     *  from above (a lone `≥` does not); negative weights and `b < 0` are skipped (not a clean cover). */
    private fun pbKnapsackClique(pb: PseudoBoolean): Set<Int>? {
        if (pb.op != PbOp.LE && pb.op != PbOp.EQ) return null
        if (pb.literals.size < 2 || pb.bound < 0) return null
        val order = pb.weights.indices.sortedBy { pb.weights[it] } // ascending weight
        for (i in order) if (pb.weights[i] <= 0) return null
        // Ascending weights: the suffix from the first index whose pair-sum with its neighbour exceeds
        // the bound is a clique (its two smallest members already exceed b, so every pair does too).
        val b = pb.bound.toLong()
        for (start in 0 until order.size - 1) {
            if (pb.weights[order[start]].toLong() + pb.weights[order[start + 1]].toLong() > b) {
                val clique = HashSet<Int>(order.size - start)
                for (k in start until order.size) clique.add(pb.literals[order[k]])
                return if (clique.size >= 2) clique else null
            }
        }
        return null
    }

    /** Cap on cliques fed to [mergeCliques]; above it the structural merge is skipped (sound — it only
     *  means fewer / smaller cliques) so the greedy extension can't go quadratic on a huge model. */
    private const val CLIQUE_MERGE_CAP = 4096

    /** The [amoCliques] of [factors], grown into maximal cliques by [mergeCliques]. */
    fun maximalAmoCliques(factors: List<Factor>): List<Set<Int>> = mergeCliques(amoCliques(factors))

    /** The [persistentAmoCliques] of [factors], grown into maximal cliques by [mergeCliques]. */
    fun maximalPersistentAmoCliques(factors: List<Factor>): List<Set<Int>> = mergeCliques(persistentAmoCliques(factors))

    /**
     * Merge a set of at-most-one [cliques] into maximal ones (OR-Tools `TransformIntoMaxCliques`). Every
     * unordered pair *within* a base clique is a sound exclusion edge, so their union is a conflict
     * graph; greedily extending each base clique with literals adjacent to all its members yields larger
     * at-most-one cliques (e.g. three binary clauses forming a triangle collapse to one size-3 clique).
     * Cliques that end up a subset of another are dropped. Deterministic (literals processed in id
     * order) and bounded by [CLIQUE_MERGE_CAP]; sound because a literal joins only when it conflicts with
     * every current member.
     */
    fun mergeCliques(cliques: List<Set<Int>>): List<Set<Int>> {
        if (cliques.size < 2 || cliques.size > CLIQUE_MERGE_CAP) return cliques
        // Conflict graph: lit -> the lits known pairwise-exclusive with it (co-members of some clique).
        val adj = HashMap<Int, HashSet<Int>>()
        for (clique in cliques) {
            for (u in clique) {
                val nbrs = adj.getOrPut(u) { HashSet() }
                for (v in clique) if (v != u) nbrs.add(v)
            }
        }
        val grown = ArrayList<Set<Int>>(cliques.size)
        for (clique in cliques) {
            val members = clique.toHashSet()
            // Candidates: lits adjacent to *every* current member, taken in id order for determinism.
            val candidates = clique.fold(null as HashSet<Int>?) { acc, u ->
                val nbrs = adj[u] ?: hashSetOf()
                if (acc == null) HashSet(nbrs) else acc.apply { retainAll(nbrs) }
            } ?: hashSetOf()
            candidates.removeAll(members)
            for (c in candidates.sorted()) {
                if (members.all { it == c || adj[it]?.contains(c) == true }) members.add(c)
            }
            grown.add(members)
        }
        // Drop any clique that is a subset of a strictly larger kept one; dedup equals.
        val sorted = grown.distinct().sortedByDescending { it.size }
        val kept = ArrayList<Set<Int>>(sorted.size)
        for (c in sorted) if (kept.none { it.size > c.size && it.containsAll(c) }) kept.add(c)
        return kept
    }

    fun rebuildProblem(
        problem: Problem,
        factors: List<Factor>,
        intDomains: Array<IntDomain> = problem.intDomains.copyOf(),
    ): Problem = Problem(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
        intDomains = intDomains,
        factors = factors,
        probeFailedLiterals = problem.probeFailedLiterals,
        probeIntBounds = problem.probeIntBounds,
        probeIntHoles = problem.probeIntHoles,
        probeBudgetPerVar = problem.probeBudgetPerVar,
        probeTotalBudget = problem.probeTotalBudget,
        probeSeed = problem.probeSeed,
    )

    fun gcdOf(xs: IntArray): Int {
        var g = 0
        for (x in xs) g = gcd(g, x)
        return g
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = if (a < 0) -a else a
        var y = if (b < 0) -b else b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    fun divAll(xs: IntArray, g: Int): IntArray = IntArray(xs.size) { xs[it] / g }

    /** Multiset of [Factor.structuralKey] over [factors] — the constraint set keyed for comparison
     *  against a transform of itself. */
    fun structuralKeyMultiset(factors: List<Factor>): Map<StructuralKey, Int> {
        val base = HashMap<StructuralKey, Int>()
        for (f in factors) {
            val key = f.structuralKey()
            base[key] = (base[key] ?: 0) + 1
        }
        return base
    }

    /** Whether applying [transform] to every factor in [factors] reproduces the [base] multiset of
     *  structural keys — i.e. the transform is an automorphism of the constraint set. [transform]
     *  returns `null` for a factor it cannot map (unkeyable / un-remappable), which fails the match.
     *  The `next > base[key]` short-circuit bails as soon as any key over-counts, before reading the
     *  whole factor list. */
    fun matchesMultiset(factors: List<Factor>, base: Map<StructuralKey, Int>, transform: (Factor) -> Factor?): Boolean {
        val counts = HashMap<StructuralKey, Int>(base.size)
        for (f in factors) {
            val key = (transform(f) ?: return false).structuralKey()
            val next = (counts[key] ?: 0) + 1
            if (next > (base[key] ?: 0)) return false // already can't match the multiset
            counts[key] = next
        }
        return counts == base
    }
}
