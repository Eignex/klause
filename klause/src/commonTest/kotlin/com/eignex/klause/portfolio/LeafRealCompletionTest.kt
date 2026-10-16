package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeafRealCompletionTest {

    // x + k = 2.5 and x ≤ 1 over a continuous x in [0, 10] and an integer k in [0, 5].
    private val problem = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 2.5),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(0.0),
        realUpper = doubleArrayOf(10.0),
    )

    private fun candidate(k: Long, x: Double) = Sample(BooleanArray(0), longArrayOf(k), doubleArrayOf(x))

    @Test
    fun `a candidate whose discrete part admits a completion should get exact reals`() {
        val completion = LeafRealCompletion(
            problem,
            objective = null,
        ).complete(candidate(2, 0.4999999), Cancellation.Never)

        val witness = assertIs<Completion.Witness>(completion)
        assertEquals(BigFraction.ofDouble(0.5), witness.sample.exactReals?.get(0))
        assertEquals(2L, witness.sample.ints[0])
    }

    @Test
    fun `a refutation should name the rows its proof combines`() {
        // k = 0 forces x = 2.5 through the first row, which the second row's x ≤ 1 forbids.
        val completion = LeafRealCompletion(problem, objective = null).complete(candidate(0, 1.0), Cancellation.Never)

        val refuted = assertIs<Completion.Refuted>(completion)
        assertEquals(setOf(0, 1), refuted.factors.toSet())
    }

    @Test
    fun `repeated completion should validate each candidate's finite pins`() {
        val completion = LeafRealCompletion(problem, objective = null)

        val first = completion.complete(candidate(2, 0.4), Cancellation.Never)
        val rejected = completion.complete(candidate(0, 0.4), Cancellation.Never)
        val repeated = completion.complete(candidate(2, 0.4), Cancellation.Never)

        assertEquals(BigFraction.ofDouble(0.5), assertIs<Completion.Witness>(first).sample.exactReals?.single())
        assertIs<Completion.Refuted>(rejected)
        assertEquals(BigFraction.ofDouble(0.5), assertIs<Completion.Witness>(repeated).sample.exactReals?.single())
    }

    @Test
    fun `a cancelled preparation should leave a later completion available`() {
        val completion = LeafRealCompletion(problem, objective = null)

        val stopped = completion.complete(candidate(2, 0.4), Cancellation { true })
        val resumed = completion.complete(candidate(2, 0.4), Cancellation.Never)

        assertIs<Completion.Undecided>(stopped)
        assertEquals(BigFraction.ofDouble(0.5), assertIs<Completion.Witness>(resumed).sample.exactReals?.single())
    }

    @Test
    fun `conditioned completion should install the current integer operand`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)),
            arrayOf(RealProduct(0, 0, 1, 1.0, 1.0)), numRealVars = 2,
            realLower = doubleArrayOf(1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val completion = LeafRealCompletion(source, objective = null)

        for (pin in listOf(0L, 1L, 0L)) {
            val sample = Sample(booleanArrayOf(), longArrayOf(pin), doubleArrayOf(1.0, 0.5))
            val result = completion.complete(sample, Cancellation.Never)

            assertEquals(BigFraction.ofLong(pin), assertIs<Completion.Witness>(result).sample.exactReals?.get(1))
        }
    }

    @Test
    fun `custom linear encoders should retain candidate-specific declines`() {
        val projections = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (target != EncodingTarget.LINEAR) selected else {
                Encoder(EncoderSupport.CONDITIONED, listOf(SourceIntId(0))) { view, encoding ->
                    if (encoding.fixedInteger(SourceIntId(0)) == bigIntOf(0)) EncoderDecline.UNSUPPORTED
                    else selected.install(view, encoding)
                }
            }
        }).prepare(problem)
        val encoding = LinearCompletionEncoding(projections)

        val accepted = encoding.modelFor(candidate(2, 0.5), Cancellation.Never)
        val declined = encoding.modelFor(candidate(0, 0.5), Cancellation.Never)

        assertNotNull(accepted)
        assertNull(declined)
    }

    @Test
    fun `numerical completion should retain CP source tolerance when exact certification is unavailable`() {
        val source = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I R 1\n" +
                " M1 'MARKER' 'INTEND'\n X COST 0.1 R 3\nRHS\n RHS R 1\n" +
                "BOUNDS\n BV B I\n UP B X 1\nENDATA\n",
        ).toProblem()
        val objective = source.objective?.toLinearObjective()
        val sample = Sample(
            booleanArrayOf(),
            longArrayOf(0),
            DoubleArray(source.model.numRealVars) { 1.0 },
            List(source.model.numRealVars) { BigFraction.ONE },
        )
        val context = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })
        val check: (Sample) -> Boolean = { source.withinTolerance(it.ints, it.reals) }

        val cp = leafRealFeasibility(source.model, objective, sample, context = context, toleranceCheck = check)
        val completion = LeafRealCompletion(source.model, objective, check, context)
            .complete(sample, Cancellation.Never)

        assertEquals(LpVerdict.TOLERANCE_OPTIMUM, cp.verdict)
        val witness = assertIs<Completion.Witness>(completion)
        assertEquals(cp.reals.toList(), witness.sample.reals.toList())
        assertTrue(check(witness.sample))
        assertNull(witness.sample.exactReals)
        assertEquals(
            source.sourceObjective(sample.ints, cp.reals),
            source.sourceObjective(witness.sample.ints, witness.sample.reals),
        )
        assertTrue(witness.work > 0L)
        assertEquals(List(source.model.numRealVars) { BigFraction.ONE }, sample.exactReals)
    }

    @Test
    fun `unavailable exact certification should not be replaced by absent or rejected tolerance`() {
        val context = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val checks = listOf<((Sample) -> Boolean)?>(null, { false })

        for (check in checks) {
            val completion = LeafRealCompletion(problem, objective, check, context)
                .complete(candidate(2, 0.4), Cancellation.Never)

            assertTrue(assertIs<Completion.Undecided>(completion).work > 0L)
        }
    }

    @Test
    fun `a tolerance point rejected at completion admission should stay private`() {
        var calls = 0
        val completion = LeafRealCompletion(
            problem,
            LinearObjective(realCoefficients = doubleArrayOf(1.0)),
            toleranceCheck = { ++calls == 1 },
        )
            .complete(candidate(2, 0.4), Cancellation.Never)

        assertIs<Completion.Undecided>(completion)
        assertEquals(2, calls)
    }

    @Test
    fun `cancellation during tolerance validation should withhold the completed witness`() {
        var stopped = false
        val completion = LeafRealCompletion(
            problem,
            LinearObjective(realCoefficients = doubleArrayOf(1.0)),
            toleranceCheck = {
                stopped = true
                true
            },
        )
            .complete(candidate(2, 0.4), Cancellation { stopped })

        assertTrue(assertIs<Completion.Undecided>(completion).work > 0L)
    }

    @Test
    fun `indicator pins should retain original objective direction under exact and tolerance completion`() {
        val source = Mps.parse(
            "OBJSENSE\n MAX\nROWS\n N COST\n L R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                " I COST 0\n M1 'MARKER' 'INTEND'\n X COST 1 R 1\nRHS\n RHS R 0.2\n" +
                "BOUNDS\n BV B I\n UP B X 1\nINDICATORS\n IF R I 1\nENDATA\n",
        ).toProblem()
        val objective = source.objective!!.toLinearObjective().negated()
        val check: (Sample) -> Boolean = { source.withinTolerance(it.ints, it.reals) }

        for (tolerance in listOf(false, true)) {
            for (pin in listOf(0L, 1L, 0L)) {
                val sample = Sample(booleanArrayOf(), longArrayOf(pin), doubleArrayOf(0.5))
                val completion = LeafRealCompletion(source.model, objective, check.takeIf { tolerance })
                    .complete(sample, Cancellation.Never)

                val witness = assertIs<Completion.Witness>(completion).sample
                assertEquals(pin, witness.ints.single())
                assertEquals(
                    if (pin == 0L) 1.0 else 0.2,
                    witness.approximateRealValue(source.columns.single { it.real }.id),
                )
                assertTrue(check(witness))
                if (tolerance) {
                    assertNull(witness.exactReals)
                } else {
                    assertEquals(
                        if (pin == 0L) BigFraction.ONE else BigFraction.ofLong(5L).reciprocal(),
                        source.sourceWitness(witness.ints, witness.exactReals).objective,
                    )
                }
            }
        }
    }
}
