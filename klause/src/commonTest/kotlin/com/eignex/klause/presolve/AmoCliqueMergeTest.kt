package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * At-most-one clique merging. The pass is solution-set preserving, so each test enumerates all Boolean
 * assignments and asserts the feasible count is unchanged, alongside the expected factor rewrite.
 */
class AmoCliqueMergeTest {

    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)
    private fun clause(vararg lits: Int) = Clause(lits)
    private fun atMostOne(vararg lits: Int) = Cardinality(lits, min = 0, max = 1)
    private fun exactlyOne(vararg lits: Int) = Cardinality(lits, min = 1, max = 1)

    private fun satisfies(f: Factor, a: BooleanArray): Boolean = when (f) {
        is Clause -> f.literals.any { Lit.evaluate(it, a[Lit.variable(it)]) }

        is Cardinality -> {
            val t = f.literals.count { Lit.evaluate(it, a[Lit.variable(it)]) }
            t in f.min..f.max
        }

        else -> true
    }

    private fun feasibleCount(factors: Array<Factor>, numBool: Int): Int {
        var count = 0
        for (mask in 0 until (1 shl numBool)) {
            val a = BooleanArray(numBool) { (mask shr it) and 1 == 1 }
            if (factors.all { satisfies(it, a) }) count++
        }
        return count
    }

    private fun checkMerge(numBool: Int, factors: List<Factor>): Problem {
        val problem = Problem(numBool, 0, emptyArray(), factors)
        val delta = Presolve.mergeAmoCliques(problem)
        val reduced = problem.withPassDelta(delta, BakeConfig.NONE)
        assertEquals(
            feasibleCount(problem.factors, numBool),
            feasibleCount(reduced.factors, numBool),
            "clique merge changed the feasible set",
        )
        return reduced
    }

    private fun clauses(p: Problem) = p.factors.filterIsInstance<Clause>().size
    private fun cardinalities(p: Problem) = p.factors.filterIsInstance<Cardinality>().size

    @Test
    fun `merges a triangle of binary clauses into one at-most-one`() {
        // (a∨b) ∧ (a∨c) ∧ (b∨c) ≡ at-most-one of {¬a,¬b,¬c}: three binary clauses collapse to one.
        val reduced = checkMerge(3, listOf(clause(pos(0), pos(1)), clause(pos(0), pos(2)), clause(pos(1), pos(2))))
        assertEquals(0, clauses(reduced), "the binary clauses are removed")
        assertEquals(1, cardinalities(reduced), "one at-most-one replaces them")
    }

    @Test
    fun `drops a binary clause subsumed by an existing larger at-most-one`() {
        // The at-most-one over {¬a,¬b,¬c} already implies (a∨b); the binary clause drops, the AMO stays.
        val reduced = checkMerge(
            3,
            listOf(atMostOne(neg(0), neg(1), neg(2)), clause(pos(0), pos(1))),
        )
        assertEquals(0, clauses(reduced), "the subsumed binary clause is removed")
        assertEquals(1, cardinalities(reduced), "the existing at-most-one is kept, not duplicated")
    }

    @Test
    fun `keeps an exactly-one but still folds the binary clauses it dominates`() {
        // exactlyOne(a,b,c) seeds the clique {a,b,c}; the two binary clauses fold into an at-most-one,
        // and the exactly-one (its lower bound not implied) is retained.
        val reduced = checkMerge(
            3,
            listOf(exactlyOne(pos(0), pos(1), pos(2)), clause(neg(0), neg(1)), clause(neg(0), neg(2))),
        )
        assertEquals(0, clauses(reduced), "the dominated binary clauses are removed")
        assertTrue(
            reduced.factors.filterIsInstance<Cardinality>().any { it.min == 1 },
            "the exactly-one survives",
        )
    }

    @Test
    fun `folds an at-most-one clique plus a matching clause into an exactly-one`() {
        // The triangle gives at-most-one of {¬a,¬b,¬c}; the clause (¬a∨¬b∨¬c) is at-least-one over the
        // same literals. Together they are exactly-one, replacing all four constraints.
        val reduced = checkMerge(
            3,
            listOf(
                clause(pos(0), pos(1)),
                clause(pos(0), pos(2)),
                clause(pos(1), pos(2)),
                clause(neg(0), neg(1), neg(2)),
            ),
        )
        assertEquals(0, clauses(reduced), "the clauses fold away")
        val cards = reduced.factors.filterIsInstance<Cardinality>()
        assertEquals(1, cards.size)
        assertEquals(1, cards[0].min, "an exactly-one is materialised")
        assertEquals(1, cards[0].max)
    }

    @Test
    fun `is a no-op when no clique reaches size three`() {
        // A single binary clause is a size-2 clique; nothing to merge.
        val reduced = checkMerge(2, listOf(clause(pos(0), pos(1))))
        assertEquals(1, clauses(reduced))
        assertEquals(0, cardinalities(reduced))
    }

    @Test
    fun `preserves the feasible set on a mixed formula`() {
        checkMerge(
            4,
            listOf(
                clause(pos(0), pos(1)),
                clause(pos(0), pos(2)),
                clause(pos(1), pos(2)),
                clause(neg(2), pos(3)),
                atMostOne(pos(0), pos(3)),
            ),
        )
    }
}
