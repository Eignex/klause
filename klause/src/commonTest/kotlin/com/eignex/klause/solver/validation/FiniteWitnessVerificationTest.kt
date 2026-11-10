package com.eignex.klause.solver.validation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FiniteWitnessVerificationTest {
    @Test
    fun `cancelled verification should leave a finite candidate indeterminate`() {
        val model = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 10)),
        ).bake()
        val candidate = Candidate<Sample, Double?>(Sample(BooleanArray(0), longArrayOf(3, 7)), null)

        val result = finiteWitnessVerifier(model, null, Cancellation { true }).verify(candidate)

        assertIs<Verification.Indeterminate>(result)
    }

    @Test
    fun `continuous witness verification should follow the tolerance check`() {
        val model = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                ReifiedRealLinear(
                    aux = 0,
                    vars = intArrayOf(),
                    intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.LE,
                    bound = 1.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        ).bake()
        val candidate = Candidate<Sample, Double?>(
            Sample(booleanArrayOf(true), LongArray(0), reals = doubleArrayOf(1.00000001)),
            null,
        )
        val accepted = finiteWitnessVerifier(model, null, toleranceCheck = { it.reals[0] <= 1.0000001 })
        val rejected = finiteWitnessVerifier(model, null, toleranceCheck = { false })

        assertIs<Verification.Accepted<*, *>>(accepted.verify(candidate))
        assertIs<Verification.Rejected>(rejected.verify(candidate))
        assertIs<Verification.Indeterminate>(finiteWitnessVerifier(model, null).verify(candidate))
    }

    @Test
    fun `mixed product verification should use exact rational coordinates`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(2, 2)),
            arrayOf(RealProduct(0, 0, 1, 0.0, 1.0)),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(1.0, 2.0),
        ).bake()
        val third = BigFraction.ofLong(3).reciprocal()
        val samples = listOf(
            Sample(
                booleanArrayOf(),
                longArrayOf(2),
                doubleArrayOf(third.toDouble(), (third + third).toDouble()),
                listOf(third, third + third),
            ),
            Sample(
                booleanArrayOf(),
                longArrayOf(2),
                doubleArrayOf(third.toDouble(), 1.0),
                listOf(third, BigFraction.ONE),
            ),
        )

        val accepted = finiteWitnessVerifier(source, null).verify(Candidate<Sample, Double?>(samples[0], null))
        val rejected = finiteWitnessVerifier(source, null).verify(Candidate<Sample, Double?>(samples[1], null))

        assertIs<Verification.Accepted<*, *>>(accepted)
        assertIs<Verification.Rejected>(rejected)
    }

    @Test
    fun `a point accepted under the same model and policy should not be checked again`() {
        var checks = 0
        val tolerance: (Sample) -> Boolean = { checks++; true }
        val model = realModel()
        val point = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.5))
        finiteWitnessVerifier(model, null, toleranceCheck = tolerance).verify(Candidate(point, null))

        val result = finiteWitnessVerifier(model, null, toleranceCheck = tolerance).verify(Candidate(point, null))

        assertIs<Verification.Accepted<*, *>>(result)
        assertEquals(1, checks)
    }

    @Test
    fun `another tolerance policy should check an accepted point again`() {
        var checks = 0
        val model = realModel()
        val point = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.5))
        finiteWitnessVerifier(model, null, toleranceCheck = { checks++; true }).verify(Candidate(point, null))

        finiteWitnessVerifier(model, null, toleranceCheck = { checks++; true }).verify(Candidate(point, null))

        assertEquals(2, checks)
    }

    @Test
    fun `changed coordinates should not inherit an accepted check`() {
        var checks = 0
        val tolerance: (Sample) -> Boolean = { checks++; true }
        val model = realModel()
        val point = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.5))
        val verifier = finiteWitnessVerifier(model, null, toleranceCheck = tolerance)
        verifier.verify(Candidate(point, null))

        verifier.verify(Candidate(point.copy(reals = doubleArrayOf(0.25)), null))

        assertEquals(2, checks)
    }

    @Test
    fun `a reused point should still be checked against consumer restrictions`() {
        val model = Problem(0, 2, Array(2) { IntDomain(0, 10) },
            arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 10))).bake()
        val point = Sample(BooleanArray(0), longArrayOf(3, 7))
        val points = FinitePointCheck(model)
        finiteWitnessVerifier(model, null, Assumptions.None, points = points).verify(Candidate(point, null))

        val result = finiteWitnessVerifier(model, null, Assumptions.None.withInt(0, 4), points = points)
            .verify(Candidate(point, null))

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `a tolerance check cut short by cancellation should stay indeterminate and be repeated`() {
        var checks = 0
        var cancelled = false
        val tolerance: (Sample) -> Boolean = { checks++; cancelled = checks == 1; true }
        val model = realModel()
        val point = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.5))
        val interrupted = finiteWitnessVerifier(model, null, Cancellation { cancelled }, tolerance)
            .verify(Candidate(point, null))

        val repeated = finiteWitnessVerifier(model, null, toleranceCheck = tolerance).verify(Candidate(point, null))

        assertIs<Verification.Indeterminate>(interrupted)
        assertIs<Verification.Accepted<*, *>>(repeated)
        assertEquals(2, checks)
    }

    private fun realModel() = Problem(
        0, 0, emptyArray(), emptyList<Factor>(),
        numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
    ).bake()

    @Test
    fun `another model should check an accepted point again`() {
        var checks = 0
        val tolerance: (Sample) -> Boolean = { checks++; true }
        val point = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.5))
        finiteWitnessVerifier(realModel(), null, toleranceCheck = tolerance).verify(Candidate(point, null))

        finiteWitnessVerifier(realModel(), null, toleranceCheck = tolerance).verify(Candidate(point, null))

        assertEquals(2, checks)
    }
}
