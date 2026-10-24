package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FactorConeProjectionTest {
    @Test
    fun `ordinary and exact integer reifications should stay outside the big M free cone`() {
        val variants = listOf<Factor>(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 5),
            ExactLinear.of(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE), LinearOp.LE,
                BigFraction.ofLong(5), activator = 0),
        )
        for (row in variants) {
            val problem = Problem(1, 1, arrayOf(IntDomain(0, 10)), arrayOf(row))

            val cone = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1)),
                objectiveCone = true).build(RootDomains(problem))

            assertEquals(0, cone.model.m)
        }
    }

    @Test
    fun `ordinary and exact integer rows should preserve the connected objective bound`() {
        val variants = listOf(
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.GE, 5),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
            ),
            arrayOf<Factor>(
                ExactLinear.of(intArrayOf(Term.ofIntVar(1)), listOf(BigFraction.ONE),
                    LinearOp.GE, BigFraction.ofLong(5)),
                ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
                    listOf(BigFraction.ONE, BigFraction.ofLong(-1)), LinearOp.GE, BigFraction.ZERO),
            ),
        )
        for (rows in variants) {
            val problem = Problem(0, 3, Array(3) { IntDomain(0, 10) },
                rows + Linear(intArrayOf(1), intArrayOf(2), LinearOp.GE, 7))
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 0, 0))

            val cone = CpToLpRelaxation(problem, objective, objectiveCone = true).build(RootDomains(problem))
            val result = solveAndCertify(cone.model)

            assertEquals(listOf(0, 1), cone.rowFactorIds.toList())
            assertEquals(-1, cone.intColOf[2])
            assertEquals(CutSource(CutSourceKind.INTEGER, 1),
                assertNotNull(cone.sourceMap).column(cone.intColOf[1])?.source)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertEquals(BigFraction.ofLong(5), result.lowerBound)
        }
    }

    @Test
    fun `real objectives should close through ordinary and exact mixed rows`() {
        val variants = listOf<Factor>(
            Linear(longArrayOf(-1), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 0),
            ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
                listOf(BigFraction.ofLong(-1), BigFraction.ONE), LinearOp.GE, BigFraction.ZERO),
        )
        for (row in variants) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5), row,
            ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
            val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))

            val cone = CpToLpRelaxation(problem, objective, objectiveCone = true).build(RootDomains(problem))
            val result = solveAndCertify(cone.model)

            assertEquals(listOf(0, 1), cone.rowFactorIds.toList())
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertEquals(BigFraction.ofLong(5), result.lowerBound)
        }
    }

    @Test
    fun `conditional real rows should retain pin premises and retract from a Boolean objective cone`() {
        val variants = listOf<Factor>(
            ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                LinearOp.LE, 5.0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.LE,
                BigFraction.ofLong(5), activator = 0),
        )
        for (row in variants) {
            val problem = Problem(1, 0, emptyArray(), arrayOf(row), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(10.0))
            var pin: Boolean? = null
            val domains = object : RelaxationDomains {
                override fun intDomain(varId: Int): IntDomain = error("no integer columns")
                override fun boolValue(varId: Int): Boolean? = pin
            }
            val relaxer = CpToLpRelaxation(problem, LinearObjective(boolWeights = longArrayOf(1)),
                objectiveCone = true)

            for (truth in listOf(true, false)) {
                pin = truth
                val active = relaxer.build(domains)
                val exact = assertNotNull(active.model.authoritativeModel())

                assertEquals(listOf(0), active.rowFactorIds.toList())
                assertEquals(listOf(Lit.make(0, truth)), assertNotNull(exact.row(0).premises).literalEntries())
            }
            pin = null
            assertEquals(0, relaxer.build(domains).model.m)
        }
    }

    @Test
    fun `integer indicators should retain both activation bounds in the real objective cone`() {
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.GE, BigFraction.ofLong(5))
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(LinearIndicator(0, true, row)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(10.0))
        var domain = IntDomain(1, 1)
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = domain
            override fun boolValue(varId: Int): Boolean? = null
        }
        val relaxer = CpToLpRelaxation(problem, LinearObjective(realCoefficients = doubleArrayOf(1.0)),
            objectiveCone = true)

        val active = relaxer.build(domains)
        domain = problem.finiteIntDomain(0)
        val retracted = relaxer.build(domains)

        val exact = assertNotNull(active.model.authoritativeModel())
        val premises = assertNotNull(exact.row(0).premises).boundEntries()
        assertEquals(listOf(false, true), premises.map { it.upper })
        assertEquals(listOf(BigFraction.ONE, BigFraction.ONE), premises.map { it.threshold.value })
        assertEquals(listOf(0, 0), premises.map { it.variable })
        assertEquals(0, retracted.model.m)
    }

    @Test
    fun `wide exact rows should retain rational authority in the real objective cone`() {
        val tiny = BigFraction.of(BIG_ONE, parseBigInt("100000000000000000000"))
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
            listOf(BigFraction.ONE, tiny), LinearOp.EQ, BigFraction.ONE + tiny)
        val problem = Problem(0, 1, arrayOf(IntDomain(1, 1)), arrayOf<Factor>(row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))

        val cone = CpToLpRelaxation(problem, LinearObjective(realCoefficients = doubleArrayOf(1.0)),
            objectiveCone = true).build(RootDomains(problem))
        val result = solveAndCertify(cone.model)

        assertEquals(listOf(0), cone.rowFactorIds.toList())
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(BigFraction.ONE, result.lowerBound)
    }
}
