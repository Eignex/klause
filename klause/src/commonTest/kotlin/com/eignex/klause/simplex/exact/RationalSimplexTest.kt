package com.eignex.klause.simplex.exact

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RationalSimplexTest {

    private class RecordingObserver : RationalSimplexObserver {
        val fracEligible = ArrayList<Boolean>()
        val escalations = ArrayList<Frac128Escalation>()
        val attempts = ArrayList<Pair<ExactSimplexStage, ExactSimplexRunResult>>()

        override fun observeFrac128Attempt(eligible: Boolean) {
            fracEligible += eligible
        }

        override fun observeEscalation(reason: Frac128Escalation) {
            escalations += reason
        }

        override fun observeSimplex(stage: ExactSimplexStage, result: ExactSimplexRunResult, elapsedNs: Long) {
            attempts += stage to result
        }
    }

    @Test
    fun `blocking certificate includes structural and slack bound sides`() {
        for (upperViolation in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(
                    intArrayOf(0),
                    listOf(if (upperViolation) BigFraction.MINUS_ONE else BigFraction.ONE),
                    BigFraction.ofLong(-2),
                ),
                ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ofLong(10)),
            )
            val model = ExactRationalFeasibilityModel(2, rows, listOf(BigFraction.ONE, null))

            val outcome = bigRationalOutcome(model)

            val conflict = assertNotNull(outcome.conflict)
            assertContentEquals(intArrayOf(0), conflict.rows)
            assertTrue(ExactSimplexBound(0, upperViolation) in conflict.bounds)
            assertTrue(ExactSimplexBound(2, upper = false) in conflict.bounds)
            assertValidConflict(model, outcome)
        }
    }

    @Test
    fun `strict blocking rows independently sum to a contradiction`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(
                    intArrayOf(0, 1),
                    listOf(BigFraction.ofLong(2), BigFraction.ofLong(-3)),
                    BigFraction.ZERO,
                    strict,
                ),
                ExactRationalInequality(
                    intArrayOf(0, 1),
                    listOf(BigFraction.ofLong(-2), BigFraction.ofLong(3)),
                    if (strict) BigFraction.ZERO else BigFraction.MINUS_ONE,
                ),
                ExactRationalInequality(intArrayOf(2), listOf(BigFraction.ONE), BigFraction.ONE),
            )
            val model = ExactRationalFeasibilityModel(3, rows)

            val outcome = bigRationalOutcome(model)

            assertEquals(setOf(0, 1), assertNotNull(outcome.conflict).rows.toSet())
            assertValidConflict(model, outcome)
        }
    }

    @Test
    fun `feasible and interrupted outcomes carry no conflict support`() {
        val model = ExactRationalFeasibilityModel(
            1,
            listOf(ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE, strict = true)),
        )

        val feasible = bigRationalOutcome(model)
        val capped = bigRationalOutcome(model, maxPivots = 0)
        val cancelled = bigRationalOutcome(model, Cancellation { true })

        assertEquals(RationalFeasibility.FEASIBLE, feasible.feasibility)
        assertNull(feasible.conflict)
        for (outcome in listOf(capped, cancelled)) {
            assertEquals(RationalFeasibility.UNKNOWN, outcome.feasibility)
            assertNull(outcome.conflict)
        }
    }

    @Test
    fun `probe bounds cannot supply an exact conflict`() {
        for (upper in listOf(false, true)) {
            val model = ExactRationalFeasibilityModel(
                1,
                listOf(
                    ExactRationalInequality(
                        intArrayOf(0),
                        listOf(if (upper) BigFraction.MINUS_ONE else BigFraction.ONE),
                        BigFraction.ofLong(-2),
                    ),
                ),
                listOf(BigFraction.ONE),
            )
            (if (upper) model.probeClampedHi else model.probeClampedLo)[0] = true

            val outcome = bigRationalOutcome(model)

            assertEquals(RationalFeasibility.UNKNOWN, outcome.feasibility)
            assertNull(outcome.conflict)
            assertEquals(RationalFeasibility.UNKNOWN, rationalOutcome(model).feasibility)
        }
    }

    @Test
    fun `overflow escalation retains the complete blocking row support`() {
        val builder = LpBuilder()
        val columns = IntArray(3) { builder.addVar(0, 1) }
        val scale = 1L shl 50
        builder.addRow(intArrayOf(columns[0]), longArrayOf(-scale), Relation.LE, -1)
        builder.addRow(intArrayOf(columns[0], columns[1]), longArrayOf(1, -scale), Relation.LE, 0)
        builder.addRow(intArrayOf(columns[1], columns[2]), longArrayOf(1, -scale), Relation.LE, 0)
        builder.addRow(intArrayOf(columns[2]), longArrayOf(1), Relation.LE, 0)
        val model = builder.build(Sense.MINIMIZE)
        val observer = RecordingObserver()

        val outcome = rationalOutcome(model, observer = observer)
        val exact = bigRationalOutcome(model)

        assertEquals(RationalFeasibility.INFEASIBLE, outcome.feasibility)
        assertEquals(listOf(Frac128Escalation.OVERFLOW), observer.escalations)
        assertEquals(setOf(0, 1, 2, 3), outcome.rows?.toSet())
        assertEquals(outcome.rows?.toSet(), exact.conflict?.rows?.toSet())
    }

    private fun assertValidConflict(model: ExactRationalFeasibilityModel, outcome: BigRationalOutcome) {
        assertEquals(RationalFeasibility.INFEASIBLE, outcome.feasibility)
        val conflict = assertNotNull(outcome.conflict)
        val weights = MutableList(model.m) { BigFraction.ZERO }
        for (index in conflict.rows.indices) weights[conflict.rows[index]] = conflict.multipliers[index]
        val view = model.bigView
        val activity = MutableList(model.numVars) { BigFraction.ZERO }
        for (column in 0 until model.n) {
            for (entry in view.colPtr[column] until view.colPtr[column + 1]) {
                activity[column] += weights[view.rowIdx[entry]] * view.colVal[entry]
            }
        }
        var rhs = BigFraction.ZERO
        var delta = BigFraction.ZERO
        for (row in 0 until model.m) {
            activity[model.n + row] = weights[row]
            rhs += weights[row] * view.rhs[row]
            if (model.rowStrict[row]) delta -= weights[row]
        }
        assertEquals(
            activity.indices.filter { !activity[it].isZero }.toSet(),
            conflict.bounds.map { it.column }.toSet(),
        )
        var minimum = BigFraction.ZERO
        for (bound in conflict.bounds) {
            val coefficient = activity[bound.column]
            assertEquals(coefficient < BigFraction.ZERO, bound.upper)
            if (bound.upper) minimum += coefficient * assertNotNull(view.upper[bound.column])
        }
        assertTrue(rhs < minimum || (rhs == minimum && delta < BigFraction.ZERO))
    }



    @Test
    fun `decides a fractional feasible system exactly`() {
        // 2x = 1 over x in [0, 1]: feasible only at the non-integer point x = 1/2.
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 1.0, cost = 0.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.EQ, 1.0)
        assertEquals(RationalFeasibility.FEASIBLE, rationalFeasible(b.build(Sense.MINIMIZE)))
    }

    @Test
    fun `keeps arbitrary precision rows out of the floating relaxation`() {
        val large = BIG_ONE shl 160
        val model = ExactRationalFeasibilityModel(
            n = 1,
            rows = listOf(
                ExactRationalInequality(
                    columns = intArrayOf(0),
                    coefficients = listOf(BigFraction.of(large, BIG_ONE)),
                    rhs = BigFraction.of(large, BIG_ONE),
                ),
            ),
        )

        val outcome = bigRationalOutcome(model)

        assertEquals(RationalFeasibility.FEASIBLE, outcome.feasibility)
        assertEquals(BigFraction.ZERO, outcome.witness!![0])
    }

    @Test
    fun `classifies bounded rows from the exact homogeneous cone`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ZERO),
        )

        val bounded = exactBoundedRows(rows, variables = 2)

        assertTrue(bounded!![0])
        assertTrue(bounded[1])
        assertTrue(!bounded[2])
    }

    @Test
    fun `minimizes an exact activity without a probe bound`() {
        val model = ExactRationalFeasibilityModel(
            n = 2,
            rows = listOf(
                ExactRationalInequality(
                    intArrayOf(0, 1),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE),
                    BigFraction.ofLong(5),
                ),
                ExactRationalInequality(
                    intArrayOf(0, 1),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE),
                    BigFraction.ofLong(-2),
                ),
            ),
        )

        val outcome = bigRationalMinimum(model, listOf(BigFraction.ONE, BigFraction.MINUS_ONE))

        assertEquals(RationalFeasibility.FEASIBLE, outcome.feasibility)
        assertEquals(BigFraction.ofLong(2), outcome.infimum)
        assertTrue(!outcome.unbounded)
    }

    @Test
    fun `reports an open exact objective direction as unbounded`() {
        val model = ExactRationalFeasibilityModel(
            n = 2,
            rows = listOf(
                ExactRationalInequality(
                    intArrayOf(0, 1),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE),
                    BigFraction.ofLong(5),
                ),
            ),
        )

        val outcome = bigRationalMinimum(model, listOf(BigFraction.ONE, BigFraction.MINUS_ONE))

        assertEquals(RationalFeasibility.FEASIBLE, outcome.feasibility)
        assertTrue(outcome.unbounded)
        assertEquals(null, outcome.infimum)
    }

    @Test
    fun `decides a coupled system with non-dyadic coefficients`() {
        // x/3 + y/3 = 1 and x + y <= 2 conflict (x + y must be 3); doubles of 1/3 are exact rationals.
        val third = 1.0 / 3.0
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 5.0, cost = 0.0)
        val y = b.addRealVar(0.0, 5.0, cost = 0.0)
        b.addRealRow(intArrayOf(x, y), doubleArrayOf(third, third), Relation.EQ, 1.0)
        b.addRealRow(intArrayOf(x, y), doubleArrayOf(1.0, 1.0), Relation.LE, 2.0)
        assertEquals(RationalFeasibility.INFEASIBLE, rationalFeasible(b.build(Sense.MINIMIZE)))
    }

    @Test
    fun `strict rows are decided by delta rationals`() {
        // x < 1 and x >= 1 over x in [0, 2]: infeasible only because of strictness.
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 2.0, cost = 0.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 1.0, strict = true)
        b.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
        assertEquals(RationalFeasibility.INFEASIBLE, rationalFeasible(b.build(Sense.MINIMIZE)))
    }

    @Test
    fun `returns a witness in original coordinates after a lower-bound shift`() {
        val b = LpBuilder()
        val x = b.addRealVar(4.0, 8.0, cost = 0.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.EQ, 10.0)

        val outcome = rationalOutcome(b.build(Sense.MINIMIZE))

        assertEquals(RationalFeasibility.FEASIBLE, outcome.feasibility)
        assertEquals(5.0, outcome.witness!![x])
    }

    @Test
    fun `a zero pivot budget reports unknown`() {
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 1.0, cost = 0.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.EQ, 3.0)
        assertEquals(RationalFeasibility.UNKNOWN, rationalFeasible(b.build(Sense.MINIMIZE), maxPivots = 0))
    }







}
