package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPrimaryFeasibilityTest {
    @Test
    fun `coupled equalities should produce a checked point with free coordinates`() {
        val source = LpBuilder().apply {
            val x = addFreeVar(null, null)
            val y = addFreeVar(null, null)
            addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.EQ, 1L)
            addRow(intArrayOf(x, y), longArrayOf(1L, -1L), Relation.EQ, 3L)
        }.build(Sense.MINIMIZE)
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            val result = assertNotNull(owner.solve(primaryExactFeasibility = true))

            assertEquals(listOf(BigFraction.ofLong(2L), BigFraction.MINUS_ONE), result.exactPrimal)
            assertNotNull(checkedLpWitness(assertNotNull(owner.state.toWorkingModel()), assertNotNull(result.exactPrimal)))
        }
    }

    @Test
    fun `inconsistent upper and lower row bounds should carry checked conflicts`() {
        for (upper in listOf(false, true)) {
            val source = LpBuilder().apply {
                val x = addVar(0L, 1L)
                addRow(intArrayOf(x), longArrayOf(1L), if (upper) Relation.LE else Relation.GE, if (upper) -1L else 2L)
            }.build(Sense.MINIMIZE)
            LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
                val result = assertNotNull(owner.solve(primaryExactFeasibility = true))

                assertEquals(LpVerdict.INFEASIBLE, result.verdict)
                assertTrue(checkedLpConflict(assertNotNull(owner.state.toWorkingModel()), assertNotNull(result.rationalConflict)))
            }
        }
    }

    @Test
    fun `coupled row combinations should carry a checked Farkas conflict`() {
        val source = LpBuilder().apply {
            val x = addFreeVar(null, 1L)
            val y = addFreeVar(null, null)
            addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.EQ, 1L)
            addRow(intArrayOf(x, y), longArrayOf(1L, -1L), Relation.EQ, 3L)
        }.build(Sense.MINIMIZE)
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            val result = assertNotNull(owner.solve(primaryExactFeasibility = true))

            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            assertTrue(checkedLpConflict(assertNotNull(owner.state.toWorkingModel()), assertNotNull(result.rationalConflict)))
            assertNotNull(result.conflictSupport)
        }
    }

    @Test
    fun `popping scoped bounds should restore a feasible point after a conflict`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 4L)
            val y = addVar(0L, 4L)
            addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.EQ, 4L)
        }.build(Sense.MINIMIZE)
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            assertNotNull(owner.solve(primaryExactFeasibility = true)?.exactPrimal)
            assertTrue(owner.push())
            val side = ExactLpSide(ExactLpNumber.of(1L))
            assertTrue(owner.assertBound(0, true, side, 0L))
            assertTrue(owner.assertBound(1, true, side, 1L))
            assertEquals(LpVerdict.INFEASIBLE, owner.solve(primaryExactFeasibility = true)?.verdict)
            assertTrue(owner.pop(0))

            val result = assertNotNull(owner.solve(primaryExactFeasibility = true))

            val point = assertNotNull(result.exactPrimal)
            assertEquals(BigFraction.ofLong(4L), point[0] + point[1])
            assertNull(result.rationalConflict)
            assertNotNull(checkedLpWitness(assertNotNull(owner.state.toWorkingModel()), point))
        }
    }

    @Test
    fun `tightened bounds should produce a point in the current box`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 4L)
            val y = addVar(0L, 4L)
            addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.EQ, 4L)
        }.build(Sense.MINIMIZE)
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            assertNotNull(owner.solve(primaryExactFeasibility = true)?.exactPrimal)
            assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(1L)), 0L))
            assertTrue(owner.assertBound(1, false, ExactLpSide(ExactLpNumber.of(2L)), 1L))

            val result = assertNotNull(owner.solve(primaryExactFeasibility = true))

            val point = assertNotNull(result.exactPrimal)
            assertTrue(point[0] >= BigFraction.ONE && point[1] >= BigFraction.ofLong(2L))
            assertEquals(BigFraction.ofLong(4L), point[0] + point[1])
            assertNotNull(checkedLpWitness(assertNotNull(owner.state.toWorkingModel()), point))
        }
    }

    @Test
    fun `appended definitions should invalidate a cached point from another matrix`() {
        val source = LpBuilder().apply { addVar(0L, 3L) }.build(Sense.MINIMIZE)
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            assertNotNull(owner.solve(primaryExactFeasibility = true)?.exactPrimal)
            val zero = ExactLpNumber.of(0L)
            val row = LpScopedRow(0L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(2L),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))))
            assertTrue(owner.replaceRows(emptySet(), emptyList(), listOf(row), false))

            val result = assertNotNull(owner.solve(primaryExactFeasibility = true))

            assertEquals(listOf(BigFraction.ofLong(2L)), result.exactPrimal)
            assertNotNull(checkedLpWitness(assertNotNull(owner.state.toWorkingModel()), assertNotNull(result.exactPrimal)))
        }
    }

    @Test
    fun `rejected rational or point evidence should leave feasibility indeterminate`() {
        for (rejected in listOf(LpCertifier.RATIONAL, LpCertifier.EXACT_POINT)) {
            val source = LpBuilder().apply { addVar(2L, 2L) }.build(Sense.MINIMIZE)
            val policy = LpCertificationPolicy { certifier, successful -> successful && certifier != rejected }
            val context = LpSolveContext(certificationPolicy = policy)
            LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel())), context = context).use { owner ->
                val result = assertNotNull(owner.solve(primaryExactFeasibility = true))

                assertEquals(LpVerdict.INDETERMINATE, result.verdict)
                assertNull(result.exactPrimal)
            }
        }
    }

    @Test
    fun `rejected Farkas evidence should leave a conflicting scope indeterminate`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val policy = LpCertificationPolicy { certifier, successful -> successful && certifier != LpCertifier.EXACT_FARKAS }
        val context = LpSolveContext(certificationPolicy = policy)
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel())), context = context).use { owner ->
            val result = assertNotNull(owner.solve(primaryExactFeasibility = true))

            assertEquals(LpVerdict.INDETERMINATE, result.verdict)
            assertNull(result.rationalConflict)
        }
    }

    @Test
    fun `strict sides should retain an interior feasibility witness`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(listOf(emptyList()), emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(one)), integral = false)),
            emptyList(), ExactLpObjective(listOf(zero)))
        LpScopedSolver(LpExactState(source)).use { owner ->
            val result = assertNotNull(owner.solve(primaryExactFeasibility = true))

            val coordinate = assertNotNull(result.exactPrimal).single()
            assertTrue(coordinate > BigFraction.ZERO && coordinate <= BigFraction.ONE)
        }
    }

    @Test
    fun `nonzero objectives should retain their certified optimum`() {
        val source = LpBuilder().apply { addVar(0L, 3L, cost = -1L) }.build(Sense.MINIMIZE)
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            val result = assertNotNull(owner.solve(primaryExactFeasibility = true))

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertEquals(BigFraction.ofLong(-3L), result.lowerBound)
        }
    }

    @Test
    fun `repeated attempts on an exhausted authority should not renew its work allowance`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        var charged = 0L
        val meter = WorkMeter { charged += it }
        val token = object : Cancellation {
            override fun isCancelled() = false
            override fun workMeter() = meter
        }
        val state = LpExactState(assertNotNull(source.authoritativeModel()))
        val cache = LpPrimaryFeasibilityCache()
        assertEquals(LpVerdict.INDETERMINATE,
            cache.solve(state, token, ProductionLpCertificationPolicy, null, 1L, 0).result?.verdict)
        val previous = charged

        val repeated = cache.solve(state, token, ProductionLpCertificationPolicy, null, 1L, 0)

        assertEquals(LpVerdict.INDETERMINATE, assertNotNull(repeated.result).verdict)
        assertEquals(previous, charged)
    }

    @Test
    fun `an exhausted primary pivot allowance should remain indeterminate`() {
        val source = LpBuilder().apply {
            val x = addFreeVar(null, null)
            val y = addFreeVar(null, null)
            addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.EQ, 1L)
            addRow(intArrayOf(x, y), longArrayOf(1L, -1L), Relation.EQ, 3L)
        }.build(Sense.MINIMIZE)
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel())), iterationLimit = 1).use { owner ->
            val result = assertNotNull(owner.solve(primaryExactFeasibility = true))

            assertEquals(LpVerdict.INDETERMINATE, result.verdict)
            assertNull(result.exactPrimal)
        }
    }

    @Test
    fun `checked witnesses should restore origins and minimized objective units`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(listOf(emptyList()), emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)), origin = ExactLpNumber.of(7L))),
            emptyList(), ExactLpObjective(listOf(zero), constant = ExactLpNumber.of(12L),
                scale = ExactLpNumber.of(3L), externalConstant = ExactLpNumber.of(5L), sense = Sense.MAXIMIZE))
        LpScopedSolver(LpExactState(source)).use { owner ->
            val result = assertNotNull(owner.solve(primaryExactFeasibility = true))

            val witness = assertNotNull(result.witness)
            assertEquals(listOf(BigFraction.ofLong(7L)), witness.primal)
            assertEquals(BigFraction.ofLong(9L), witness.objective)
        }
    }

    @Test
    fun `an explicit engine factory should keep control of its solve`() {
        val source = LpBuilder().apply { addVar(0L, 3L) }.build(Sense.MINIMIZE)
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation, refactorUpdateLimit,
                    iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by delegate {
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? = error("selected engine")
                }
            }
        }
        val context = LpSolveContext(engineFactory = factory)
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel())), context = context).use { owner ->
            val failure = assertFailsWith<IllegalStateException> { owner.solve(primaryExactFeasibility = true) }

            assertEquals("selected engine", failure.message)
        }
    }
}
