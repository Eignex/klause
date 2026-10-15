package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExactRelaxationRowsTest {
    @Test
    fun `a wide mixed row numerically certifies its original source equality`() {
        val tiny = BigFraction.of(BIG_ONE, parseBigInt("100000000000000000000"))
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
            listOf(BigFraction.ONE, tiny), LinearOp.EQ, BigFraction.ONE + tiny)
        val problem = Problem(0, 1, arrayOf(IntDomain(1, 1)), arrayOf<Factor>(row),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val relaxation = CpToLpRelaxation(problem, null).build(RootDomains(problem))

        val result = solveAndCertify(relaxation.model)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        val point = assertNotNull(result.exactPrimal)
        val column = relaxation.colRealId.indexOf(0)
        assertEquals(BigFraction.ONE, point[column])
    }

    @Test
    fun `wide indicator activation retains both pin premises and retracts its row`() {
        val tiny = BigFraction.of(BIG_ONE, parseBigInt("100000000000000000000"))
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
            listOf(BigFraction.ONE, tiny), LinearOp.GE, BigFraction.ONE)
        val factor = LinearIndicator(1, true, row)
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)), arrayOf<Factor>(factor),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val relaxer = CpToLpRelaxation(problem, null)
        val pinned = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = if (varId == 1) IntDomain(1, 1) else IntDomain(0, 1)
            override fun boolValue(varId: Int): Boolean? = null
        }

        val active = relaxer.build(pinned)
        val retracted = relaxer.build(RootDomains(problem))

        val exact = assertNotNull(active.model.authoritativeModel())
        assertEquals(1, exact.m)
        assertFalse(exact.row(0).global)
        val premises = assertNotNull(exact.row(0).premises)
        assertEquals(listOf(false, true), premises.boundEntries().map { it.upper })
        assertTrue(premises.boundEntries().all { it.variable == 1 && it.threshold.value == BigFraction.ONE })
        assertEquals(0, retracted.model.m)
    }

    @Test
    fun `split free real columns preserve opposite exact wide coefficients`() {
        val tiny = BigFraction.of(BIG_ONE, parseBigInt("100000000000000000000"))
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
            listOf(BigFraction.ONE, tiny), LinearOp.LE, BigFraction.ONE)
        val problem = Problem(0, 1, arrayOf(IntDomain(-2, 2)), arrayOf<Factor>(row),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))

        val relaxation = CpToLpRelaxation(problem, null).build(RootDomains(problem))

        val exact = assertNotNull(relaxation.model.authoritativeModel())
        val reals = relaxation.colRealId.indices.filter { relaxation.colRealId[it] == 0 }
        assertEquals(2, reals.size)
        assertTrue(reals.map { exact.entries(it).single().number.value }.containsAll(listOf(tiny, tiny.negated())))
        assertEquals(BigFraction.ofLong(3), exact.rhs(0).value)
    }
}
