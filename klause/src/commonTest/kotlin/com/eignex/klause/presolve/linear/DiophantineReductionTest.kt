package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagatorProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiophantineReductionTest {

    @Test
    fun `an implied equality carves off-residue values without replacing its factor`() {
        val source = Linear(longArrayOf(2, 3, 3), intArrayOf(0, 1, 2), LinearOp.EQ, 10)
        val factor = object : Factor by source, Propagator by source.propagatorProjection() {
            override val linearForm: LinearForm = LinearForm.Relaxation(source.linearRows)
        }
        val model = problem(arrayOf(IntDomain(0, 5), IntDomain(0, 2), IntDomain(0, 2)), factor)

        val delta = DiophantineReduction.reduce(model)

        val domain = assertNotNull(delta.domains)[0]
        assertTrue(3L !in domain && 4L !in domain)
        assertTrue(2L in domain && 5L in domain)
        assertTrue(delta.droppedIndices.isEmpty())
    }

    private fun problem(domains: Array<IntDomain>, vararg factors: Factor) =
        Problem(numBoolVars = 0, numIntVars = domains.size, intDomains = domains, factors = factors.toList()).bake()

    /** `2x + 3y + 3z = 10` over declared ranges, with `x` open above when [openX] is set. */
    private fun sourceProblem(xHi: Long, openX: Boolean = false) = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, xHi), IntDomain(0, 2), IntDomain(0, 2)),
        factors = listOf(Linear(longArrayOf(2, 3, 3), intArrayOf(0, 1, 2), LinearOp.EQ, 10)),
        openIntHi = booleanArrayOf(openX, false, false),
    )

    @Test
    fun `the source form moves each closed side to the nearest in-class value`() {
        // x ≡ 2 (mod 3), so the declared 0..7 narrows to 2..5.
        val bounds = assertNotNull(DiophantineReduction.reduceSource(sourceProblem(xHi = 7)).bounds)

        assertEquals(2, bounds.lower(0))
        assertEquals(5, bounds.upper(0))
    }

    @Test
    fun `the source form leaves an open side open`() {
        val bounds = assertNotNull(DiophantineReduction.reduceSource(sourceProblem(xHi = 0, openX = true)).bounds)

        assertEquals(2, bounds.lower(0))
        assertTrue(bounds.isOpenUpper(0))
    }

    @Test
    fun `the source form refutes a congruence with no solution`() {
        // 2x + 4y = 3: 2x ≡ 3 (mod 4) has no solution.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = listOf(Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 3)),
        )

        assertTrue(DiophantineReduction.reduceSource(p).infeasible)
    }

}
