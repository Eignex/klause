package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.factor.table.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Per-factor structural reduction ([Presolve.reduceStructural]), exercised through the factors that
 * implement [com.eignex.klause.solver.Factor.structuralReduce]. Each test asserts the global is
 * rewritten into the simpler factor its structure implies, or left untouched when nothing pins it.
 */
class StructuralReductionTest {

    private fun theLinear(problem: Problem): Linear = problem.factors.filterIsInstance<Linear>().single()

    @Test
    fun `a fixed index into a constant array becomes a result equality`() {
        // idx = 2 (offset 1) selects arr[1] = 20, so result = 20.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(2, 2), IntDomain(0, 100)),
            listOf(Element(idx = 0, result = 1, arr = intArrayOf(10, 20, 30), arrIsVars = false)),
        )
        val out = Presolve.reduceStructural(problem)
        assertTrue(out.factors.none { it is Element }, "the element global is removed")
        val eq = theLinear(out)
        assertEquals(LinearOp.EQ, eq.op)
        assertEquals(listOf(1), eq.vars.toList())
        assertEquals(20, eq.bound)
    }

    @Test
    fun `a fixed index into a variable array becomes an equality between result and the selected var`() {
        // idx = 1 (offset 1) selects arr[0] = var 2, so result (var 1) = var 2.
        val problem = Problem(
            0,
            5,
            Array(5) { IntDomain(0, 9) }.also { it[0] = IntDomain(1, 1) },
            listOf(Element(idx = 0, result = 1, arr = intArrayOf(2, 3, 4), arrIsVars = true)),
        )
        val out = Presolve.reduceStructural(problem)
        assertTrue(out.factors.none { it is Element }, "the element global is removed")
        val eq = theLinear(out)
        assertEquals(LinearOp.EQ, eq.op)
        assertEquals(0, eq.bound)
        assertEquals(setOf(1, 2), eq.vars.toSet())
    }

    @Test
    fun `a fixed index selecting the result variable itself drops the element`() {
        // arr[0] is var 1 = result, so the constraint is result = result — vacuous.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 9) }.also { it[0] = IntDomain(1, 1) },
            listOf(Element(idx = 0, result = 1, arr = intArrayOf(1, 2), arrIsVars = true)),
        )
        val out = Presolve.reduceStructural(problem)
        assertTrue(out.factors.isEmpty(), "the vacuous element drops with no replacement")
    }

    @Test
    fun `a constant array of one value fixes the result and tightens the index range`() {
        // Every entry is 7, so result = 7; dropping the element keeps idx in its valid range [1, 3].
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5), IntDomain(0, 10)),
            listOf(Element(idx = 0, result = 1, arr = intArrayOf(7, 7, 7), arrIsVars = false)),
        )
        val out = Presolve.reduceStructural(problem)
        assertTrue(out.factors.none { it is Element }, "the element global is removed")
        assertEquals(7, theLinear(out).bound)
        assertEquals(1, out.intDomains[0].min, "index lower bound clamped to the array's first position")
        assertEquals(3, out.intDomains[0].max, "index upper bound clamped to the array's last position")
    }

    @Test
    fun `an unconstrained element is left untouched`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(1, 3), IntDomain(0, 100)),
            listOf(Element(idx = 0, result = 1, arr = intArrayOf(10, 20, 30), arrIsVars = false)),
        )
        assertSame(problem, Presolve.reduceStructural(problem), "no fixed index and a varied array is the no-op signal")
    }

    @Test
    fun `a two-variable all-different becomes a binary disequality`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val out = Presolve.reduceStructural(problem)
        assertTrue(out.factors.none { it is AllDifferent }, "the all-different global is removed")
        val ne = theLinear(out)
        assertEquals(LinearOp.NE, ne.op)
        assertEquals(setOf(0, 1), ne.vars.toSet())
        assertEquals(0, ne.bound)
    }

    @Test
    fun `an all-different over three variables is left as a global`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 4)),
        )
        assertSame(problem, Presolve.reduceStructural(problem), "a 3-var all-different keeps its global form")
    }

    @Test
    fun `a vacuous cardinality drops`() {
        // 0 <= (#true of three literals) <= 3 accepts every assignment.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), min = 0, max = 3)),
        )
        assertTrue(Presolve.reduceStructural(problem).factors.isEmpty(), "the vacuous cardinality drops")
    }

    @Test
    fun `a binding cardinality is left untouched`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), min = 0, max = 1)),
        )
        assertSame(problem, Presolve.reduceStructural(problem), "an at-most-one still constrains, so it stays")
    }
}
