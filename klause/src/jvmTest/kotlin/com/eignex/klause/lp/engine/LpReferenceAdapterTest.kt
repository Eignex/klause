package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        val referenceInfeasible = LpReferenceAdapter().solve(infeasible)

        assertEquals(LpVerdict.OPTIMAL, solveAndCertify(feasible).verdict)
        assertEquals(BigFraction.ofLong(3L), referenceFeasible.objectiveLowerBound)
        assertTrue(referenceFeasible.lowerBoundAttained)
        assertNull(referenceFeasible.objectiveDecline)
        assertEquals(LpVerdict.INFEASIBLE, solveAndCertify(infeasible).verdict)
        assertIs<LpReferenceResult.Infeasible>(referenceInfeasible)
    }

    @Test
    fun `exact reference handles a continuous fractional optimum`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 10.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.GE, 3.0)
        }.build(Sense.MINIMIZE)

        val result = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model))

        assertEquals(BigFraction.ofDouble(1.5), result.objectiveLowerBound)
        assertTrue(result.lowerBoundAttained)
    }

    @Test
    fun `strict objective lower bound is not promoted to an attained optimum`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)

        val result = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model))

        assertEquals(BigFraction.ZERO, result.objectiveLowerBound)
        assertFalse(result.lowerBoundAttained)
        assertTrue(result.witness.single() > BigFraction.ZERO)
        assertNull(result.objectiveDecline)
    }

    @Test
    fun `unbounded objective has no finite reference lower bound`() {
        val model = LpBuilder().apply {
            addOpenAboveVar(0L, cost = -1L)
        }.build(Sense.MINIMIZE)

        val result = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model))

        assertNull(result.objectiveLowerBound)
        assertFalse(result.lowerBoundAttained)
        assertNull(result.objectiveDecline)
    }

    @Test
    fun `probe bounded objective comparison declines`() {
        val model = LpBuilder().apply {
            addFreeVar(lower = null, upper = 3L, cost = 1L)
        }.build(Sense.MINIMIZE)

        val result = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model))

        assertNull(result.objectiveLowerBound)
        assertFalse(result.lowerBoundAttained)
        assertEquals(LpReferenceDecline.PROBE_BOUND_OBJECTIVE, result.objectiveDecline)
    }

    @Test
    fun `cancelled reference run declines instead of deciding`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 2L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)

        val result = assertIs<LpReferenceResult.Declined>(
            LpReferenceAdapter(Cancellation { true }).solve(model),
        )

        assertEquals(LpReferenceDecline.CANCELLED_OR_PIVOT_LIMIT, result.reason)
    }

    @Test
    fun `reference adapter has no float engine or certifier dependency`() {
        val root = generateSequence(Paths.get("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.exists(it.resolve("klause/src/jvmTest")) }
        val source = root.resolve(
            "klause/src/jvmTest/kotlin/com/eignex/klause/lp/engine/LpReferenceAdapter.kt",
        ).readText()

        val forbidden = listOf("RevisedSimplex", "integerCertify", "integerFarkasRay", "solveAndCertify")

        assertEquals(emptyList(), forbidden.filter(source::contains))
    }
}
