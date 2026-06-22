package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.factor.bool.Clause

/** Small math and problem-rebuild helpers shared across the presolve passes. */
internal object PresolveShared {

    /** At-most-one cliques (each a set of Lit-encoded literals, at most one satisfied) recognised
     *  soundly: a [Cardinality] `0 ≤ Σ lit ≤ 1`, and any binary [Clause] `(l1 ∨ l2)` ⟺ at most one of
     *  `{¬l1, ¬l2}`. */
    fun amoCliques(factors: List<Factor>): List<Set<Int>> {
        val cliques = ArrayList<Set<Int>>()
        for (f in factors) {
            when {
                f is Cardinality && f.min == 0 && f.max == 1 -> cliques.add(f.literals.toHashSet())

                f is Clause && f.literals.size == 2 ->
                    cliques.add(hashSetOf(Lit.negate(f.literals[0]), Lit.negate(f.literals[1])))
            }
        }
        return cliques
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
