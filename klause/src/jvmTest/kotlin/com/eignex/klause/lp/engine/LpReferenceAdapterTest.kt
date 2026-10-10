package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LpReferenceAdapterTest {

    @Test
    fun `exact reference agrees with certified feasible and infeasible verdicts`() {
        val feasible = LpBuilder().apply {
            val x = addVar(0L, 5L, cost = 1L)
            val y = addVar(0L, 5L, cost = 1L)
            addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 3L)
        }.build(Sense.MINIMIZE)
        val infeasible = LpBuilder().apply {
            val x = addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)

        val referenceFeasible = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(feasible))
        val certifiedFeasible = solveAndCertify(feasible)
        val referenceBound = assertIs<LpReferenceObjective.Bound>(referenceFeasible.objective)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, certifiedFeasible.verdict)
        assertEquals(3L, certifiedFeasible.integerObjectiveLowerBound)
        assertEquals(BigFraction.ofLong(3L), referenceBound.lower)
        assertTrue(referenceBound.attained)
        assertEquals(LpVerdict.INFEASIBLE, solveAndCertify(infeasible).verdict)
        assertIs<LpReferenceResult.Infeasible>(LpReferenceAdapter().solve(infeasible))
    }

    @Test
    fun `exact reference agrees with a certified continuous fractional optimum`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 10.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.GE, 3.0)
        }.build(Sense.MINIMIZE)

        val reference = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model))
        val certified = solveAndCertify(model)
        val referenceBound = assertIs<LpReferenceObjective.Bound>(reference.objective)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
        assertEquals(
            referenceBound.lower.toDouble(),
            checkNotNull(certified.float).objective,
            1e-9,
        )
        assertEquals(BigFraction.ofDouble(1.5), referenceBound.lower)
        assertTrue(referenceBound.attained)
    }

    @Test
    fun `active row mask changes the exact reference problem`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)

        assertIs<LpReferenceResult.Infeasible>(LpReferenceAdapter().solve(model, booleanArrayOf(true)))
        assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model, booleanArrayOf(false)))
    }

    @Test
    fun `strict objective infimum is not promoted to an attained optimum`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)

        val reference = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model))
        val referenceBound = assertIs<LpReferenceObjective.Bound>(reference.objective)

        assertEquals(LpVerdict.FEASIBLE, solveAndCertify(model).verdict)
        assertEquals(BigFraction.ZERO, referenceBound.lower)
        assertFalse(referenceBound.attained)
        assertTrue(reference.witness.single() > BigFraction.ZERO)
    }

    @Test
    fun `unbounded objective has no finite reference lower bound`() {
        val model = LpBuilder().apply {
            addOpenAboveVar(0L, cost = -1L)
        }.build(Sense.MINIMIZE)

        val reference = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model))

        assertIs<LpReferenceObjective.Unbounded>(reference.objective)
    }

    @Test
    fun `probe bounded objective comparison declines`() {
        val model = LpBuilder().apply {
            addFreeVar(lower = null, upper = 3L, cost = 1L)
        }.build(Sense.MINIMIZE)

        val reference = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model))

        assertIs<LpReferenceObjective.ProbeBoundDecline>(reference.objective)
    }

    @Test
    fun `source witness checks omit probe sides but retain real sides and rows`() {
        for (lowerClamped in listOf(false, true)) {
            val model = LpBuilder().apply {
                val x = addFreeVar(if (lowerClamped) null else 0L, if (lowerClamped) 0L else null)
                addRow(
                    intArrayOf(x),
                    longArrayOf(1L),
                    if (lowerClamped) Relation.GE else Relation.LE,
                    if (lowerClamped) -5L else 5L,
                )
            }.build(Sense.MINIMIZE).rebind(
                longArrayOf(if (lowerClamped) -3L else 0L),
                longArrayOf(if (lowerClamped) 0L else 3L),
            )
            val adapter = LpReferenceAdapter()
            val direction = if (lowerClamped) -1L else 1L

            for ((point, expected) in listOf(5L to true, -1L to false, 6L to false)) {
                val value = point * direction
                assertEquals(expected, adapter.accepts(model, longArrayOf(value.toDouble().toRawBits())))
                assertEquals(expected, adapter.acceptsExact(model, listOf(BigFraction.ofLong(value))))
            }
        }
    }

    @Test
    fun `probe box infeasibility declines instead of refuting the open model`() {
        val model = LpBuilder().apply {
            val x = addFreeVar(lower = null, upper = null)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, LP_UNBOUNDED_PROBE + 1L)
        }.build(Sense.MINIMIZE)

        val result = assertIs<LpReferenceResult.Declined>(LpReferenceAdapter().solve(model))

        assertEquals(LpReferenceDecline.PROBE_BOUND_FEASIBILITY, result.reason)
    }

    @Test
    fun `cancelled reference run declines instead of deciding`() {
        val model = constrainedModel()

        val result = assertIs<LpReferenceResult.Declined>(
            LpReferenceAdapter(Cancellation { true }).solve(model),
        )

        assertEquals(LpReferenceDecline.CANCELLED_OR_PIVOT_LIMIT, result.reason)
    }

    @Test
    fun `non finite input has its own decline reason`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 2.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 1.0)
        }.build(Sense.MINIMIZE)
        model.doubleView!!.rhs[0] = Double.NaN

        val result = assertIs<LpReferenceResult.Declined>(LpReferenceAdapter().solve(model))

        assertEquals(LpReferenceDecline.NON_FINITE_INPUT, result.reason)
    }

    private fun constrainedModel(): LpModel = LpBuilder().apply {
        val x = addVar(0L, 2L)
        addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
    }.build(Sense.MINIMIZE)
}
