package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisRepair
import com.eignex.klause.simplex.basis.BasisRepairControl
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpScopedSolverTest {
    @Test
    fun `coarsened owners should reuse an unchanged group after a bound edit`() {
        val builder = LpBuilder()
        repeat(12) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val admission = object : LpComponentAdmission {
            override val ownerMergeWork = 13L
            override fun accepts(shape: LpComponentShape): Boolean = true
        }
        LpScopedSolver(state, componentSplit = true, componentAdmission = admission).use { owner ->
            assertEquals(3, assertNotNull(assertNotNull(owner.solve()).float).blocks)

            assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(4L)), 0L))
            val result = assertNotNull(owner.solve())

            assertEquals(BigFraction.ofLong(19L) * BigFraction.ofLong(2L).reciprocal(), result.lowerBound)
            assertEquals(2, owner.lastMetrics.componentReuses)
            assertEquals(1, owner.lastMetrics.componentSolves)
        }
    }

    @Test
    fun `a joining row should preserve a separate coarsened owner`() {
        val builder = LpBuilder()
        repeat(12) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val admission = object : LpComponentAdmission {
            override val ownerMergeWork = 13L
            override fun accepts(shape: LpComponentShape): Boolean = true
        }
        LpScopedSolver(state, componentSplit = true, componentAdmission = admission).use { owner ->
            assertEquals(3, assertNotNull(assertNotNull(owner.solve()).float).blocks)

            assertTrue(owner.append(LpScopedRow(12L,
                listOf(0 to ExactLpNumber.of(-1L), 4 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-3L),
                ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))))), false))
            val result = assertNotNull(owner.solve())

            assertEquals(BigFraction.ofLong(8L), result.lowerBound)
            assertEquals(2, assertNotNull(result.float).blocks)
            assertEquals(1, owner.lastMetrics.componentReuses)
        }
    }

    @Test
    fun `coarsened owners should preserve exact aggregation with interleaved coordinates`() {
        val builder = LpBuilder()
        repeat(16) { builder.addVar(0L, 5L, cost = 1L) }
        builder.addVar(0L, 4L, cost = -3L)
        repeat(8) { builder.addRow(intArrayOf(it, it + 8), longArrayOf(1L, 1L), Relation.GE, 1L) }
        builder.addRow(intArrayOf(), longArrayOf(), Relation.GE, 0L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val admission = object : LpComponentAdmission {
            override val ownerMergeWork = 19L
            override fun accepts(shape: LpComponentShape): Boolean = true
        }

        LpScopedSolver(state, componentSplit = true, componentAdmission = admission).use { owner ->
            val result = assertNotNull(owner.solve())

            assertEquals(BigFraction.ofLong(-4L), result.lowerBound)
            assertEquals(3, assertNotNull(result.float).blocks)
        }
    }

    @Test
    fun `mixed scalar and local row replacements should copy the scalar basis once`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        LpScopedSolver(state, componentSplit = true).use { owner ->
            assertNotNull(owner.solve())
            val before = owner.metrics.preparationRefactorizations
            val zero = ExactLpNumber.of(0L)

            assertTrue(owner.replaceRows(emptySet(), emptyList(), listOf(
                LpScopedRow(3L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-3L),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            ), false, assertions = listOf(
                LpBoundAssertion(1, false, ExactLpSide(ExactLpNumber.of(4L)), 1L, 0),
            )))
            val result = assertNotNull(owner.solve())

            assertEquals(BigFraction.ofLong(15L) * BigFraction.ofLong(2L).reciprocal(), result.lowerBound)
            assertEquals(1L, owner.metrics.preparationRefactorizations - before)
            assertEquals(1L, owner.metrics.componentScalarCopies)
            assertEquals(1, owner.lastMetrics.componentReuses)
        }
    }

    @Test
    fun `mixed edits should preserve delegated factory constructors`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        var constructions = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int,
                iterationLimit: Int, workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                constructions++
                return ProductionLpEngineFactory.newPersistentSolver(model, cancellation, refactorUpdateLimit,
                    iterationLimit, workLimit, trackDegeneracy, pricing)
            }
        }
        LpScopedSolver(state, context = LpSolveContext(factory), componentSplit = true).use { owner ->
            assertNotNull(owner.solve())
            val zero = ExactLpNumber.of(0L)

            assertTrue(owner.replaceRows(emptySet(), emptyList(), listOf(
                LpScopedRow(3L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-3L),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            ), false, assertions = listOf(
                LpBoundAssertion(1, false, ExactLpSide(ExactLpNumber.of(4L)), 1L, 0),
            )))

            assertEquals(5, constructions)
            assertEquals(BigFraction.ofLong(15L) * BigFraction.ofLong(2L).reciprocal(),
                assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `a partially reported initial collection should retain its full construction charge`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        var children = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int,
                iterationLimit: Int, workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val child = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                if (++children == 1) return child
                return object : PersistentLpSolver by child {
                    override fun prepareLogicals(token: ExecutionControl): Basis? = error("preparation failed")
                }
            }
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        LpScopedSolver(state, context = LpSolveContext(factory), componentSplit = true).use { owner ->
            assertFailsWith<IllegalStateException> { owner.prepare() }

            assertEquals(2, children)
            assertEquals(0L, owner.metrics.reservedConstructionWork)
            assertEquals(0L, owner.metrics.constructionWork)
            assertTrue(owner.metrics.measuredPreparationWork > 0L)
        }
    }

    @Test
    fun `child preparation should preserve its primary when reporting also fails`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val primary = IllegalArgumentException("preparation failed")
        val report = IllegalStateException("report failed")
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int,
                iterationLimit: Int, workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val child = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by child {
                    override fun prepareLogicals(token: ExecutionControl): Basis? = throw primary
                    override val lastMetrics: LpSolveMetrics get() = throw report
                }
            }
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        LpScopedSolver(state, context = LpSolveContext(factory), componentSplit = true).use { owner ->
            val failure = assertFailsWith<IllegalArgumentException> { owner.prepare() }

            assertSame(primary, failure)
            assertTrue(failure.suppressedExceptions.contains(report))
        }
    }

    @Test
    fun `cancellation after exact conflict certification should withhold the result`() {
        var cancelled = false
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
            override fun observePhase(metrics: LpPhaseMetrics) {
                cancelled = true
            }
        }
        val huge = ExactLpNumber.of(BigFraction.of(BIG_ONE shl 2048, BIG_ONE))
        LpScopedSolver(LpExactState(scopedLowerBoundModel()), ExecutionControl { cancelled }).use { owner ->
            assertTrue(owner.assertBound(0, false, ExactLpSide(huge), 17L))

            val result = owner.solve(observer = observer)

            assertNull(result)
            assertNull(owner.lastResult)
        }
    }

    @Test
    fun `unprojectable bound conflicts should retain their witnesses and retract`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0, 1) }.build(Sense.MINIMIZE).authoritativeModel())
        val huge = ExactLpNumber.of(BigFraction.of(BIG_ONE shl 2048, BIG_ONE))
        LpScopedSolver(LpExactState(source)).use { owner ->
            assertNotNull(owner.solve())
            assertTrue(owner.push())

            assertTrue(owner.assertBound(0, false, ExactLpSide(huge), 17L))
            val result = assertNotNull(owner.solve())

            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            val support = assertNotNull(result.conflictSupport)
            assertSame(owner.state, support.state)
            assertEquals(listOf(17L, -2L), support.sides.map { it.witness })
            assertNull(owner.state.toWorkingModel())
            assertTrue(owner.pop(0))
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, assertNotNull(owner.solve()).verdict)
        }
    }

    @Test
    fun `unprojectable assertions should remain available for a later contradictory batch`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(listOf(emptyList()), emptyList(), listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(), ExactLpObjective(listOf(zero)))
        val huge = ExactLpNumber.of(BigFraction.of(BIG_ONE shl 2048, BIG_ONE))
        LpScopedSolver(LpExactState(source)).use { owner ->
            assertNotNull(owner.solve())
            assertTrue(owner.push())
            assertTrue(owner.assertBound(0, false, ExactLpSide(huge), 17L))
            assertNull(owner.state.conflict)

            val applied = owner.assertBounds(listOf(
                LpBoundAssertion(0, true, ExactLpSide(zero), 18L, 1),
            ))

            assertEquals(1, applied.count)
            val result = assertNotNull(owner.solve())
            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            assertEquals(listOf(17L, 18L), assertNotNull(result.conflictSupport).sides.map { it.witness })
            assertTrue(owner.pop(0))
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, assertNotNull(owner.solve()).verdict)
        }
    }

    @Test
    fun `spent construction funding should not repeat declined preparation`() {
        val builder = LpBuilder()
        val column = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val state = LpExactState(source)
        assertNotNull(state.ownerWorkingModel())
        LpScopedSolver(state).use { owner ->
            assertFalse(owner.prepare(ExecutionControl.Never, 1L))
            val paid = owner.metrics.preparationWork

            val ready = owner.prepare(ExecutionControl.Never, 0L)

            assertFalse(ready)
            assertTrue(paid > 0L)
            assertEquals(paid, owner.metrics.preparationWork)
        }
    }

    @Test
    fun `retained queries can finish after an earlier invocation token expires`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        for (components in listOf(false, true)) {
            var expired = false
            val firstToken = ExecutionControl { expired }
            LpScopedSolver(LpExactState(source), componentSplit = components).use { owner ->
                val first = owner.solveFloat(token = firstToken, allowance = LpFloatAllowance(1L, 0))
                assertFalse(first?.second?.optimal == true)
                expired = true

                val completed = assertNotNull(owner.solveFloat(token = ExecutionControl.Never))

                val point = assertNotNull(completed.second)
                assertTrue(point.optimal)
                assertEquals(1.0, point.objective)
            }
        }
    }

    @Test
    fun `declined component scans retain their preparation cost`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 5L, cost = 1L)
        val y = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(x, y), longArrayOf(1L, -1L), Relation.LE, 2L)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val costs = listOf(false, true).map { split ->
            LpScopedSolver(LpExactState(source), componentSplit = split).use { owner ->
                assertTrue(owner.prepare())
                owner.metrics.preparationWork
            }
        }

        assertTrue(costs[1] > costs[0])
    }

    @Test
    fun `initial prepared row revisions should preserve separable component admission`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val trail = LpBoundTrail(source)
        assertTrue(trail.append(LpScopedRow(2L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-2L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))))), false))
        LpScopedSolver(trail.state, componentSplit = true).use { owner ->
            val result = assertNotNull(owner.solve())

            assertEquals(2, assertNotNull(result.float).blocks)
            assertEquals(BigFraction.ofLong(3L), result.lowerBound)
        }
    }

    @Test
    fun `component owners should share the containing basis storage limit`() {
        for (admission in listOf(null, object : LpComponentAdmission {
            override fun accepts(shape: LpComponentShape): Boolean = true
            override val basisEntryLimit = 32L
        })) {
            for (rowOnly in listOf(false, true)) {
                val builder = LpBuilder()
                repeat(2) {
                    val column = builder.addVar(0L, 5L, cost = 1L)
                    builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
                }
                if (rowOnly) builder.addRow(intArrayOf(), longArrayOf(), Relation.LE, 0L)
                val limits = ArrayList<Long>()
                val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                    override fun newPersistentSolver(
                        model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int,
                        iterationLimit: Int, workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
                    ): PersistentLpSolver {
                        limits += pricing.basisEntryLimit
                        return ProductionLpEngineFactory.newPersistentSolver(
                            model, cancellation, refactorUpdateLimit, iterationLimit, workLimit,
                            trackDegeneracy, pricing,
                        )
                    }
                }
                val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
                LpScopedSolver(LpExactState(source), context = LpSolveContext(factory),
                    pricing = LpPricingOptions(basisEntryLimit = 64L), componentSplit = true,
                    componentAdmission = admission).use { owner ->
                    assertTrue(owner.prepare())
                }

                assertEquals(if (rowOnly) 3 else 2, limits.size)
                assertTrue(limits.all { it > 0L })
                assertTrue(limits.sum() <= (admission?.basisEntryLimit ?: 64L))
            }
        }
    }

    @Test
    fun `a local cut should replace only its component and retract with its scope`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val factory = RecordingLpEngineFactory()
        LpScopedSolver(LpExactState(source), context = LpSolveContext(factory), componentSplit = true).use { owner ->
            assertNotNull(owner.solve())
            assertTrue(owner.push())
            val zero = ExactLpNumber.of(0L)
            assertTrue(owner.append(LpScopedRow(2L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-3L),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))), true))

            val result = assertNotNull(owner.solve())

            assertEquals(BigFraction.ofLong(7L) * BigFraction.ofLong(2L).reciprocal(), result.lowerBound)
            assertEquals(2, assertNotNull(result.float).blocks)
            assertEquals(1, owner.lastMetrics.componentSolves)
            assertEquals(1, owner.lastMetrics.componentReuses)
            assertEquals(3, factory.calls.count { it.kind == EngineConstruction.PERSISTENT })
            assertTrue(owner.pop(0))
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `new structural columns should allow later reuse of unchanged components`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        LpScopedSolver(LpExactState(source), componentSplit = true).use { owner ->
            assertNotNull(owner.solve())
            val zero = ExactLpNumber.of(0L)
            val one = ExactLpNumber.of(1L)
            val column = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(5L))))
            assertTrue(owner.append(listOf(LpStructuralColumn(column, one)), listOf(
                LpScopedRow(3L, listOf(0 to ExactLpNumber.of(-1L), 3 to ExactLpNumber.of(-1L)),
                    ExactLpNumber.of(-3L), ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            ), false))
            val changed = assertNotNull(owner.solve())
            assertEquals(3, assertNotNull(changed.float).blocks)
            assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(4L)), 1L))

            val result = assertNotNull(owner.solve())

            assertEquals(BigFraction.ofLong(5L), result.lowerBound)
            assertEquals(2, owner.lastMetrics.componentReuses)
        }
    }

    @Test
    fun `joining two blocks should retain the other child`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val factory = RecordingLpEngineFactory()
        LpScopedSolver(LpExactState(source), context = LpSolveContext(factory), componentSplit = true).use { owner ->
            assertNotNull(owner.solve())
            val zero = ExactLpNumber.of(0L)
            val minus = ExactLpNumber.of(-1L)

            assertTrue(owner.append(LpScopedRow(3L, listOf(0 to minus, 1 to minus), ExactLpNumber.of(-3L),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))), false))
            assertEquals(1L, owner.metrics.componentJoinStages)
            val result = assertNotNull(owner.solve())

            assertEquals(BigFraction.ofLong(7L) * BigFraction.ofLong(2L).reciprocal(), result.lowerBound)
            assertEquals(1, owner.lastMetrics.componentReuses)
            assertEquals(1, owner.lastMetrics.componentSolves)
            assertEquals(4, factory.calls.count { it.kind == EngineConstruction.PERSISTENT })
        }
    }

    @Test
    fun `a cut joining all blocks should use an ordinary owner`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        LpScopedSolver(LpExactState(source), componentSplit = true).use { owner ->
            assertNotNull(owner.solve())
            val zero = ExactLpNumber.of(0L)
            val minus = ExactLpNumber.of(-1L)

            assertTrue(owner.append(LpScopedRow(2L, listOf(0 to minus, 1 to minus), ExactLpNumber.of(-3L),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))), false))
            val result = assertNotNull(owner.solve())

            assertEquals(BigFraction.ofLong(3L), result.lowerBound)
            assertEquals(1, assertNotNull(result.float).blocks)
        }
    }

    @Test
    fun `a component conflict should retain its guarded row and active bound premise`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 5L)
        val y = builder.addVar(0L, 5L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 3L, global = false,
            premises = LpRowPremises(intArrayOf(), booleanArrayOf(), longArrayOf(), intArrayOf(7)))
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        LpScopedSolver(LpExactState(source), componentSplit = true).use { owner ->
            assertTrue(owner.assertBound(y, true, ExactLpSide(ExactLpNumber.of(2L)), 8L))

            val result = assertNotNull(owner.solve())

            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            val support = assertNotNull(result.conflictSupport)
            assertSame(owner.state, support.state)
            val premises = assertNotNull(support.rows.single { it.first == 1 }.second.premises)
            assertEquals(listOf(7), premises.literalEntries())
            assertEquals(8L, support.sides.single { it.column == y && it.upper }.witness)
        }
    }

    @Test
    fun `component ownership should retain exact node bounds across a sibling edit`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        LpScopedSolver(LpExactState(source), componentSplit = true).use { owner ->
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.push())
            assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 7L))

            val changed = assertNotNull(owner.solve())

            assertEquals(BigFraction.ofLong(7L) * BigFraction.ofLong(2L).reciprocal(), changed.lowerBound)
            assertEquals(2, assertNotNull(changed.float).blocks)
            assertEquals(1L, owner.metrics.createdOwners)
            assertTrue(owner.pop(0))
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `new columns rows and bound assertions should publish in one preparation`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val column = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(5L))), integral = true)
        val row = LpScopedRow(
            0L,
            listOf(0 to ExactLpNumber.of(-1L)),
            ExactLpNumber.of(-1L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
        )
        val empty = ExactLpModel(emptyList(), emptyList(), emptyList(), emptyList(), ExactLpObjective(emptyList()))
        LpScopedSolver(LpExactState(empty)).use { owner ->
            assertTrue(owner.push())

            assertTrue(
                owner.replaceRows(
                    emptySet(),
                    listOf(LpStructuralColumn(column, one)),
                    listOf(row),
                    true,
                    assertions = listOf(LpBoundAssertion(0, false, ExactLpSide(ExactLpNumber.of(3L)), 0L, 1)),
                ),
            )

            assertEquals(BigFraction.ofLong(3L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(1L, owner.metrics.preparationAttempts)
            assertTrue(owner.pop(0))
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
            assertEquals(1L, owner.metrics.createdOwners)
        }
    }

    @Test
    fun `an invalid bound in a structural batch should preserve the original authority`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val row = LpScopedRow(
            1L,
            listOf(0 to ExactLpNumber.of(-1L)),
            ExactLpNumber.of(-2L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))),
        )
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.push())
            val before = owner.state
            val preparations = owner.metrics.preparationAttempts

            assertFalse(
                owner.replaceRows(
                    setOf(0L),
                    emptyList(),
                    listOf(row),
                    true,
                    assertions = listOf(LpBoundAssertion(2, false, ExactLpSide(ExactLpNumber.of(2L)), 0L, 0)),
                ),
            )

            assertSame(before, owner.state)
            assertEquals(preparations, owner.metrics.preparationAttempts)
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `mixed row lifetimes should prepare once and keep a permanent definition through pop`() {
        val source = LpBuilder().apply { addVar(0L, 3L, cost = 1L) }.build(Sense.MINIMIZE)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))))
        val rows = listOf(
            LpScopedRow(0L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-1L), logical),
            LpScopedRow(1L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-2L), logical),
        )
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.push())

            assertTrue(owner.replaceRows(emptySet(), emptyList(), rows, true, permanentRows = setOf(0L)))

            assertEquals(BigFraction.ofLong(2L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(2L, owner.metrics.createdOwners)
            assertTrue(owner.pop(0))
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.state.rows.row(0).active)
            assertFalse(owner.state.rows.row(1).active)
            assertTrue(owner.compact())
            assertEquals(1, owner.state.model.m)
        }
    }

    @Test
    fun `a source row replacement should publish one structural edit and restore the parent on pop`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val zero = ExactLpNumber.of(0L)
        val row = LpScopedRow(
            1,
            listOf(0 to ExactLpNumber.of(-1L)),
            ExactLpNumber.of(-2L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
        )
        LpScopedSolver(LpExactState(assertNotNull(source.authoritativeModel()))).use { owner ->
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.push())

            assertTrue(owner.replaceRows(setOf(0), emptyList(), listOf(row), true))

            assertEquals(BigFraction.ofLong(2L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(2L, owner.metrics.createdOwners)
            assertTrue(owner.pop(0))
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `failed adoption should not reuse a prior work stop`() {
        val state = LpExactState(scopedLowerBoundModel())
        var reject = false
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: ExecutionControl,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: ExecutionControl): Boolean =
                        !reject && delegate.adopt(state, token)
                }
            }
        }
        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
            owner.withWorkingModel(LpWorkingModel.overrides(state)) { scope ->
                val first = assertNotNull(scope.solveFloat(allowance = LpFloatAllowance(1L, 100)))
                assertNull(first.second)
                assertEquals(LpFloatTermination.WORK, scope.lastFloatTermination)
                val spent = scope.metrics.solves
                reject = true

                assertNull(scope.solveFloat())

                assertNull(scope.lastFloatTermination)
                assertEquals(spent, scope.metrics.solves)
            }
        }
    }

    @Test
    fun `working scopes and bound pops should restore the source objective`() {
        val x = 0
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))),
                listOf(ExactLpNumber.of(2L)),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(3L)))),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(one, zero)),
            ),
        )
        LpScopedSolver(state).use { owner ->
            assertTrue(owner.push())
            assertTrue(owner.assertBound(x, false, ExactLpSide(one), 42L))
            val temporary = LpWorkingModel.overrides(
                owner.state,
                ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero)),
            )
            owner.withWorkingModel(temporary) { scope ->
                assertEquals(BigFraction.ofLong(-2L), assertNotNull(scope.solve()).lowerBound)
            }
            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.pop(0))

            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `exact source validation should use shifted coordinates logical costs and minimized scale`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(-1L)))),
            listOf(ExactLpNumber.of(-1L)),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))),
                    origin = ExactLpNumber.of(1L),
                ),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(
                listOf(ExactLpNumber.of(2L), ExactLpNumber.of(3L)),
                constant = ExactLpNumber.of(4L),
                scale = ExactLpNumber.of(2L),
                externalConstant = ExactLpNumber.of(5L),
            ),
        )
        LpScopedSolver(LpExactState(model)).use { solver ->
            val result = assertNotNull(solver.solve())

            IndependentExactSourceValidator.validate(solver.state, result)

            assertEquals(listOf(BigFraction.ofLong(2L)), result.exactPrimal)
            assertEquals(BigFraction.ofLong(8L), assertNotNull(result.witness).objective)
        }
    }

    @Test
    fun `a published replacement should stay accepted and clear solve metrics when old owner cleanup throws`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))),
            listOf(minusOne),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        val cleanup = IllegalStateException("old owner cleanup failure")
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: ExecutionControl,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override fun close() {
                        delegate.close()
                        if (model.m == 1) throw cleanup
                    }
                }
            }
        }
        LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { solver ->
            assertNotNull(solver.solve())
            assertTrue(solver.lastMetrics.workOps > 0L)
            val row = LpScopedRow(2, listOf(0 to one), one, ExactLpColumn(ExactLpBounds()))

            val failure = assertFailsWith<IllegalStateException> { solver.append(row, false) }

            assertSame(cleanup, failure)
            assertEquals(listOf(0L, 2L), solver.state.rows.entries().map { it.id })
            assertNull(solver.lastResult)
            assertEquals(LpSolveMetrics(), solver.lastMetrics)
            assertEquals(1L, solver.metrics.editSuccesses)
            assertEquals(BigFraction.ONE, assertNotNull(solver.solve()).lowerBound)
        }
    }

    @Test
    fun `an appended row conflict should cancel structural coefficients and preserve guarded proof after pop`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(
                solver.append(
                    LpScopedRow(
                        2,
                        listOf(0 to one),
                        zero,
                        logical,
                        ExactLpRow(false, premises = ExactLpPremises(emptyList(), listOf(41))),
                    ),
                    false,
                ),
            )
            assertTrue(solver.push())
            assertTrue(
                solver.append(
                    LpScopedRow(
                        3,
                        listOf(0 to minusOne),
                        minusOne,
                        logical,
                        ExactLpRow(false, premises = ExactLpPremises(emptyList(), listOf(42))),
                    ),
                    true,
                ),
            )

            val result = assertNotNull(solver.solve())

            assertTrue(solver.pop(0))
            assertTrue(solver.compact())

            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
            val proof = assertNotNull(result.rationalConflict)
            val support = assertNotNull(result.conflictSupport)
            assertEquals(setOf(0, 1), proof.rows.toSet())
            assertEquals(
                setOf(41, 42),
                support.rows.map { assertNotNull(it.second.premises).literalEntries().single() }.toSet(),
            )
            assertEquals(listOf(2L, 3L), support.state.rows.entries().map { it.id })
            assertTrue(checkedLpConflict(assertNotNull(support.state.toWorkingModel()), proof))
        }
    }

    @Test
    fun `explicit deactivation of strict sides should permit the exact boundary witness`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(
                solver.append(
                    LpScopedRow(
                        4,
                        listOf(0 to one),
                        one,
                        ExactLpColumn(ExactLpBounds(upper = ExactLpSide(one, strict = true))),
                    ),
                    scoped = false,
                ),
            )
            assertEquals(LpVerdict.FEASIBLE, assertNotNull(solver.solve()).verdict)

            assertTrue(solver.deactivate(4))
            val result = assertNotNull(solver.solve())

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertEquals(listOf(BigFraction.ZERO), result.exactPrimal)
            assertTrue(assertNotNull(result.bound?.support).rows.isEmpty())
        }
    }

    @Test
    fun `a logical objective should be explicitly removed before row lifetime ends`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val bounds = ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(bounds)),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        LpScopedSolver(LpExactState(source)).use { solver ->
            assertTrue(solver.push())
            assertTrue(solver.append(LpScopedRow(1, listOf(0 to one), one, ExactLpColumn(bounds), cost = one), true))
            val priced = assertNotNull(solver.solve())
            assertEquals(listOf(BigFraction.ONE), priced.exactPrimal)
            assertEquals(BigFraction.ZERO, priced.lowerBound)
            val before = solver.state
            assertFalse(solver.pop(0))
            assertSame(before, solver.state)
            assertSame(priced, solver.lastResult)

            assertTrue(solver.replaceObjective(ExactLpObjective(listOf(one, zero))))
            assertTrue(solver.pop(0))
            assertTrue(solver.compact())

            val result = assertNotNull(solver.solve())
            assertEquals(listOf(BigFraction.ZERO), result.exactPrimal)
            assertEquals(BigFraction.ZERO, result.lowerBound)
            assertEquals(1L, solver.metrics.editDeclines)
        }
    }

    @Test
    fun `failed append preparation should close staged owners and preserve the current solve`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        for (failure in listOf("singular", "arithmetic", "unexpected")) {
            var fail = false
            var closes = 0
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel,
                    cancellation: ExecutionControl,
                    refactorUpdateLimit: Int,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = RevisedSimplex(
                        model,
                        cancellation,
                        pricing = pricing,
                        basisSolverFactory = { matrix ->
                            val factors = KotlinBasisSolver(matrix)
                            object : BasisSolver by factors {
                                private fun singular(): Boolean {
                                    if (!fail) return false
                                    when (failure) {
                                        "arithmetic" -> throw BasisArithmeticException("injected preparation")
                                        "unexpected" -> error("injected preparation")
                                    }
                                    return true
                                }

                                override fun refactorize(basicIndex: IntArray): Boolean =
                                    !singular() && factors.refactorize(basicIndex)

                                override fun refactorizeRepairing(
                                    basicIndex: IntArray,
                                    control: BasisRepairControl,
                                ): BasisRepair? =
                                    if (singular()) null else factors.refactorizeRepairing(basicIndex, control)
                            }
                        },
                    )
                    return object : PersistentLpSolver by delegate {
                        override fun close() {
                            closes++
                            delegate.close()
                        }
                    }
                }
            }
            LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { solver ->
                val original = assertNotNull(solver.solve())
                val before = solver.state
                fail = true
                val row = LpScopedRow(1, listOf(0 to one), zero, ExactLpColumn(ExactLpBounds()))
                if (failure == "unexpected") {
                    assertFailsWith<IllegalStateException> { solver.append(row, true) }
                } else {
                    assertFalse(solver.append(row, true))
                }
                assertSame(before, solver.state)
                assertSame(original, solver.lastResult)
                assertEquals(1L, solver.metrics.preparationDeclines)
                assertEquals(1, closes)
            }
        }
    }

    @Test
    fun `failed compaction staging or reduced build should preserve tombstones and the live owner`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        for (failureOffset in listOf(1, 2)) {
            var builds = 0
            var failAt = -1
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel,
                    cancellation: ExecutionControl,
                    refactorUpdateLimit: Int,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                    return object : PersistentLpSolver by delegate {
                        override fun prepareLogicals(token: ExecutionControl): Basis? =
                            if (++builds == failAt) null else delegate.prepareLogicals(token)
                    }
                }
            }
            LpScopedSolver(LpExactState(source), context = LpSolveContext(engineFactory = factory)).use { solver ->
                assertNotNull(solver.solve())
                assertTrue(solver.deactivate(0))
                val before = solver.state
                failAt = builds + failureOffset

                assertFalse(solver.compact())

                assertSame(before, solver.state)
                assertEquals(1L, solver.metrics.currentOwners)
                assertEquals(1, solver.metrics.retainedRows)
                assertEquals(0, solver.metrics.activeRows)
                assertEquals(1L, solver.metrics.preparationDeclines)
                assertEquals(listOf(BigFraction.ZERO), assertNotNull(solver.solve()).exactPrimal)
                failAt = -1
                assertTrue(solver.compact())
                assertEquals(0, solver.state.model.m)
            }
        }
    }

    @Test
    fun `resource and adoption declines should be atomic and cancelled solves should withhold committed assertions`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        var reject = false
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: ExecutionControl,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: ExecutionControl): Boolean =
                        !reject && delegate.adopt(state, token)
                }
            }
        }
        LpScopedSolver(
            LpExactState(source),
            context = LpSolveContext(engineFactory = factory),
            maxRetainedRows = 0,
        ).use { solver ->
            val original = assertNotNull(solver.solve())
            val before = solver.state
            assertFalse(solver.append(LpScopedRow(0, emptyList(), zero, ExactLpColumn(ExactLpBounds())), false))
            assertSame(before, solver.state)
            reject = true
            assertFalse(solver.assertBound(0, false, ExactLpSide(one), 1))
            assertSame(before, solver.state)
            assertSame(original, solver.lastResult)
            reject = false
            assertTrue(solver.assertBound(0, false, ExactLpSide(one), 1))

            assertNull(solver.solve(token = ExecutionControl { true }))

            assertNull(solver.lastResult)
            assertEquals(1L, solver.state.assertions.single().witness)
            assertEquals(listOf(BigFraction.ONE), assertNotNull(solver.solve()).exactPrimal)
            assertEquals(2L, solver.metrics.editDeclines)
        }
    }

    @Test
    fun `projection should exhaust the setup budget before allocating a numerical owner`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        LpScopedSolver(LpExactState(source), workLimit = source.keySize + 2L).use { owner ->
            assertNull(owner.solve())

            assertEquals(0L, owner.metrics.createdOwners)
            assertTrue(owner.metrics.preparationWork > 0L)
        }
    }

    @Test
    fun `a warm hint with an explicit allowance should be rejected before preparation`() {
        val builder = LpBuilder()
        builder.addVar(0, 1)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        LpScopedSolver(state).use { owner ->
            assertFailsWith<IllegalArgumentException> {
                owner.solveFloat(Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)), allowance = LpFloatAllowance(1L, 1))
            }

            assertSame(state, owner.state)
            assertEquals(0L, owner.metrics.createdOwners)
            assertEquals(LpSolveMetrics(), owner.lastMetrics)
        }
    }
}

private object IndependentExactSourceValidator {
    fun validate(state: LpExactState, result: CertifiedLpResult) {
        val primal = assertNotNull(result.exactPrimal)
        assertEquals(state.model.n, primal.size)
        val shifted = List(state.model.n) { column ->
            primal[column] - state.model.column(column).origin.value
        }
        for (column in shifted.indices) validateBounds(shifted[column], state.model.column(column).bounds)
        val logicals = MutableList(state.model.m) { BigFraction.ZERO }
        for (row in 0 until state.model.m) {
            var logical = state.model.rhs(row).value
            for (column in 0 until state.model.n) {
                val coefficient = state.model.entries(column).firstOrNull { it.row == row }?.number?.value
                    ?: BigFraction.ZERO
                logical -= coefficient * shifted[column]
            }
            validateBounds(logical, state.model.column(state.model.n + row).bounds)
            logicals[row] = logical
        }
        val coordinates = shifted + logicals
        var objective = state.model.objective.constant.value
        for (column in coordinates.indices) {
            objective += state.model.objective.cost(column).value * coordinates[column]
        }
        objective = objective * state.model.objective.scale.value.reciprocal() +
            state.model.objective.externalConstant.value
        assertEquals(objective, assertNotNull(result.witness).objective)
    }

    private fun validateBounds(value: BigFraction, bounds: ExactLpBounds) {
        bounds.lower?.let { side ->
            if (side.strict) assertTrue(value > side.number.value) else assertTrue(value >= side.number.value)
        }
        bounds.upper?.let { side ->
            if (side.strict) assertTrue(value < side.number.value) else assertTrue(value <= side.number.value)
        }
    }
}

internal fun scopedLowerBoundModel(): ExactLpModel {
    val zero = ExactLpNumber.of(0L)
    val one = ExactLpNumber.of(1L)
    val minusOne = ExactLpNumber.of(-1L)
    return ExactLpModel(
        listOf(listOf(ExactLpEntry(0, minusOne))),
        listOf(minusOne),
        listOf(
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
        ),
        listOf(ExactLpRow()),
        ExactLpObjective(listOf(one, zero)),
    )
}
