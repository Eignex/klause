package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs

class FiniteWitnessVerificationTest {
    @Test
    fun `an interrupted fixpoint leaves a finite candidate indeterminate`() {
        val model = Problem(
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 10)),
        ).bake()
        val candidate = Candidate<Sample, Double?>(Sample(BooleanArray(0), longArrayOf(3, 7)), null)

        val result = finiteWitnessVerifier(model, null, Cancellation { true }).verify(candidate)

        assertIs<Verification.Indeterminate>(result)
    }
    @Test
    fun `finite continuous witnesses retain the configured source tolerance policy`() {
        val model = Problem(
            numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf(ReifiedRealLinear(
                aux = 0, vars = intArrayOf(), intCoeffs = doubleArrayOf(),
                realVars = intArrayOf(0), realCoeffs = doubleArrayOf(1.0), op = LinearOp.LE, bound = 1.0,
            )),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
        ).bake()
        val candidate = Candidate<Sample, Double?>(
            Sample(booleanArrayOf(true), LongArray(0), reals = doubleArrayOf(1.00000001)), null,
        )
        val accepted = finiteWitnessVerifier(model, null, toleranceCheck = { it.reals[0] <= 1.0000001 })
        val rejected = finiteWitnessVerifier(model, null, toleranceCheck = { false })

        assertIs<Verification.Accepted<*, *>>(accepted.verify(candidate))
        assertIs<Verification.Rejected>(rejected.verify(candidate))
        assertIs<Verification.Indeterminate>(finiteWitnessVerifier(model, null).verify(candidate))
    }
}
