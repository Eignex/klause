package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ClauseArenaTest {

    private fun cnf(vararg clauses: IntArray): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = clauses.map<IntArray, Factor> { Clause(it) }.toTypedArray(),
    )

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
