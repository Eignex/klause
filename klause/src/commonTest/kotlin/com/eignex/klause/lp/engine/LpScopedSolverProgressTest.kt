package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpScopedSolverProgressTest {
    @Test
    fun `declined components cannot borrow their preparation grant for a monolithic owner`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        var admissions = 0
        LpScopedSolver(LpExactState(source), workLimit = 100_000L, componentSplit = true,
            componentAdmission = { admissions++; false }, monolithicPreparationWork = 1L).use { owner ->

            repeat(2) { assertFalse(owner.prepare()) }

            assertEquals(1, admissions)
            assertEquals(0L, owner.metrics.createdOwners)
        }
    }

    @Test
    fun `query replay records the admitted numerical owner rather than a declined split request`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        for (admitted in listOf(false, true)) {
            val observed = ArrayList<Boolean>()
            val context = LpSolveContext(onQuery = { observed += it.componentSplit })
            LpScopedSolver(LpExactState(source), context = context,
                componentSplit = true, componentAdmission = { admitted }).use { owner ->

                val solved = assertNotNull(owner.solveFloat())

                assertEquals(admitted, solved.first is ComponentLpSolverCapability)
                assertEquals(listOf(admitted), observed)
            }
        }
    }

    @Test
    fun `floating query capture should retain adopted bounds and numerical limits`() {
        var observed: LpQueryObservation? = null
        var bytes: ByteArray? = null
        val context = LpSolveContext(onQuery = { query ->
            observed = query
            bytes = query.captureExactWithin("scoped", 4096, 65_536, 64, ExecutionControl.Never)
        })
        LpScopedSolver(LpExactState(scopedLowerBoundModel()), context = context,
            refactorUpdateLimit = 7, trackDegeneracy = true, pricing = LpPricingOptions(tieSeed = 43L)).use { owner ->
            assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 17L))

            assertNotNull(owner.solveFloat(allowance = LpFloatAllowance(100_000L, 31))?.second)
        }
        val query = assertNotNull(observed)
        val captured = LpExactCapture.decode(assertNotNull(bytes))
        assertEquals(LpQueryRoute.SCOPED_FLOAT, query.route)
        assertTrue(query.explicitAllowance)
        assertNull(query.claim)
        assertNull(query.refinementLimits)
        assertEquals(43L, captured.settings.seed)
        assertEquals(100_000L, captured.settings.workLimit)
        assertEquals(31, captured.settings.pivotLimit)
        assertEquals(7, captured.settings.refactorUpdateLimit)
        assertTrue(captured.settings.trackDegeneracy)
        assertEquals(BigFraction.ofLong(2L), captured.initialState.activeSide(0, false)?.side?.number?.value)
        assertEquals(17L, captured.initialState.activeSide(0, false)?.witness)
        assertFailsWith<IllegalStateException> { query.captureBasis() }
    }

    @Test
    fun `resuming a floating cursor should preserve one query observation`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val observations = mutableListOf<LpQueryObservation>()
        val context = LpSolveContext(onQuery = { observations += it })
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        LpScopedSolver(LpExactState(source), context = context).use { owner ->
            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100), retainFundingStop = true))
            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100), retainFundingStop = true))

            val query = observations.single()
            assertEquals(LpQueryRoute.SCOPED_CONTINUATION, query.route)
            assertEquals(1L, query.sequence)
            assertEquals(1L, query.initialQuantum)
            assertEquals(100_000L, query.workLimit)
            assertTrue(query.retainFundingStop)
            assertFailsWith<IllegalStateException> { query.model }
        }
    }

    @Test
    fun `child gains are withheld when observation changes the parent`() {
        for (n in listOf(3, 12)) {
            val builder = LpBuilder()
            repeat(n) { builder.addVar(0, 1, cost = if (n == 3) longArrayOf(3L, 2L, 4L)[it] else 38L) }
            repeat(n) { row ->
                if (n == 3) {
                    builder.addRow(intArrayOf(row, (row + 1) % n), longArrayOf(1, 1), Relation.GE, 1L)
                } else {
                    builder.addRow(IntArray(n) { it }, LongArray(n) { if (it == row) 16L else 2L }, Relation.GE, 19L)
                }
            }
            val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            LpScopedSolver(state).use { owner ->
                assertTrue(assertNotNull(owner.solveFloat()?.second).optimal)
                val point = assertNotNull(owner.state.ownerWorkingModel())
                var completed = 0
                var work = 0L
                var seeded = false
                var decline: LpProbeDecline? = null

                val gains = owner.directionalGains(point, intArrayOf(0, 1), longArrayOf(0, 0),
                    1_000_000L, ExecutionControl.Never) {
                    work += it.work
                    completed += it.completed
                    decline = it.decline
                    seeded = seeded || it.numerical?.parentValues == true
                    if (it.completed > 0 && completed == 4) {
                        assertTrue(owner.push())
                        assertTrue(owner.assertBound(0, true, ExactLpSide(ExactLpNumber.of(0L)), 1L))
                    }
                }

                assertNull(gains)
                assertEquals(4, completed)
                assertEquals(n == 12, seeded)
                assertEquals(LpProbeDecline.STALE_POINT, decline)
                assertTrue(work in 1L..1_000_000L)
            }
        }
    }

    @Test
    fun `a producer retired during a seeded query supplies no advice`() {
        val n = 12
        val builder = LpBuilder()
        repeat(n) { builder.addVar(0, 1, cost = 38L) }
        repeat(n) { row ->
            builder.addRow(IntArray(n) { it }, LongArray(n) { if (it == row) 16L else 2L }, Relation.GE, 19L)
        }
        LpScopedSolver(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))).use { owner ->
            assertTrue(assertNotNull(owner.solveFloat()?.second).optimal)
            val point = assertNotNull(owner.state.ownerWorkingModel())
            var seeded = false
            var completed = 0
            var work = 0L
            var decline: LpProbeDecline? = null

            val gains = owner.directionalGains(point, intArrayOf(0), longArrayOf(0), 1_000_000L,
                ExecutionControl.Never) {
                completed += it.completed
                work += it.work
                decline = it.decline
                if (it.numerical?.parentValues == true) {
                    seeded = true
                    owner.close()
                }
            }

            assertNull(gains)
            assertTrue(seeded)
            assertEquals(2, completed)
            assertEquals(LpProbeDecline.OWNER, decline)
            assertTrue(work in 1L..1_000_000L)
        }
    }

    @Test
    fun `unavailable child probes report the guard without constructing a numerical owner`() {
        for (valueMode in listOf(false, true)) {
            for (cancelled in listOf(false, true)) {
                val factory = RecordingLpEngineFactory()
                LpScopedSolver(LpExactState(scopedLowerBoundModel()), context = LpSolveContext(factory)).use { owner ->
                    val point = assertNotNull(owner.state.ownerWorkingModel())
                    var metrics: LpProbeMetrics? = null

                    val source = LpProbeSource(point, Basis(IntArray(0), emptyArray()))
                    val gains = if (valueMode) {
                        owner.directionalValues(source, intArrayOf(0), longArrayOf(0), 1000L,
                            ExecutionControl { cancelled }) { metrics = it }
                    } else owner.directionalGains(point, intArrayOf(0), longArrayOf(0), 1000L,
                        ExecutionControl { cancelled }) { metrics = it }

                    assertNull(gains)
                    assertTrue(factory.calls.isEmpty())
                    val reason = if (cancelled) LpProbeDecline.CANCELLED else LpProbeDecline.OWNER
                    assertEquals(LpProbeMetrics(0L, 0L, 0, reason), metrics)
                }
            }
        }
    }

    @Test
    fun `reading paused dual proposals does not construct a numerical owner`() {
        val factory = RecordingLpEngineFactory()
        LpScopedSolver(LpExactState(scopedLowerBoundModel()), context = LpSolveContext(factory)).use { owner ->

            val proposal = owner.pausedDualProposal(ExecutionControl.Never)

            assertNull(proposal)
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `paused row multipliers admit exact checking without completing the query`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        LpScopedSolver(LpExactState(source)).use { owner ->
            var proposal: DoubleArray? = null
            repeat(8) {
                if (proposal == null) {
                    assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))
                    proposal = owner.pausedDualProposal(ExecutionControl.Never)
                }
            }
            val first = assertNotNull(proposal)
            val paid = owner.lastMetrics

            val checked = certifyLpBoundOnly(assertNotNull(owner.state.ownerWorkingModel()), first,
                ReconstructionLimits())

            val bound = assertNotNull(checked.bound)
            assertTrue(bound.value > BigFraction.ZERO)
            assertTrue(bound.value <= BigFraction.ofLong(3L) * BigFraction.ofLong(2L).reciprocal())
            assertEquals(paid, owner.lastMetrics)
            assertTrue(owner.floatPaused)
            assertNull(owner.lastResult)
        }
    }

    @Test
    fun `paused dual proposals own their row buffers`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        LpScopedSolver(LpExactState(source)).use { owner ->
            var proposal: DoubleArray? = null
            repeat(8) {
                if (proposal == null) {
                    assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))
                    proposal = owner.pausedDualProposal(ExecutionControl.Never)
                }
            }
            val first = assertNotNull(proposal)
            val expected = first.toList()
            first.fill(0.0)

            val next = assertNotNull(owner.pausedDualProposal(ExecutionControl.Never))

            assertEquals(expected, next.toList())
        }
    }

    @Test
    fun `changed bounds retire paused dual proposals`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        LpScopedSolver(LpExactState(source)).use { owner ->
            var proposal: DoubleArray? = null
            repeat(8) {
                if (proposal == null) {
                    assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))
                    proposal = owner.pausedDualProposal(ExecutionControl.Never)
                }
            }
            assertNotNull(proposal)

            assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(1L)), 7L))

            assertNull(owner.pausedDualProposal(ExecutionControl.Never))
            assertFalse(owner.floatPaused)
        }
    }

    @Test
    fun `cancelled paused dual reads withhold copied values`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        LpScopedSolver(LpExactState(source)).use { owner ->
            var proposal: DoubleArray? = null
            repeat(8) {
                if (proposal == null) {
                    assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))
                    proposal = owner.pausedDualProposal(ExecutionControl.Never)
                }
            }
            assertNotNull(proposal)
            for (stopAt in listOf(1, 4)) {
                var polls = 0

                val declined = owner.pausedDualProposal(ExecutionControl { ++polls >= stopAt })

                assertNull(declined)
            }
        }
    }

    @Test
    fun `paused component guidance reads returned children without advancing`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val factory = RecordingLpEngineFactory()
        LpScopedSolver(LpExactState(source), context = LpSolveContext(factory), componentSplit = true).use { owner ->
            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))
            assertTrue(owner.floatPaused)
            val paid = owner.lastMetrics
            val queries = factory.floatAllowances.size

            val guidance = assertNotNull(owner.partialPrimalGuidance(ExecutionControl.Never))

            assertEquals(listOf(true, false, false), guidance.known.toList())
            assertEquals(0.5, guidance.coordinates[0])
            assertEquals(paid, owner.lastMetrics)
            assertEquals(queries, factory.floatAllowances.size)
            assertTrue(owner.floatPaused)
            assertNull(owner.lastResult)
        }
    }

    @Test
    fun `a bound edit retires paused component guidance`() {
        val builder = LpBuilder()
        repeat(3) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        LpScopedSolver(LpExactState(source), context = LpSolveContext(RecordingLpEngineFactory()),
            componentSplit = true).use { owner ->
            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))
            assertNotNull(owner.partialPrimalGuidance(ExecutionControl.Never))

            assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.of(1L)), 7L))

            assertNull(owner.partialPrimalGuidance(ExecutionControl.Never))
            assertFalse(owner.floatPaused)
        }
    }

    @Test
    fun `basis costs reject the point after a bound edit`() {
        val builder = LpBuilder()
        val x = builder.addVar(0, 2)
        val y = builder.addVar(0, 1, cost = 3)
        val z = builder.addVar(0, 1, cost = 5)
        builder.addRow(intArrayOf(x, y, z), longArrayOf(2, 2, -2), Relation.EQ, 1)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val pointModel = assertNotNull(state.ownerWorkingModel())
        LpScopedSolver(state).use { owner ->
            assertNotNull(owner.solveFloat()?.second)
            assertNotNull(owner.directionalCosts(pointModel, intArrayOf(x), 100_000L, ExecutionControl.Never) {
                assertTrue(it > 0L)
            })
            assertTrue(owner.push())
            assertTrue(owner.assertBound(x, true, ExactLpSide(ExactLpNumber.of(0L)), 1L))
            var work = 0L

            val costs = owner.directionalCosts(pointModel, intArrayOf(x), 100_000L,
                ExecutionControl.Never) { work += it }

            assertNull(costs)
            assertEquals(0L, work)
        }
    }

    @Test
    fun `reading optional guidance does not construct a numerical owner`() {
        for (directional in listOf(false, true)) {
            val builder = LpBuilder()
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
            val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
            val state = LpExactState(source)
            val point = assertNotNull(state.ownerWorkingModel())
            val factory = RecordingLpEngineFactory()
            var work = 0L
            LpScopedSolver(state, context = LpSolveContext(factory), componentSplit = true).use { owner ->

                val guidance = if (directional) {
                    owner.directionalCosts(point, intArrayOf(column), 100_000L, ExecutionControl.Never) { work += it }
                } else owner.partialPrimalGuidance(ExecutionControl.Never)

                assertNull(guidance)
                assertTrue(factory.calls.isEmpty())
                assertEquals(0L, work)
            }
        }
    }

    @Test
    fun `promoting a pivot stopped query should preserve its paid numerical path`() {
        val expected = LpScopedSolver(LpExactState(scopedLowerBoundModel())).use { owner ->
            assertNotNull(owner.solveFloat()).second to owner.lastMetrics
        }
        LpScopedSolver(LpExactState(scopedLowerBoundModel())).use { owner ->
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
        LpScopedSolver(LpExactState(scopedLowerBoundModel()), ExecutionControl { cancelled }).use { owner ->
            val guide = owner.advanceRootGuidance(100_000L, LpFloatAllowance(100_000L, 100), ReconstructionLimits(),
                observer = observer)

            assertNull(guide.primal)
            assertNull(guide.bound)
            assertEquals(LpRootGuidancePhase.CERTIFICATE, guide.phase)
        }
    }

    @Test
    fun `root guidance should retain heuristic coordinates when bound certification is exhausted`() {
        LpScopedSolver(LpExactState(scopedLowerBoundModel())).use { owner ->
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
        LpScopedSolver(LpExactState(scopedLowerBoundModel())).use { owner ->
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
        LpScopedSolver(LpExactState(scopedLowerBoundModel()), context = context).use { owner ->
            val guide = owner.advanceRootGuidance(100_000L, LpFloatAllowance(100_000L, 100), ReconstructionLimits())

            assertNotNull(guide.primal)
            assertNull(guide.bound)
            assertEquals(LpRootGuidancePhase.CERTIFICATE, guide.phase)
        }
    }

    @Test
    fun `reading paused progress does not prepare a numerical owner`() {
        val factory = RecordingLpEngineFactory()
        LpScopedSolver(LpExactState(scopedLowerBoundModel()), context = LpSolveContext(factory)).use { owner ->

            val progress = owner.pausedTrialProgress

            assertTrue(progress.isEmpty())
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `paused numerical progress is observable without advancing the cursor`() {
        LpScopedSolver(LpExactState(scopedLowerBoundModel()),
            pricing = LpPricingOptions(basisEntryLimit = 100_000L)).use { owner ->
            val allowance = LpFloatAllowance(100_000L, 100)
            assertNull(owner.advanceFloat(1L, allowance))
            assertNull(owner.advanceFloat(1L, allowance))
            val before = owner.lastMetrics

            val progress = owner.pausedTrialProgress

            assertTrue(owner.floatPaused)
            assertTrue(progress.getValue("ProgressSamples") > 0L)
            assertEquals(1L, progress["ProgressPhaseDUAL"])
            assertEquals(before, owner.lastMetrics)
            assertNull(owner.lastResult)
        }
    }

    @Test
    fun `completed numerical solves withhold paused progress`() {
        LpScopedSolver(LpExactState(scopedLowerBoundModel()),
            pricing = LpPricingOptions(basisEntryLimit = 100_000L)).use { owner ->
            assertNotNull(owner.solveFloat())

            val progress = owner.pausedTrialProgress

            assertTrue(progress.isEmpty())
        }
    }

    @Test
    fun `checkpointed floating work should match uninterrupted work without publishing a paused result`() {
        val model = scopedLowerBoundModel()
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
    fun `fresh consultations resume the same cursor after the earlier query token expires`() {
        val state = LpExactState(scopedLowerBoundModel())
        val expected = LpScopedSolver(state).use { owner ->
            assertNotNull(owner.solveFloat()).second to owner.lastMetrics
        }
        var expired = false
        LpScopedSolver(state).use { owner ->
            val allowance = LpFloatAllowance(100_000L, 100)
            assertNull(owner.advanceFloat(1L, allowance, ExecutionControl { expired }))
            var work = owner.lastMetrics
            assertTrue(owner.floatPaused)
            expired = true
            var result: FloatLpResult? = null
            var attempts = 0

            while (result == null && attempts++ < 64) {
                result = owner.advanceFloat(1L, allowance, ExecutionControl.Never)?.second
                work += owner.lastMetrics
            }

            assertEquals(assertNotNull(expected.first).objective, assertNotNull(result).objective)
            assertEquals(expected.second.workOps, work.workOps)
            assertFalse(owner.floatPaused)
            assertEquals(1L, owner.metrics.createdOwners)
            assertEquals(0L, owner.metrics.closedOwners)
        }
    }

    @Test
    fun `fixed lifetime consultations should retain their cursor as remaining work shrinks`() {
        LpScopedSolver(LpExactState(scopedLowerBoundModel())).use { owner ->
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
        LpScopedSolver(LpExactState(scopedLowerBoundModel())).use { owner ->
            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))

            assertTrue(owner.adopt(owner.state))
            assertTrue(owner.assertBounds(emptyList()) is LpBoundBatchResult.Applied)

            assertTrue(owner.floatPaused)
            assertEquals(0L, owner.metrics.closedOwners)
        }
    }

    @Test
    fun `changed bounds should discard paused progress before solving the new authority`() {
        LpScopedSolver(LpExactState(scopedLowerBoundModel())).use { owner ->
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
            LpScopedSolver(LpExactState(scopedLowerBoundModel())).use { owner ->
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
        val owner = LpScopedSolver(LpExactState(scopedLowerBoundModel()), ExecutionControl { cancelled })
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
        LpScopedSolver(LpExactState(scopedLowerBoundModel())).use { owner ->
            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))
            var polls = 0
            val token = ExecutionControl { ++polls >= 2 }

            assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100), token))

            assertFalse(owner.floatPaused)
            assertEquals(owner.metrics.createdOwners, owner.metrics.closedOwners)
        }
    }

    @Test
    fun `retraction should discard a paused scoped solve`() {
        for (reset in listOf(false, true)) {
            LpScopedSolver(LpExactState(scopedLowerBoundModel())).use { owner ->
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
        LpScopedSolver(LpExactState(scopedLowerBoundModel())).use { owner ->
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
            LpScopedSolver(LpExactState(scopedLowerBoundModel()), context = LpSolveContext(factory)).use { owner ->
                owner.advanceFloat(quantum, LpFloatAllowance(cumulative, 100))

                assertEquals(minOf(quantum, cumulative), assertNotNull(factory.floatAllowances.single()).work)
                assertFalse(owner.floatPaused)
            }
        }
    }

    @Test
    fun `closing a paused owner should release its numerical factors`() {
        val owner = LpScopedSolver(LpExactState(scopedLowerBoundModel()))
        assertNull(owner.advanceFloat(1L, LpFloatAllowance(100_000L, 100)))

        owner.close()

        assertFalse(owner.floatPaused)
        assertEquals(owner.metrics.createdOwners, owner.metrics.closedOwners)
    }
}
