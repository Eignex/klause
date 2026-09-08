package com.eignex.klause.lp.engine

import com.eignex.klause.lp.LpBoundaryScanner
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText
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

        assertEquals(LpVerdict.OPTIMAL, certifiedFeasible.verdict)
        assertEquals(3L, certifiedFeasible.exactLowerBound)
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

        assertEquals(LpVerdict.OPTIMAL, certified.verdict)
        assertEquals(
            referenceBound.lower.toDouble(),
            checkNotNull(certified.float).objective,
            1e-9,
        )
        assertEquals(BigFraction.ofDouble(1.5), referenceBound.lower)
        assertTrue(referenceBound.attained)
    }

    @Test
    fun `strict objective infimum is not promoted to an attained optimum`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)

        val reference = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model))
        val referenceBound = assertIs<LpReferenceObjective.Bound>(reference.objective)

        assertEquals(LpVerdict.OPTIMAL, solveAndCertify(model).verdict)
        assertEquals(BigFraction.ZERO, referenceBound.lower)
        assertFalse(referenceBound.attained)
        assertTrue(reference.witness.single() > BigFraction.ZERO)
    }

    @Test
    fun `attained optimum is witnessed even when phase one stops elsewhere`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 10.0, cost = 1.0)
            val y = addRealVar(0.0, 10.0)
            addRealRow(intArrayOf(x, y), doubleArrayOf(1.0, 1.0), Relation.GE, 3.0)
        }.build(Sense.MINIMIZE)

        val reference = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model))
        val referenceBound = assertIs<LpReferenceObjective.Bound>(reference.objective)

        assertEquals(BigFraction.ZERO, referenceBound.lower)
        assertTrue(referenceBound.attained)
        assertEquals(BigFraction.ZERO, reference.witness[0])
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
    fun `zero cost probe support still declines objective comparison`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 10L, cost = 1L)
            val y = addFreeVar(lower = 0L, upper = null)
            addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, LP_UNBOUNDED_PROBE + 5L)
        }.build(Sense.MINIMIZE)

        val reference = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model))

        assertIs<LpReferenceObjective.ProbeBoundDecline>(reference.objective)
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
    fun `integer shifts beyond double precision stay exact`() {
        val fixed = 9_007_199_254_740_993L
        val model = LpBuilder().apply {
            addVar(fixed, fixed, cost = 1L)
        }.build(Sense.MINIMIZE)

        val reference = assertIs<LpReferenceResult.Feasible>(LpReferenceAdapter().solve(model))
        val referenceBound = assertIs<LpReferenceObjective.Bound>(reference.objective)

        assertEquals(BigFraction.ofLong(fixed), reference.witness.single())
        assertEquals(BigFraction.ofLong(fixed), referenceBound.lower)
        assertTrue(referenceBound.attained)
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
    fun `pivot limited reference run declines instead of deciding`() {
        val result = assertIs<LpReferenceResult.Declined>(
            LpReferenceAdapter(maxPivots = 0).solve(constrainedModel()),
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

    @Test
    fun `reference adapter reaches no float engine implementation or certifier`() {
        val root = generateSequence(Paths.get("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.exists(it.resolve("klause/src/jvmTest")) }
        val adapter = root.resolve(
            "klause/src/jvmTest/kotlin/com/eignex/klause/lp/engine/LpReferenceAdapter.kt",
        ).readText().let(LpBoundaryScanner::codeOnly)
        val declaration = Regex(
            "^(?:(?:internal|private|public|protected|inline|suspend|abstract|sealed|data|value|" +
                "open|expect|actual|external|tailrec|operator|infix)\\s+)*" +
                "(?:fun\\s+interface|enum\\s+class|annotation\\s+class|class|interface|object|fun|" +
                "typealias|const\\s+val)" +
                "\\s+([A-Za-z_]\\w*)",
        )
        val engineRoot = root.resolve("klause/src/commonMain/kotlin/com/eignex/klause/lp/engine")
        val engineNames = Files.walk(engineRoot).use { files ->
            files.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList().flatMap { file ->
                LpBoundaryScanner.codeOnly(file.readText())
                    .lineSequence()
                    .mapNotNull(declaration::find)
                    .map { it.groupValues[1] }
                    .toList()
            }
        }
        val referencedEngineNames = engineNames
            .filter { name -> Regex("\\b$name\\b").containsMatchIn(adapter) }
            .toSet()

        assertEquals(setOf("LpModel"), referencedEngineNames)
    }

    private fun constrainedModel(): LpModel = LpBuilder().apply {
        val x = addVar(0L, 2L)
        addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
    }.build(Sense.MINIMIZE)
}
