package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LeafColumnsTest {
    @Test
    fun `unread columns should be the ones no factor declares`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false)))),
        )

        val unread = assertNotNull(unreadColumnsOf(problem))

        assertEquals(listOf(false, false, true), (0 until 3).map(unread::bool))
        assertEquals(listOf(true, true), (0 until 2).map(unread::int))
    }

    @Test
    fun `a model whose every column has a reader should have no unread columns`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
            ),
        )

        assertNull(unreadColumnsOf(problem))
    }

    @Test
    fun `objective terms should keep their columns decided`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = emptyArray<Factor>(),
        )
        val unread = assertNotNull(unreadColumnsOf(problem))

        val kept = assertNotNull(unread.withoutTerms(longArrayOf(0L, 3L), longArrayOf(0L)))

        assertEquals(listOf(true, false), (0 until 2).map(kept::bool))
        assertEquals(true, kept.int(0))
    }
}
