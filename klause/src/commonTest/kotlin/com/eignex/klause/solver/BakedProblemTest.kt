package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.withAppendedFactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BakedProblemTest {
    private fun mixed(): Problem = Problem(
        numBoolVars = 1,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 5)),
        factors = arrayOf(
            ReifiedRealLinear(
                aux = 0,
                vars = intArrayOf(),
                intCoeffs = doubleArrayOf(),
                realVars = intArrayOf(0),
                realCoeffs = doubleArrayOf(1.0),
                op = LinearOp.LE,
                bound = 2.0,
            ),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(0.0),
        realUpper = doubleArrayOf(3.0),
    )

    @Test
    fun `the projection copies the domains it is handed so a later write cannot reach its fold`() {
        val handed = arrayOf(IntDomain(0, 5))
        val baked = BakedProblem(numBoolVars = 0, numIntVars = 1, intDomains = handed, factors = emptyList())

        handed[0] = IntDomain(9, 9)

        assertEquals(IntDomain(0, 5), baked.rootIntDomain(0), "the fold owns an array of its own")
    }

    @Test
    fun `what the fold proves is what the model states back for the column`() {
        val baked = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        ).bake()

        assertEquals(3L, baked.rootIntDomain(0).max, "the bake folds x <= 3 into the root domain")
        assertEquals(
            baked.rootIntDomain(0),
            baked.intDomainOrNull(0),
            "the declared value set is the array the fold writes into, so the two readings cannot diverge",
        )
    }

    @Test
    fun `a declared hole survives into the fold`() {
        val baked = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5).excludeValue(3)),
            factors = emptyArray(),
        ).bake()

        assertFalse(3L in baked.rootIntDomain(0), "the fold must not widen a column back over its hole")
        assertTrue(2L in baked.rootIntDomain(0) && 4L in baked.rootIntDomain(0))
    }

    @Test
    fun `appending a factor keeps the continuous columns its rows read`() {
        val appended = mixed().bake().withAppendedFactor(Clause(intArrayOf(Lit.make(0, true))))

        assertEquals(1, appended.numRealVars, "a rebuild that declares the reals away breaks its own rows")
        assertEquals(0.0, appended.realLower[0])
        assertEquals(3.0, appended.realUpper[0])
    }

    @Test
    fun `appending a factor keeps which integer sides were invented rather than declared`() {
        val open = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            openIntHi = booleanArrayOf(true),
        )

        val appended = open.bake().withAppendedFactor(Clause(intArrayOf(Lit.make(0, true))))

        assertTrue(appended.intBounds.isOpenUpper(0), "an invented endpoint must not read back as declared")
    }
}
