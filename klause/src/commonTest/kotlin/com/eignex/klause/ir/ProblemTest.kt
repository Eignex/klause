package com.eignex.klause.ir

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProblemTest {

    @Test
    fun `bake should fold root deductions while the raw problem stays unbaked`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )
        assertEquals(10, problem.finiteIntDomain(0).max)
        assertEquals(3, problem.bake().rootIntDomain(0).max)
    }

    @Test
    fun `problem should reject a factor with an invalid Boolean variable`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Problem(
                numBoolVars = 1,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf(Clause(intArrayOf(Lit.make(1, true)))),
            )
        }

        assertTrue(error.message!!.contains("factor 0 references Boolean variable 1"))
    }

    @Test
    fun `problem should reject a factor with an invalid integer variable`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Problem(
                numBoolVars = 0,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 1)),
                factors = arrayOf(Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 0)),
            )
        }

        assertTrue(error.message!!.contains("factor 0 references integer variable 1"))
    }

    @Test
    fun `model bounds should keep an open side behind the search clamp`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(-8, 8)),
            factors = emptyArray(),
            openIntLo = booleanArrayOf(true),
        )

        assertFalse(problem.intBounds.hasLower(0))
        assertTrue(problem.intBounds.hasUpper(0))
        assertEquals(8, problem.intBounds.upper(0))
    }

    @Test
    fun `problem should keep open bounds without a search domain`() {
        val openUpper = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(3), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        assertEquals(null, problem.intDomainOrNull(0))
        assertFalse(problem.intBounds.hasUpper(0))
    }

    @Test
    fun `withFactors should keep a non-contiguous domain`() {
        val holey = IntDomain(0, 6).excludeValue(3)
        val spec = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(holey),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5)),
        )

        val rewritten = spec.withFactors(emptyArray())

        assertEquals(holey, rewritten.intDomainOrNull(0))
        assertEquals(0, rewritten.numFactors)
    }
}
