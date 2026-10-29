package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisRepair
import com.eignex.klause.simplex.basis.BasisRepairControl
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
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
    fun `promoting a pivot stopped query should preserve its paid numerical path`() {
        val expected = LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
            assertNotNull(owner.solveFloat()).second to owner.lastMetrics
        }
        LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
            val first = owner.advanceRootGuidance(100_000L, LpFloatAllowance(100_000L, 1), ReconstructionLimits())
            val paid = owner.lastMetrics

            assertFalse(first.paused)
            assertTrue(owner.floatFundingStopped)
            assertEquals(LpFloatTermination.PIVOTS, first.termination)
            assertNull(first.primal)
            assertNull(first.bound)
            val resumed = assertNotNull(owner.advanceFloat(100_000L, LpFloatAllowance(200_000L, 100),
                retainFundingStop = true)).second
            val total = paid + owner.lastMetrics

            assertEquals(assertNotNull(expected.first).objective, assertNotNull(resumed).objective)
            assertEquals(expected.second.workOps, total.workOps)
            assertEquals(expected.second.pivots, total.pivots)
            assertEquals(1L, owner.metrics.createdOwners)
            assertEquals(0L, owner.metrics.closedOwners)
        }
    }

    @Test
    fun `owner cancellation during root certification should discard guidance`() {
        var cancelled = false
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                cancelled = true
            }
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        }
        LpScopedSolver(LpExactState(lowerBoundModel()), Cancellation { cancelled }).use { owner ->
            val guide = owner.advanceRootGuidance(100_000L, LpFloatAllowance(100_000L, 100), ReconstructionLimits(),
                observer = observer)

            assertNull(guide.primal)
            assertNull(guide.bound)
            assertEquals(LpRootGuidancePhase.CERTIFICATE, guide.phase)
        }
    }

    @Test
    fun `root guidance should retain heuristic coordinates when bound certification is exhausted`() {
        LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
            val guide = owner.advanceRootGuidance(100_000L, LpFloatAllowance(100_000L, 100),
                ReconstructionLimits(maxWork = 0L))

            assertEquals(1.0, assertNotNull(guide.primal).single())
            assertNull(guide.bound)
            assertEquals(LpRootGuidancePhase.CERTIFICATE, guide.phase)
            assertEquals(ReconstructionDecline.WORK, guide.certificateMetrics?.decline)
            assertNull(owner.lastResult)
        }
    }

    @Test
    fun `paused root guidance should publish neither coordinates nor a bound`() {
        LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
            val guide = owner.advanceRootGuidance(1L, LpFloatAllowance(100_000L, 100), ReconstructionLimits())

            assertTrue(guide.paused)
            assertEquals(LpRootGuidancePhase.FLOAT, guide.phase)
            assertNull(guide.primal)
            assertNull(guide.bound)
            assertNull(guide.certificateMetrics)
        }
    }

    @Test
    fun `root guidance should inherit its owners proof policy`() {
        val context = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })
        LpScopedSolver(LpExactState(lowerBoundModel()), context = context).use { owner ->
            val guide = owner.advanceRootGuidance(100_000L, LpFloatAllowance(100_000L, 100), ReconstructionLimits())

            assertNotNull(guide.primal)
            assertNull(guide.bound)
            assertEquals(LpRootGuidancePhase.CERTIFICATE, guide.phase)
        }
    }

    @Test
    fun `checkpointed floating work should match uninterrupted work without publishing a paused result`() {
        val model = lowerBoundModel()
        val expected = LpScopedSolver(LpExactState(model)).use { owner ->
            assertNotNull(owner.solveFloat()).second to owner.lastMetrics
        }
        LpScopedSolver(LpExactState(model)).use { owner ->
            val allowance = LpFloatAllowance(100_000L, 100)
            var work = LpSolveMetrics()

            assertNull(owner.advanceFloat(1L, allowance))
            work += owner.lastMetrics
            assertTrue(owner.floatPaused)
            assertNull(owner.lastResult)
            assertNull(owner.lastFloatTermination)
            var result: FloatLpResult? = null
            var attempts = 0
            while (result == null && attempts++ < 64) {
                result = owner.advanceFloat(1L, allowance)?.second
                work += owner.lastMetrics
            }

            assertEquals(assertNotNull(expected.first).objective, assertNotNull(result).objective)
            assertEquals(expected.second.workOps, work.workOps)
            assertEquals(expected.second.pivots, work.pivots)
            assertFalse(owner.floatPaused)
            assertEquals(1L, owner.metrics.createdOwners)
        }
    }

    @Test
    fun `fixed lifetime consultations should retain their cursor as remaining work shrinks`() {
        LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))
            val preparation = owner.metrics.preparationWork

            owner.advanceFloat(1L, LpFloatAllowance(50_000L, 100))

            assertEquals(preparation, owner.metrics.preparationWork)
            assertEquals(1L, owner.metrics.createdOwners)
            assertEquals(0L, owner.metrics.closedOwners)
        }
    }

    @Test
    fun `unchanged authority should retain a paused numerical owner`() {
        LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))

            assertTrue(owner.adopt(owner.state))
            assertTrue(owner.assertBounds(emptyList()) is LpBoundBatchResult.Applied)

            assertTrue(owner.floatPaused)
            assertEquals(0L, owner.metrics.closedOwners)
        }
    }

    @Test
    fun `changed bounds should discard paused progress before solving the new authority`() {
        LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))

            assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 1L))
            val result = owner.solve()

            assertFalse(owner.floatPaused)
            assertEquals(BigFraction.ofLong(3L), assertNotNull(result).lowerBound)
            assertEquals(1L, owner.metrics.closedOwners)
            assertEquals(2L, owner.metrics.createdOwners)
        }
    }

    @Test
    fun `changed objectives and rows should discard paused progress before numerical adoption`() {
        for (objectiveEdit in listOf(false, true)) {
            LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
                assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))

                if (objectiveEdit) {
                    assertTrue(owner.replaceObjective(
                        ExactLpObjective(listOf(ExactLpNumber.of(2L), ExactLpNumber.of(0L))),
                    ))
                } else {
                    assertTrue(owner.append(LpScopedRow(1L, listOf(0 to ExactLpNumber.of(-1L)),
                        ExactLpNumber.of(-2L), ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))))), false))
                }
                val result = owner.solve()

                assertFalse(owner.floatPaused)
                assertEquals(BigFraction.ofLong(2L), assertNotNull(result).lowerBound)
                assertEquals(1L, owner.metrics.closedOwners)
                assertEquals(2L, owner.metrics.createdOwners)
            }
        }
    }

    @Test
    fun `global cancellation should retire a paused owner without a result`() {
        var cancelled = false
        val owner = LpScopedSolver(LpExactState(lowerBoundModel()), Cancellation { cancelled })
        owner.use {
            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))
            cancelled = true

            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))

            assertFalse(owner.floatPaused)
            assertNull(owner.lastResult)
            assertEquals(owner.metrics.createdOwners, owner.metrics.closedOwners)
        }
    }

    @Test
    fun `cancellation while preparing an advance should retire the paused owner`() {
        LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))
            var polls = 0
            val token = Cancellation { ++polls >= 2 }

            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100), token))

            assertFalse(owner.floatPaused)
            assertEquals(owner.metrics.createdOwners, owner.metrics.closedOwners)
        }
    }

    @Test
    fun `retraction should discard a paused scoped solve`() {
        for (reset in listOf(false, true)) {
            LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
                assertTrue(owner.push())
                assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 1L))
                assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))

                assertTrue(if (reset) owner.resetRoot() else owner.pop(0))
                val result = owner.solve()

                assertFalse(owner.floatPaused)
                assertEquals(BigFraction.ONE, assertNotNull(result).lowerBound)
            }
        }
    }

    @Test
    fun `checkpoint advances should preserve the cumulative numerical work limit`() {
        LpScopedSolver(LpExactState(lowerBoundModel())).use { owner ->
            val allowance = LpFloatAllowance(1L, 100)
            var attempts = 0

            do {
                owner.advanceFloat(1L, allowance)
            } while (owner.floatPaused && attempts++ < 8)

            assertFalse(owner.floatPaused)
            assertEquals(LpFloatTermination.WORK, owner.lastFloatTermination)
            assertNull(owner.lastResult)
        }
    }

    @Test
    fun `nonresumable owners should honor the smaller consultation and cumulative allowance`() {
        for ((quantum, cumulative) in listOf(1L to 10L, 10L to 1L)) {
            val factory = RecordingLpEngineFactory()
            LpScopedSolver(LpExactState(lowerBoundModel()), context = LpSolveContext(factory)).use { owner ->
                owner.advanceFloat(quantum, LpFloatAllowance(cumulative, 100))

                assertEquals(minOf(quantum, cumulative), assertNotNull(factory.floatAllowances.single()).work)
                assertFalse(owner.floatPaused)
            }
        }
    }

    @Test
    fun `closing a paused owner should release its numerical factors`() {
        val owner = LpScopedSolver(LpExactState(lowerBoundModel()))
        assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))

        owner.close()

        assertFalse(owner.floatPaused)
        assertEquals(owner.metrics.createdOwners, owner.metrics.closedOwners)
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
        val state = LpExactState(lowerBoundModel())
        var reject = false
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
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
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

    private fun lowerBoundModel(): ExactLpModel {
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
                cancellation: Cancellation,
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
                    cancellation: Cancellation,
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
                    cancellation: Cancellation,
                    refactorUpdateLimit: Int,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                    return object : PersistentLpSolver by delegate {
                        override fun prepareLogicals(token: Cancellation): Basis? =
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
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = RevisedSimplex(model, cancellation, pricing = pricing)
                return object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
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

            assertNull(solver.solve(token = Cancellation { true }))

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
