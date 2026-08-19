package com.eignex.klause.cli

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundExactResidualTest {

    @Test
    fun `the residual keeps only the factors no clamped side reaches`() {
        val overClamped = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)
        val overExact = Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 3)
        val residual = boundExactResidual(problem(listOf(overClamped, overExact)))

        assertEquals(listOf<Factor>(overExact), residual?.factors?.toList())
    }

    @Test
    fun `the residual narrows no domain, including the clamped ones`() {
        val factors = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 3),
        )
        val residual = boundExactResidual(problem(factors))

        assertEquals(IntDomain(0L, 1L shl 62), residual?.intDomains?.get(0))
        assertEquals(IntDomain(0L, 3L), residual?.intDomains?.get(1))
    }

    @Test
    fun `a model whose every factor reaches a clamped side has no residual`() {
        assertNull(boundExactResidual(problem(listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)))))
    }

    @Test
    fun `a refutation among bound-exact factors holds without the box`() {
        val factors = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            Clause(intArrayOf(Lit.make(0, true))),
            Clause(intArrayOf(Lit.make(0, false))),
        )

        assertTrue(refutationIsBoxFree(problem(factors), Cancellation.Never))
    }

    @Test
    fun `a refutation whose bound-exact part is satisfiable stays uncertified`() {
        val factors = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
        )

        assertFalse(refutationIsBoxFree(problem(factors), Cancellation.Never))
    }

    @Test
    fun `a model with no clamped side offers no certificate`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0L, 3L) },
            factors = listOf(Clause(intArrayOf(Lit.make(0, true))), Clause(intArrayOf(Lit.make(0, false)))),
        )

        assertNull(boundExactResidual(problem))
        assertFalse(refutationIsBoxFree(problem, Cancellation.Never))
    }

    /** Two integer variables, the first clamped open above (the invented box), the second bound-exact. */
    private fun problem(factors: List<Factor>): Problem = Problem(
        numBoolVars = 2,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0L, 1L shl 62), IntDomain(0L, 3L)),
        factors = factors,
        openIntHi = booleanArrayOf(true, false),
    )
}
