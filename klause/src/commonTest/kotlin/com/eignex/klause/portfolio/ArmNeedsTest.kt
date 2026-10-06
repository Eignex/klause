package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArmNeedsTest {
    private fun linearModel() = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
        ),
    ).bake()

    @Test
    fun `a linear row offers a relaxation`() {
        val facts = ProblemFacts.of(linearModel(), Kind.COP, LpConfig.AGGRESSIVE)

        assertTrue(facts.offers(ArmNeed.Relaxation(LpEmphasis.DEFAULT)))
    }

    @Test
    fun `clauses alone offer no relaxation`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = arrayOf(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        ).bake()

        val facts = ProblemFacts.of(problem, Kind.CSP, LpConfig.AGGRESSIVE)

        assertFalse(facts.offers(ArmNeed.Relaxation(LpEmphasis.AGGRESSIVE)))
    }

    @Test
    fun `an lp ceiling of off leaves nothing to relax`() {
        val facts = ProblemFacts.of(linearModel(), Kind.COP, LpConfig(LpEmphasis.OFF))

        assertFalse(facts.offers(ArmNeed.Relaxation(LpEmphasis.DEFAULT)))
    }

    @Test
    fun `a satisfaction model offers no objective`() {
        assertFalse(ProblemFacts.assumed(Kind.CSP).offers(ArmNeed.Objective))
    }

    @Test
    fun `a domain past the 32-bit range offers no local search to a mixed pool`() {
        val wide = 1L shl 40
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, wide)),
            factors = arrayOf<Factor>(Linear(longArrayOf(1L), intArrayOf(0), LinearOp.GE, 3L)),
        ).bake()

        assertFalse(ProblemFacts.of(problem, Kind.CSP, LpConfig.AGGRESSIVE).offers(ArmNeed.LocalSearch))
    }
}
