package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClauseArenaTest {

    private fun cnf(vararg clauses: IntArray): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = clauses.map<IntArray, Factor> { Clause(it) }.toTypedArray(),
    )

    @Test
    fun `pure-Boolean clause-only problem is native-SAT eligible`() {
        assertTrue(cnf(intArrayOf(Lit.make(0, true), Lit.make(1, false))).isNativeSatEligible)
    }

    @Test
    fun `problem with an integer variable is not eligible`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0L, 2L)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)),
        )
        assertFalse(problem.isNativeSatEligible)
    }

    @Test
    fun `problem with a pseudo-Boolean factor is not eligible`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                PseudoBoolean(longArrayOf(1L, 1L), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 1L),
            ),
        )
        assertFalse(problem.isNativeSatEligible)
    }

    @Test
    fun `arena packs every clause contiguously with a trailing sentinel`() {
        val c0 = intArrayOf(Lit.make(0, true), Lit.make(1, false))
        val c1 = intArrayOf(Lit.make(2, true))
        val c2 = intArrayOf(Lit.make(0, false), Lit.make(1, true), Lit.make(2, false))
        val arena = cnf(c0, c1, c2).clauseArena

        assertEquals(3, arena.clauseCount)
        assertEquals(arena.lits.size, arena.end(arena.clauseCount - 1), "sentinel must equal total literals")
    }

    @Test
    fun `each clause is recoverable from the arena in original order`() {
        val c0 = intArrayOf(Lit.make(0, true), Lit.make(1, false))
        val c1 = intArrayOf(Lit.make(2, true))
        val c2 = intArrayOf(Lit.make(0, false), Lit.make(1, true), Lit.make(2, false))
        val arena = cnf(c0, c1, c2).clauseArena

        assertContentEquals(c0, arena.lits.copyOfRange(arena.start(0), arena.end(0)))
        assertContentEquals(c1, arena.lits.copyOfRange(arena.start(1), arena.end(1)))
        assertContentEquals(c2, arena.lits.copyOfRange(arena.start(2), arena.end(2)))
    }

    @Test
    fun `length reflects each clause's literal count`() {
        val arena = cnf(
            intArrayOf(Lit.make(0, true), Lit.make(1, false)),
            intArrayOf(Lit.make(2, true)),
        ).clauseArena
        assertEquals(2, arena.length(0))
        assertEquals(1, arena.length(1))
    }

    @Test
    fun `building an arena from an ineligible problem fails`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0L, 2L)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)),
        )
        assertFailsWith<IllegalArgumentException> { ClauseArena.of(problem) }
    }
}
