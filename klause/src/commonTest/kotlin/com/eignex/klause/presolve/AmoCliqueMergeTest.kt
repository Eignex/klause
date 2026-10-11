package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmoCliqueMergeTest {

    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)
    private fun clause(vararg lits: Int) = Clause(lits)
    private fun atMostOne(vararg lits: Int) = Cardinality(lits, min = 0, max = 1)

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
        val problem = Problem(numBool, 0, emptyArray(), factors).bake()
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
    fun `keeps clauses when cancellation follows clique analysis`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(clause(pos(0), pos(1)), clause(pos(0), pos(2)), clause(pos(1), pos(2))),
        )
        var polls = 0

        val delta = AmoCliqueMerge.mergeAmoCliques(problem, Cancellation { ++polls > 1 })

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `merges a triangle of binary clauses into one at-most-one`() {
        // (a∨b) ∧ (a∨c) ∧ (b∨c) ≡ at-most-one of {¬a,¬b,¬c}: three binary clauses collapse to one.
        val reduced = checkMerge(3, listOf(clause(pos(0), pos(1)), clause(pos(0), pos(2)), clause(pos(1), pos(2))))
        assertEquals(0, clauses(reduced), "the binary clauses are removed")
        assertEquals(1, cardinalities(reduced), "one at-most-one replaces them")
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
