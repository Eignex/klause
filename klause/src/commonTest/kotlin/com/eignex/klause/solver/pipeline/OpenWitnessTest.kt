package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.util.Bits
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.unaryMinus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpenWitnessTest {
    @Test
    fun `a copied mixed theory witness retains its assignment and exact exchanged objective`() {
        val wide = parseBigInt("9223372036854775808")
        val third = BigFraction.ofLong(3).reciprocal()
        val source = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0), longArrayOf(0), Bits(1).also { it.set(0) }, Bits(1).also { it.set(0) },
            ),
            factors = emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val witness = OpenTheoryAssignment.ExactLira(
            ExactLiraAssignment(booleanArrayOf(true), arrayOf(wide), listOf(third)),
        ).toSample(source).copy()
        val objective = LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(1.0))
        val exchange = PortfolioIncumbents<BigFraction>(
            valueOf = { objective.evaluateExact(it.sample) },
            improves = { candidate, standing -> candidate < standing },
            approximateValue = { it.toDouble() },
            gain = { standing, candidate -> (standing - candidate).toDouble() },
        )
        val result = MinimizeResult.BestFound(witness, objective.evaluate(witness), TerminationReason.BudgetExhausted)
        exchange.exchange.offer(witness, requireNotNull(exchange.valueOf(result)))

        val incumbent = requireNotNull(exchange.exchange.current())
        val assignment = OpenTheoryAssignment.Sampled(incumbent.assignment.copy())

        assertEquals(wide.toString(), assignment.intValue(0))
        assertEquals("1/3", assignment.realValue(0))
        assertEquals(true, assignment.boolValue(0))
        assertEquals(BigFraction.of(wide, bigIntOf(1)) + third, incumbent.objective)
    }

    @Test
    fun `a wide theory witness is explicitly projected into local search windows`() {
        val wide = parseBigInt("9223372036854775808")
        val witness = Sample(BooleanArray(0), LongArray(0), exactInts = listOf(wide, -wide))
        witness.witnessCertificate = EvidenceCertificate.verified(ModelIdentity.of(Any()), EvidenceKind.Witness)
        val search = LocalSearchModel.open(model)

        val projected = inWindows(witness.copy(), search)

        assertEquals(search.domains[0].max, projected.ints[0])
        assertEquals(search.domains[1].min, projected.ints[1])
        assertNull(projected.exactInts)
        assertNull(projected.witnessCertificate)
        assertEquals(wide, witness.exactInts?.first())
    }

    // x0 ≥ 0 declared, open above, and x0 + x1 = 10 with x1 open on both sides.
    private val model = Problem(
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(
            longArrayOf(0, 0),
            longArrayOf(0, 0),
            Bits(2).also { it.set(1) },
            Bits(2).also {
                it.set(0)
                it.set(1)
            },
        ),
        factors = arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 10)),
    )

    private fun sample(x0: Long, x1: Long) = Sample(BooleanArray(0), longArrayOf(x0, x1))

    @Test
    fun `a point satisfying every factor is a witness`() {
        assertNull(refuteOpenWitness(model, sample(3, 7)))
    }

    @Test
    fun `a point violating a factor is refuted`() {
        assertNotNull(refuteOpenWitness(model, sample(3, 8)))
    }

    @Test
    fun `a point outside a declared side is refuted`() {
        assertNotNull(refuteOpenWitness(model, sample(-1, 11)))
    }
    @Test
    fun `a copied witness from another model is rejected by the source verifier`() {
        val candidate = sample(3, 7)
        candidate.witnessCertificate = EvidenceCertificate.verified(ModelIdentity.of(Any()), EvidenceKind.Witness)

        val verdict = openWitnessVerifier(model, null).verify(Candidate(candidate.copy(), null))

        assertIs<Verification.Rejected>(verdict)
    }

    @Test
    fun `exact real coordinates must satisfy strict reification and source bounds`() {
        val source = Problem(
            numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf(ReifiedRealLinear(
                aux = 0, vars = intArrayOf(), intCoeffs = doubleArrayOf(),
                realVars = intArrayOf(0), realCoeffs = doubleArrayOf(1.0),
                op = LinearOp.LE, bound = 1.0, strict = true,
            )),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
        )
        val cases = listOf(
            Triple(true, BigFraction.ONE, false),
            Triple(true, BigFraction.ofLong(2).reciprocal(), true),
            Triple(false, BigFraction.ONE, true),
            Triple(false, BigFraction.ofLong(3), false),
        )
        for ((truth, value, accepted) in cases) {
            val point = Sample(
                booleanArrayOf(truth), LongArray(0),
                reals = doubleArrayOf(value.toDouble()), exactReals = listOf(value),
            )
            val verdict = openWitnessVerifier(source, null).verify(Candidate(point, null))

            if (accepted) assertIs<Verification.Accepted<*, *>>(verdict) else assertIs<Verification.Rejected>(verdict)
        }
    }

    @Test
    fun `missing exact real coordinates leave an open witness indeterminate`() {
        val source = Problem(
            numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray(),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
        )
        val point = Sample(BooleanArray(0), LongArray(0), reals = doubleArrayOf(1.0))

        val verdict = openWitnessVerifier(source, null).verify(Candidate(point, null))

        assertIs<Verification.Indeterminate>(verdict)
    }
}
