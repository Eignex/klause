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
    fun `clauses should be kept when cancellation follows clique analysis`() {
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
    fun `a triangle of binary clauses should merge into one at-most-one`() {
        val reduced = checkMerge(3, listOf(clause(pos(0), pos(1)), clause(pos(0), pos(2)), clause(pos(1), pos(2))))
        assertEquals(0, clauses(reduced))
        assertEquals(1, cardinalities(reduced))
    }

    @Test
    fun `an at-most-one clique plus a matching clause should fold into an exactly-one`() {
        val reduced = checkMerge(
            3,
            listOf(
                clause(pos(0), pos(1)),
                clause(pos(0), pos(2)),
                clause(pos(1), pos(2)),
                clause(neg(0), neg(1), neg(2)),
            ),
        )
        assertEquals(0, clauses(reduced))
        val cards = reduced.factors.filterIsInstance<Cardinality>()
        assertEquals(1, cards.size)
        assertEquals(1, cards[0].min)
        assertEquals(1, cards[0].max)
    }

    @Test
    fun `clique merging should preserve the feasible set on a mixed formula`() {
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
