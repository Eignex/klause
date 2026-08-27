package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BakedProblemTest {

    @Test
    fun `bake folds the root deductions into the domains`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf(Linear(intArrayOf(0), intArrayOf(1), LinearOp.LE, 3)),
        )

        assertEquals(3, problem.bake().requireFiniteIntDomains()[0].max, "bake carries the x <= 3 tightening")
    }

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
