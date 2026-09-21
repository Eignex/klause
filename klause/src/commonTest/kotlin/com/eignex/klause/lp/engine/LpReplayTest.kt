package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpReplayTest {
    @Test
    fun `replay exhausts one continuation allowance across unchanged and changing bases`() {
        for (changing in listOf(false, true)) {
            val model = LpBuilder().apply {
                val x = addRealVar(0.0, 2.0)
                addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
            }.build(Sense.MINIMIZE)
            var event = 0
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newGeneralSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    pricing: LpPricingOptions,
                ): LpSolver = object : LpSolver {
                    override val infeasibleRay: DoubleArray? = null
                    override val infeasibleBasis: Basis get() = if (changing && event % 2 == 0) {
                        Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
                    } else {
                        Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC))
                    }
                    override fun solve(warm: Basis?): FloatLpResult? {
                        event++
                        return null
                    }
                    override fun solvePrimal(warm: Basis?): FloatLpResult? = solve(warm)
                }
            }
            val capture = LpCapture.capture(
                model,
                LpReplaySettings("continuation-allowance", 570L, componentSplit = false),
                List(32) { LpReplayEvent.Solve() },
            )

            val report = LpReplay.replay(
                capture,
                context = LpSolveContext(factory),
                continuationLimits = ExactContinuationLimits(maxWork = 5000L),
            )

            assertTrue(report.steps.first().continuation?.success == true)
            assertEquals(ContinuationDecline.WORK, report.steps.last().continuation?.decline)
            assertTrue(report.steps.sumOf { it.continuation?.work ?: 0L } <= 5000L)
            assertFalse(report.steps.last().hasFeasibleWitness)
            assertFalse(report.steps.last().hasInfeasibilityProof)
        }
    }

    @Test
    fun `strict replay exposes supplemental admission refusal`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            repeat(128) { addRealVar(0.0, 1.0) }
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)
        val capture = LpCapture.capture(
            model,
            LpReplaySettings("strict-admission", 570L, componentSplit = false),
            listOf(LpReplayEvent.Solve()),
        )

        val step = LpReplay.replay(capture).steps.single()

        assertEquals(LpStrictReplayCapability.ADMISSION_DECLINED, step.strictRefinementCapability)
        assertFalse(step.hasFeasibleWitness)
        assertFalse(step.hasInfeasibilityProof)
    }

    @Test
    fun `strict replay cancellation during auxiliary preparation publishes no witness`() {
        val source = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)
        var prepared = false
        var closed = 0
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
                    override fun prepareLogicals(token: Cancellation): Basis? {
                        val result = delegate.prepareLogicals(token)
                        prepared = true
                        repeat(2000) { cancellation() }
                        return result
                    }
                    override fun close() {
                        closed++
                        delegate.close()
                    }
                }
            }
        }
        val capture = LpCapture.capture(
            source,
            LpReplaySettings("strict-cancel", 570L, componentSplit = false, cancellationPollLimit = 2000),
            listOf(LpReplayEvent.Solve()),
        )

        val step = LpReplay.replay(capture, context = LpSolveContext(factory)).steps.single()

        assertTrue(prepared)
        assertEquals(1, closed)
        assertFalse(step.hasFeasibleWitness)
        assertFalse(step.hasInfeasibilityProof)
        assertNull(step.exactWitness)
    }

    @Test
    fun `strict replay retains source evidence without adding numerical source solves`() {
        val source = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)
        val calls = ArrayList<String>()
        var sourceOwners = 0
        var auxiliaries = 0
        var closed = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newGeneralSolver(
                model: LpModel,
                cancellation: Cancellation,
                pricing: LpPricingOptions,
            ): LpSolver {
                sourceOwners++
                val delegate = ProductionLpEngineFactory.newGeneralSolver(model, cancellation, pricing)
                return object : LpSolver by delegate {
                    override fun solve(warm: Basis?): FloatLpResult? {
                        calls += "solve"
                        return delegate.solve(warm)
                    }
                    override fun solvePrimal(warm: Basis?): FloatLpResult? {
                        calls += "solvePrimal"
                        return delegate.solvePrimal(warm)
                    }
                    override fun close() {
                        closed++
                        delegate.close()
                    }
                }
            }
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                assertTrue(model.n > source.n)
                auxiliaries++
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
                    override fun close() {
                        closed++
                        delegate.close()
                    }
                }
            }
        }
        val capture = LpCapture.capture(
            source,
            LpReplaySettings("strict-repeat", 570L, componentSplit = false),
            listOf(LpReplayEvent.Solve(), LpReplayEvent.SolvePrimal()),
        )

        val report = LpReplay.replay(capture, context = LpSolveContext(factory))

        assertEquals(listOf("solve", "solvePrimal"), calls)
        assertEquals(1, sourceOwners)
        assertEquals(1, auxiliaries)
        assertEquals(sourceOwners + auxiliaries, closed)
        assertEquals(1, report.steps.sumOf { it.refinement?.strictAttempts ?: 0 })
        assertTrue(report.steps.last().supplementalEvidenceReused)
        for (step in report.steps) {
            assertEquals(LpVerdict.FEASIBLE, step.productionVerdict)
            assertEquals(BigFraction.ZERO, step.rationalLowerBound)
            assertNotNull(checkedLpWitness(source, assertNotNull(step.exactWitness)))
            assertTrue(assertNotNull(step.exactWitness).single() > BigFraction.ZERO)
        }
    }

    @Test
    fun `row replay preserves interleaved persistent guards through nested backjumps`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        val localGuard = ExactLpPremises(emptyList(), listOf(30))
        val events = listOf(
            LpExactReplayEvent.Push(),
            LpExactReplayEvent.Append(
                LpScopedRow(
                    1,
                    listOf(0 to minusOne),
                    ExactLpNumber.of(-4L),
                    logical,
                    ExactLpRow(false, premises = ExactLpPremises(emptyList(), listOf(10))),
                ),
                true,
            ),
            LpExactReplayEvent.Append(
                LpScopedRow(
                    2,
                    listOf(0 to minusOne),
                    ExactLpNumber.of(-2L),
                    logical,
                    ExactLpRow(false, premises = ExactLpPremises(emptyList(), listOf(20))),
                ),
                false,
            ),
            LpExactReplayEvent.Push(),
            LpExactReplayEvent.Append(
                LpScopedRow(
                    3,
                    listOf(0 to minusOne),
                    ExactLpNumber.of(-6L),
                    logical,
                    ExactLpRow(false, premises = localGuard),
                ),
                true,
            ),
            LpExactReplayEvent.Append(LpScopedRow(4, listOf(0 to minusOne), ExactLpNumber.of(-3L), logical), false),
            LpExactReplayEvent.Solve(),
            LpExactReplayEvent.Pop(1),
            LpExactReplayEvent.Solve(),
            LpExactReplayEvent.Pop(0),
            LpExactReplayEvent.Solve(),
            LpExactReplayEvent.Compact(),
            LpExactReplayEvent.Solve(),
        )
        val capture = LpExactCapture.capture(source, persistentSettings("scoped rows"), events)

        val replay = LpExactReplay.replay(LpExactCapture.decode(capture.encode()))

        assertNull(replay.declinedEventIndex)
        assertEquals(0L, assertNotNull(replay.rowMetrics).currentOwners)
        assertEquals(0L, replay.rowMetrics.preparationDeclines)
        assertTrue(replay.steps.all { it.accepted })
        val results = replay.steps.mapNotNull { it.result }
        assertEquals(listOf(6L, 4L, 3L, 3L).map { BigFraction.ofLong(it) }, results.map { it.lowerBound })
        assertEquals(results.map { it.lowerBound }, results.map { assertNotNull(it.exactPrimal).single() })
        val deep = assertNotNull(results.first().bound?.support)
        assertEquals(3L, deep.state.rows.row(deep.rows.single().first).id)
        assertEquals(localGuard, deep.rows.single().second.premises)
        val final = replay.steps.last().state
        assertEquals(listOf(2L, 4L), final.rows.entries().map { it.id })
        val support = assertNotNull(results.last().bound?.support)
        assertEquals(4L, support.state.rows.row(support.rows.single().first).id)
        val fresh = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne), ExactLpEntry(1, minusOne))),
            listOf(ExactLpNumber.of(-2L), ExactLpNumber.of(-3L)),
            listOf(source.column(0), logical, logical),
            listOf(ExactLpRow(false, premises = ExactLpPremises(emptyList(), listOf(20))), ExactLpRow()),
            ExactLpObjective(listOf(one, zero, zero)),
        )
        val independent = solveAndCertify(fresh)
        assertEquals(independent.exactPrimal, results.last().exactPrimal)
        assertEquals(independent.lowerBound, results.last().lowerBound)
        assertEquals(3.0, results.last().float?.primal?.single())
        assertEquals(-1.0, results.last().float?.duals?.get(1))
    }

    @Test
    fun `replay resumes compacted identities assertions and objective coordinates`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        val trail = LpBoundTrail(source)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        assertTrue(trail.push())
        assertTrue(trail.append(LpScopedRow(5, listOf(0 to minusOne), minusOne, logical), true))
        assertTrue(trail.pop(0))
        assertTrue(trail.compact())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 8))
        assertTrue(trail.recenter(listOf(one)))
        val events = listOf(
            LpExactReplayEvent.Append(LpScopedRow(6, listOf(0 to minusOne), ExactLpNumber.of(-3L), logical), false),
            LpExactReplayEvent.Solve(),
            LpExactReplayEvent.Deactivate(6),
            LpExactReplayEvent.Compact(),
            LpExactReplayEvent.Solve(),
        )
        val capture = LpExactCapture.capture(trail.state, persistentSettings("resume scoped"), events, 1)

        val report = LpExactReplay.replay(LpExactCapture.decode(capture.encode()))

        assertNull(report.declinedEventIndex)
        val results = report.steps.mapNotNull { it.result }
        assertEquals(listOf(BigFraction.ofLong(4L), BigFraction.ofLong(2L)), results.map { it.lowerBound })
        assertEquals(results.map { it.lowerBound }, results.map { assertNotNull(it.exactPrimal).single() })
        val final = report.steps.last().state
        assertEquals(6L, final.rows.lastId)
        assertEquals(one, final.model.column(0).origin)
        assertEquals(one, final.model.objective.constant)
        assertEquals(8L, final.assertions.single().witness)
        assertEquals(one, final.assertions.single().side.number)
        assertFalse(LpBoundTrail(final).append(LpScopedRow(5, emptyList(), zero, logical), false))
        assertEquals(0L, assertNotNull(report.rowMetrics).currentOwners)
    }

    @Test
    fun `invalid later row transitions are rejected before any factory construction`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val logical = ExactLpColumn(ExactLpBounds())
        val row = LpScopedRow(0, listOf(0 to one), zero, logical)
        val histories = listOf(
            listOf(
                LpExactReplayEvent.Append(row, true),
                LpExactReplayEvent.Deactivate(0),
                LpExactReplayEvent.Compact(),
                LpExactReplayEvent.Append(row, false),
            ),
            listOf(
                LpExactReplayEvent.Push(),
                LpExactReplayEvent.Append(LpScopedRow(0, listOf(0 to one), zero, logical, cost = one), true),
                LpExactReplayEvent.Pop(0),
            ),
            listOf(
                LpExactReplayEvent.Append(row, false),
                LpExactReplayEvent.Assert(1, true, ExactLpSide(zero), 1),
                LpExactReplayEvent.Deactivate(0),
            ),
            listOf(
                LpExactReplayEvent.Append(row, false),
                LpExactReplayEvent.Deactivate(0),
                LpExactReplayEvent.Compact(),
                LpExactReplayEvent.Solve(Basis(intArrayOf(1), arrayOf(VarStatus.FREE, VarStatus.BASIC))),
            ),
        )
        for (history in histories) {
            val factory = RecordingLpEngineFactory()
            val capture = LpExactCapture.capture(
                source,
                persistentSettings("invalid rows"),
                listOf(LpExactReplayEvent.Solve()) + history,
            )

            assertFails { LpExactReplay.replay(capture, LpSolveContext(engineFactory = factory)) }

            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `exact replay preserves active witnesses across objective and origin changes`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(1L))),
        )
        val premises = ExactLpPremises(listOf(ExactLpPremise(3, false, ExactLpNumber.of(2L))), listOf(19))
        val capture = LpExactCapture.capture(
            model,
            persistentSettings("exact edits"),
            listOf(
                LpExactReplayEvent.Solve(),
                LpExactReplayEvent.Push(),
                LpExactReplayEvent.Assert(0, false, ExactLpSide(ExactLpNumber.of(2L), premises = premises), 41L),
                LpExactReplayEvent.Solve(),
                LpExactReplayEvent.Objective(ExactLpObjective(listOf(ExactLpNumber.of(-1L)))),
                LpExactReplayEvent.Recenter(listOf(ExactLpNumber.of(1L))),
                LpExactReplayEvent.Solve(),
                LpExactReplayEvent.Pop(0),
                LpExactReplayEvent.Solve(),
            ),
        )

        val report = LpExactReplay.replay(LpExactCapture.decode(capture.encode()))

        assertNull(report.declinedEventIndex)
        assertTrue(report.steps.all { it.accepted })
        assertEquals(9, report.steps.size)
        val asserted = report.steps[3]
        assertEquals(1L, asserted.state.boundRevision)
        assertEquals(0L, asserted.state.objectiveRevision)
        assertEquals(listOf(0), asserted.state.scopes)
        assertEquals(41L, asserted.state.assertions.single().witness)
        assertEquals(premises, asserted.state.assertions.single().side.premises)
        val tightened = assertNotNull(asserted.result)
        assertEquals(BigFraction.ofLong(2L), assertNotNull(tightened.witness).primal.single())
        assertEquals(BigFraction.ofLong(2L), tightened.lowerBound)
        val shifted = report.steps[6]
        assertEquals(0L, shifted.state.matrixRevision)
        assertEquals(2L, shifted.state.boundRevision)
        assertEquals(2L, shifted.state.objectiveRevision)
        assertEquals(ExactLpNumber.of(1L), shifted.state.model.column(0).origin)
        assertEquals(41L, shifted.state.assertions.single().witness)
        assertEquals(BigFraction.ofLong(10L), assertNotNull(shifted.result?.witness).primal.single())
        assertEquals(BigFraction.ofLong(-10L), shifted.result.lowerBound)
        assertTrue(report.steps.last().state.assertions.isEmpty())
        assertEquals(3L, report.steps.last().state.boundRevision)
        assertEquals(1L, report.steps.last().state.popRevision)
        assertEquals(2L, report.steps.last().state.objectiveRevision)
        assertTrue(report.steps.last().state.popRevision > asserted.state.popRevision)
    }

    @Test
    fun `exact replay preflight rejects unsupported later projection`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val huge = ExactLpNumber.of(BigFraction.of(BigInteger.ONE shl 2048, BigInteger.ONE))
        val capture = LpExactCapture.capture(
            model,
            persistentSettings("unsupported projection"),
            listOf(LpExactReplayEvent.Solve(), LpExactReplayEvent.Assert(0, false, ExactLpSide(huge), 7L)),
        )

        assertFails { LpExactReplay.replay(capture) }
    }

    @Test
    fun `exact replay preflight rejects invalid backjump after solve`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val capture = LpExactCapture.capture(
            model,
            persistentSettings("bad pop"),
            listOf(LpExactReplayEvent.Solve(), LpExactReplayEvent.Pop(1)),
        )

        assertFails { LpExactReplay.replay(capture) }
    }

    @Test
    fun `exact replay cancellation declines before any event claim`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val settings = LpReplaySettings(
            "cancelled exact",
            0L,
            LpReplaySolverKind.PERSISTENT,
            componentSplit = false,
            cancellationPollLimit = 1,
        )

        val report = LpExactReplay.replay(LpExactCapture.capture(model, settings, listOf(LpExactReplayEvent.Solve())))

        assertEquals(-1, report.declinedEventIndex)
        assertTrue(report.steps.isEmpty())
    }

    @Test
    fun `exact replay cancelled solve stops without publishing a claim`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val settings = LpReplaySettings(
            "cancelled solve",
            0L,
            LpReplaySolverKind.PERSISTENT,
            componentSplit = false,
            cancellationPollLimit = 32,
        )
        val capture = LpExactCapture.capture(
            model,
            settings,
            listOf(LpExactReplayEvent.Solve(), LpExactReplayEvent.Push()),
        )

        val report = LpExactReplay.replay(capture)

        assertEquals(0, report.declinedEventIndex)
        val step = report.steps.single()
        assertFalse(step.accepted)
        assertTrue(step.cancelled)
        assertNull(step.result)
        assertEquals(0, step.state.depth)
    }

    @Test
    fun `resource stopped native replay retains its constant bound across gated restoration`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L)
            addVar(0L, 10L)
            addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.GE, 3L)
        }.build(Sense.MINIMIZE)
        val capture = LpCapture.capture(
            model,
            LpReplaySettings(
                "constant-bound",
                76L,
                LpReplaySolverKind.PERSISTENT,
                componentSplit = false,
                pivotLimit = 1,
                workLimit = 10L,
            ),
            listOf(
                LpReplayEvent.Solve(),
                LpReplayEvent.Rebind(longArrayOf(0L, 0L), longArrayOf(1L, 10L)),
                LpReplayEvent.ResolveBounds(),
                LpReplayEvent.ResolveGated(booleanArrayOf(false)),
                LpReplayEvent.Solve(),
            ),
        )

        val steps = LpReplay.replay(capture).steps

        for (index in listOf(0, 2, 4)) {
            val step = steps[index]
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, step.productionVerdict)
            assertEquals(BigFraction.ZERO, step.rationalLowerBound)
            val point = assertNotNull(step.exactWitness)
            assertTrue(point[0] + point[1] >= BigFraction.ofLong(3L))
            assertTrue(point.all { it >= BigFraction.ZERO && it <= BigFraction.ofLong(10L) })
            if (index > 0) assertTrue(point[0] <= BigFraction.ONE)
        }
        assertEquals(LpCertificationCapability.GATED_ACTIVE_STATE_UNAVAILABLE, steps[3].certificationCapability)
        assertFalse(steps[3].hasCertifiedBound)
        assertFalse(steps[3].hasFeasibleWitness)
        assertNull(steps[3].rationalLowerBound)
        assertNull(steps[3].integerObjectiveLowerBound)
        assertNull(steps[3].exactWitness)
    }

    @Test
    fun `rebound absent upper sides preserve source authority and row premises`() {
        for (probeClamped in listOf(false, true)) {
            val premises = LpRowPremises(intArrayOf(7), booleanArrayOf(false), longArrayOf(5L), intArrayOf(19))
            val model = LpBuilder().apply {
                if (probeClamped) addFreeVar(0L, null, cost = 1L) else addOpenAboveVar(0L, cost = 1L)
                addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 5L, global = false, premises = premises)
            }.build(Sense.MINIMIZE)
            assertEquals(probeClamped, model.probeClampedHi[0])
            assertEquals(probeClamped, model.hasUpper[0])
            assertFalse(model.probeClampedLo[0])
            var adopted: LpExactState? = null
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
                            delegate.adopt(state, token).also { if (it) adopted = state }
                    }
                }
            }
            val capture = LpCapture.capture(
                model,
                persistentSettings("probe-premises"),
                listOf(
                    LpReplayEvent.Rebind(longArrayOf(2L), longArrayOf(3L)),
                    LpReplayEvent.ResolveBounds(),
                ),
            )

            val step = LpReplay.replay(
                LpCapture.decode(capture.encode()),
                context = LpSolveContext(factory),
            ).steps.last()

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, step.productionVerdict)
            assertEquals(listOf(BigFraction.ofLong(5L)), step.exactWitness)
            assertEquals(BigFraction.ofLong(5L), step.rationalLowerBound)
            val authority = assertNotNull(adopted).model
            assertNull(authority.column(0).bounds.upper)
            assertEquals(BigFraction.ZERO, assertNotNull(authority.column(0).bounds.lower).number.value)
            assertEquals(BigFraction.ofLong(2L), authority.column(0).origin.value)
            assertFalse(authority.row(0).global)
            assertEquals(listOf(19), assertNotNull(authority.row(0).premises).literalEntries())
            assertEquals(
                listOf(ExactLpPremise(7, false, ExactLpNumber.of(5L))),
                authority.row(0).premises?.boundEntries(),
            )
        }
    }

    @Test
    fun `mixed gated replay restores full source rows before certification`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val capture = LpCapture.capture(
            model,
            persistentSettings("mixed-gated"),
            listOf(
                LpReplayEvent.Rebind(longArrayOf(0L), longArrayOf(1L)),
                LpReplayEvent.ResolveGated(booleanArrayOf(false)),
                LpReplayEvent.ResolveBounds(),
                LpReplayEvent.Rebind(longArrayOf(0L), longArrayOf(3L)),
                LpReplayEvent.ResolveBounds(),
            ),
        )

        val steps = LpReplay.replay(LpCapture.decode(capture.encode())).steps

        assertEquals(LpCandidateKind.FLOAT_OPTIMUM, steps[1].candidate)
        assertEquals(LpVerdict.INDETERMINATE, steps[1].productionVerdict)
        assertFalse(steps[1].hasFeasibleWitness)
        assertFalse(steps[1].hasCertifiedBound)
        assertEquals(LpVerdict.INFEASIBLE, steps[2].productionVerdict)
        assertTrue(steps[2].hasInfeasibilityProof)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, steps[4].productionVerdict)
        val point = assertNotNull(steps[4].exactWitness).single()
        assertTrue(point >= BigFraction.ofLong(2L) && point <= BigFraction.ofLong(3L))
    }

    @Test
    fun `partial row masks survive bound replacement and restore both source rows`() {
        val model = LpBuilder().apply {
            addVar(0L, 3L)
            addVar(0L, 3L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
            addRow(intArrayOf(1), longArrayOf(1L), Relation.GE, 4L)
        }.build(Sense.MINIMIZE)
        val capture = LpCapture.capture(
            model,
            persistentSettings("partial-mask"),
            listOf(
                LpReplayEvent.Rebind(longArrayOf(0L, 0L), longArrayOf(3L, 3L)),
                LpReplayEvent.ResolveGated(booleanArrayOf(true, false)),
                LpReplayEvent.Rebind(longArrayOf(0L, 1L), longArrayOf(1L, 5L)),
                LpReplayEvent.ResolveGated(booleanArrayOf(false, true)),
                LpReplayEvent.ResolveBounds(),
                LpReplayEvent.Rebind(longArrayOf(0L, 1L), longArrayOf(3L, 5L)),
                LpReplayEvent.ResolveBounds(),
            ),
        )

        val steps = LpReplay.replay(LpCapture.decode(capture.encode())).steps

        for (index in listOf(1, 3)) {
            val step = steps[index]
            val point = assertNotNull(step.primalBits).map { assertNotNull(BigFraction.ofDouble(Double.fromBits(it))) }
            assertEquals(BigFraction.ZERO, BigFraction.ofDouble(Double.fromBits(assertNotNull(step.objectiveBits))))
            assertTrue(point.all { it >= BigFraction.ZERO && it <= BigFraction.ofLong(5L) })
            if (index == 1) {
                assertTrue(point[0] >= BigFraction.ofLong(2L) && point[0] <= BigFraction.ofLong(3L))
                assertTrue(point[1] <= BigFraction.ofLong(3L))
            } else {
                assertTrue(point[0] >= BigFraction.ZERO && point[0] <= BigFraction.ONE)
                assertTrue(point[1] >= BigFraction.ofLong(4L))
            }
            assertEquals(LpVerdict.INDETERMINATE, step.productionVerdict)
            assertFalse(step.hasCertifiedBound)
            assertFalse(step.hasFeasibleWitness)
            assertNull(step.exactWitness)
        }
        assertEquals(LpVerdict.INFEASIBLE, steps[4].productionVerdict)
        assertTrue(steps[4].hasInfeasibilityProof)
        val restored = steps[6]
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, restored.productionVerdict)
        val point = assertNotNull(restored.exactWitness)
        assertTrue(point[0] >= BigFraction.ofLong(2L) && point[1] >= BigFraction.ofLong(4L))
        assertTrue(point[0] <= BigFraction.ofLong(3L) && point[1] <= BigFraction.ofLong(5L))
        assertEquals(BigFraction.ZERO, restored.rationalLowerBound)
    }

    @Test
    fun `declined replay adoption withholds earlier artifacts until an explicit replacement`() {
        for (rhs in listOf(0L, 2L)) {
            val model = LpBuilder().apply {
                addVar(0L, 1L)
                addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, rhs)
            }.build(Sense.MINIMIZE)
            var attempts = 0
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
                        override fun adopt(state: LpExactState, token: Cancellation): Boolean {
                            attempts++
                            return attempts > 1 && delegate.adopt(state, token)
                        }

                        override fun close() {
                            closes++
                            delegate.close()
                        }
                    }
                }
            }
            val capture = LpCapture.capture(
                model,
                persistentSettings("failed-adoption"),
                listOf(
                    LpReplayEvent.Solve(),
                    LpReplayEvent.Rebind(longArrayOf(2L), longArrayOf(3L)),
                    LpReplayEvent.ResolveBounds(),
                    LpReplayEvent.Solve(),
                    LpReplayEvent.SolvePrimal(),
                    LpReplayEvent.ResolveGated(booleanArrayOf(false)),
                    LpReplayEvent.Rebind(longArrayOf(2L), longArrayOf(3L)),
                    LpReplayEvent.ResolveBounds(),
                ),
            )

            val steps = LpReplay.replay(capture, context = LpSolveContext(factory)).steps

            assertEquals(
                if (rhs == 0L) LpVerdict.ATTAINED_OPTIMUM else LpVerdict.INFEASIBLE,
                steps[0].productionVerdict,
            )
            for (step in steps.subList(2, 6)) {
                assertEquals(LpVerdict.INDETERMINATE, step.productionVerdict)
                assertEquals(LpCandidateKind.NONE, step.candidate)
                assertFalse(step.hasFeasibleWitness)
                assertFalse(step.hasCertifiedBound)
                assertFalse(step.hasInfeasibilityProof)
                assertNull(step.rationalLowerBound)
                assertNull(step.integerObjectiveLowerBound)
                assertNull(step.exactWitness)
                assertEquals(0L, step.metrics.workOps)
            }
            assertEquals(LpCertificationCapability.GATED_ACTIVE_STATE_UNAVAILABLE, steps[5].certificationCapability)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, steps[7].productionVerdict)
            val point = assertNotNull(steps[7].exactWitness).single()
            assertTrue(point >= BigFraction.ofLong(2L) && point <= BigFraction.ofLong(3L))
            assertEquals(2, attempts)
            assertEquals(1, closes)
        }
    }

    @Test
    fun `explicit replay cancellation reset can recover a declined bound replacement`() {
        val model = LpBuilder().apply { addVar(0L, 10L, cost = 1L) }.build(Sense.MINIMIZE)
        val capture = LpCapture.capture(
            model,
            LpReplaySettings(
                "reset",
                76L,
                LpReplaySolverKind.PERSISTENT,
                componentSplit = false,
                cancellationPollLimit = 1,
            ),
            listOf(
                LpReplayEvent.Rebind(longArrayOf(5L), longArrayOf(10L)),
                LpReplayEvent.ResolveBounds(),
                LpReplayEvent.Rebind(longArrayOf(6L), longArrayOf(10L), cancellationPollLimit = 0),
                LpReplayEvent.ResolveBounds(),
            ),
        )

        val steps = LpReplay.replay(capture).steps

        assertEquals(LpVerdict.INDETERMINATE, steps[1].productionVerdict)
        assertFalse(steps[1].hasCertifiedBound)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, steps[3].productionVerdict)
        assertEquals(listOf(BigFraction.ofLong(6L)), steps[3].exactWitness)
        assertEquals(BigFraction.ofLong(6L), steps[3].rationalLowerBound)
    }

    @Test
    fun `persistent replay executes solve rebind and resolve through the seam`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        val capture = LpCapture.capture(
            builder.build(Sense.MINIMIZE),
            persistentSettings("rebind"),
            listOf(
                LpReplayEvent.Solve(),
                LpReplayEvent.Rebind(longArrayOf(5L), longArrayOf(10L)),
                LpReplayEvent.ResolveBounds(),
            ),
        )

        val report = LpReplay.replay(LpCapture.decode(capture.encode()))

        assertEquals(3, report.steps.size)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, report.steps[0].productionVerdict)
        assertEquals(3.0, Double.fromBits(assertNotNull(report.steps[0].objectiveBits)), 1e-9)
        assertEquals(LpReplayOperation.REBIND, report.steps[1].operation)
        assertNull(report.steps[1].productionVerdict)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, report.steps[2].productionVerdict)
        assertEquals(5.0, Double.fromBits(assertNotNull(report.steps[2].objectiveBits)), 1e-9)
        assertTrue(report.steps[2].metrics.warmHits > 0)
        assertTrue(report.steps[2].hasCertifiedBound)
        val integer = assertNotNull(report.steps[2].certifiers.singleOrNull { it.certifier == LpCertifier.INTEGER })
        assertEquals(1, integer.attempts)
        assertEquals(0, integer.declines)
    }

    @Test
    fun `gated replay deactivates a row only through the supported persistent operation`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        val capture = LpCapture.capture(
            builder.build(Sense.MINIMIZE),
            persistentSettings("gated"),
            listOf(
                LpReplayEvent.Solve(),
                LpReplayEvent.ResolveGated(booleanArrayOf(false)),
                LpReplayEvent.ResolveGated(booleanArrayOf(true)),
            ),
        )

        val report = LpReplay.replay(capture)

        assertEquals(LpVerdict.INFEASIBLE, report.steps[0].productionVerdict)
        assertTrue(report.steps[0].hasInfeasibilityProof)
        assertEquals(LpVerdict.INDETERMINATE, report.steps[1].productionVerdict)
        assertEquals(LpCandidateKind.FLOAT_OPTIMUM, report.steps[1].candidate)
        assertFalse(report.steps[1].hasCertifiedBound)
        assertEquals(LpCertificationCapability.GATED_ACTIVE_STATE_UNAVAILABLE, report.steps[1].certificationCapability)
        assertEquals(LpVerdict.INDETERMINATE, report.steps[2].productionVerdict)
        assertEquals(LpCandidateKind.FLOAT_INFEASIBILITY, report.steps[2].candidate)
        assertFalse(report.steps[2].hasInfeasibilityProof)
    }

    @Test
    fun `warm basis and primal solve remain discrete portable events`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val basis = newLpSolver(model, componentSplit = false).use { assertNotNull(it.solve()).basis }
        val capture = LpCapture.capture(
            model,
            LpReplaySettings("warm", 7L, LpReplaySolverKind.TABLEAU, componentSplit = false),
            listOf(LpReplayEvent.SolvePrimal(LpCapturedBasis.capture(basis))),
        )

        val step = LpReplay.replay(capture).steps.single()

        assertEquals(LpReplayOperation.SOLVE_PRIMAL, step.operation)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, step.productionVerdict)
        assertEquals(1, step.metrics.warmAttempts)
    }

    @Test
    fun `cancellation is replayed as an indeterminate result rather than a verdict`() {
        val capture = LpCapture.capture(
            pivotModel(160),
            LpReplaySettings(
                "cancel",
                19L,
                LpReplaySolverKind.TABLEAU,
                componentSplit = false,
                cancellationPollLimit = 1,
            ),
            listOf(LpReplayEvent.Solve()),
        )

        val step = LpReplay.replay(capture).steps.single()

        assertEquals(LpVerdict.INDETERMINATE, step.productionVerdict)
        assertFalse(step.hasCertifiedBound)
        assertFalse(step.hasFeasibleWitness)
        assertFalse(step.hasInfeasibilityProof)
    }

    @Test
    fun `rebind without an override preserves the lifetime cancellation budget`() {
        val model = pivotModel(160)
        val capture = LpCapture.capture(
            model,
            LpReplaySettings(
                "cancel-rebind",
                20L,
                LpReplaySolverKind.PERSISTENT,
                componentSplit = false,
                cancellationPollLimit = 1,
            ),
            listOf(
                LpReplayEvent.Rebind(LongArray(model.n), LongArray(model.n) { 9L }),
                LpReplayEvent.ResolveBounds(),
            ),
        )

        val report = LpReplay.replay(LpCapture.decode(capture.encode()))

        assertEquals(LpReplayOperation.REBIND, report.steps[0].operation)
        assertEquals(LpVerdict.INDETERMINATE, report.steps[1].productionVerdict)
        assertFalse(report.steps[1].hasCertifiedBound)
    }

    @Test
    fun `strict rational refutation records its exact infeasibility proof`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(-1.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 0.0, strict = true)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0)
        val capture = LpCapture.capture(
            builder.build(Sense.MINIMIZE),
            LpReplaySettings("strict-refutation", 21L, componentSplit = false),
            listOf(LpReplayEvent.Solve()),
        )

        val step = LpReplay.replay(capture).steps.single()

        assertEquals(LpVerdict.INFEASIBLE, step.productionVerdict)
        assertTrue(step.hasInfeasibilityProof)
        assertTrue(step.certifiers.any { it.certifier == LpCertifier.RATIONAL && it.successes >= 1 })
    }

    @Test
    fun `component replay retains the exact assembled lower bound`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 5L, cost = 1L)
        val y = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 3L)
        val capture = LpCapture.capture(
            builder.build(Sense.MINIMIZE),
            LpReplaySettings("components", 22L),
            listOf(LpReplayEvent.Solve()),
        )

        val step = LpReplay.replay(capture).steps.single()

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, step.productionVerdict)
        assertEquals(5L, step.integerObjectiveLowerBound)
        assertTrue(step.hasCertifiedBound)
    }

    @Test
    fun `pivot budget reports a certified bound without promoting the candidate to optimum`() {
        val capture = LpCapture.capture(
            coveringModel(),
            LpReplaySettings(
                "pivot-bound",
                23L,
                LpReplaySolverKind.TABLEAU,
                componentSplit = false,
                pivotLimit = 1,
            ),
            listOf(LpReplayEvent.Solve()),
        )

        val step = LpReplay.replay(capture).steps.single()

        assertEquals(LpCandidateKind.FLOAT_BOUND, step.candidate)
        assertEquals(LpVerdict.CERTIFIED_BOUND, step.productionVerdict)
        assertTrue(step.hasCertifiedBound)
        assertFalse(step.hasFeasibleWitness)
        assertTrue(step.metrics.pivots <= 1)
    }

    @Test
    fun `known future events are persisted but rejected before execution`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 1L)
        val model = builder.build(Sense.MINIMIZE)
        val futureEvents = listOf<LpReplayEvent>(
            LpReplayEvent.BoundWrite(0, true, 1L, false, 2),
            LpReplayEvent.Push(1),
            LpReplayEvent.Pop(0),
            LpReplayEvent.RowActivation(0, false),
            LpReplayEvent.RowsAppended(LpCapturedModel.capture(model)),
            LpReplayEvent.ObjectiveSwap(LongArray(2), null, 0L, null),
            LpReplayEvent.Epoch(1L, 2L, 3L, 4L),
        )
        for (future in futureEvents) {
            val shaped = shapeFutureEvent(future)
            val capture = LpCapture.capture(
                model,
                persistentSettings("future"),
                listOf(LpReplayEvent.Solve(), shaped),
            )
            var validations = 0

            assertFails {
                LpReplay.replay(LpCapture.decode(capture.encode()), validator = { _, _ ->
                    validations++
                    LpIndependentCheck(LpIndependentValidation.VALIDATED, LpIndependentClaim.PROVED_OPTIMUM)
                })
            }
            assertEquals(0, validations, "${future::class.simpleName} executed a prefix before rejection")
        }
    }

    @Test
    fun `negative logical width rejects rebound replay before allocating an owner`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.LE, 5L)
        }.build(Sense.MINIMIZE)
        val capture = LpCapture.capture(
            model,
            persistentSettings("negative-logical-width"),
            listOf(LpReplayEvent.Solve(), LpReplayEvent.Rebind(longArrayOf(0L), longArrayOf(10L))),
        )
        capture.model.hasUpper[model.n] = true
        capture.model.upper[model.n] = -1L
        val decoded = LpCapture.decode(capture.encode())
        var owners = 0
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
                owners++
                error("unexpected owner allocation")
            }
        }

        val failure = assertFails { LpReplay.replay(decoded, context = LpSolveContext(factory)) }

        assertEquals("replay bound updates require valid exact source authority", failure.message)
        assertEquals(0, owners)
    }

    @Test
    fun `malformed model and unsupported continuous rebind fail before validation`() {
        val valid = LpCapturedModel.capture(LpBuilder().build(Sense.MINIMIZE))
        val malformed = LpCapturedModel(
            valid.n,
            valid.m,
            intArrayOf(1),
            valid.rowIdx,
            valid.colVal,
            valid.rhs,
            valid.cost,
            valid.upper,
            valid.hasUpper,
            valid.loShift,
            valid.objConstant,
            valid.sense,
            valid.tag,
            valid.rowGlobal,
            valid.rowStrict,
            valid.rowPremises,
            valid.flippedRhs,
            valid.probeClampedLo,
            valid.probeClampedHi,
            valid.colContinuous,
            valid.numericAuthority,
            valid.doubleView,
        )
        assertFails {
            LpReplay.replay(LpCapture(LP_CAPTURE_VERSION, persistentSettings("bad"), malformed, emptyList()))
        }

        assertFails {
            LpReplay.replay(
                LpCapture.capture(
                    valid.toModel(),
                    LpReplaySettings(
                        "tableau-refactor",
                        0L,
                        LpReplaySolverKind.TABLEAU,
                        componentSplit = false,
                        refactorUpdateLimit = DEFAULT_REFACTOR_UPDATE_LIMIT + 1,
                    ),
                    emptyList(),
                ),
            )
        }

        val integerBuilder = LpBuilder()
        integerBuilder.addVar(0L, 1L)
        val inconsistent = LpCapture.capture(
            integerBuilder.build(Sense.MINIMIZE),
            persistentSettings("bad-continuous-flag"),
            emptyList(),
        )
        inconsistent.model.colContinuous[0] = true
        assertFails { LpReplay.replay(inconsistent) }

        val realBuilder = LpBuilder()
        realBuilder.addRealVar(0.0, 1.0)
        val real = realBuilder.build(Sense.MINIMIZE)
        assertFails {
            LpReplay.replay(
                LpCapture.capture(
                    real,
                    persistentSettings("real-rebind"),
                    listOf(LpReplayEvent.Rebind(longArrayOf(0L), longArrayOf(1L))),
                ),
            )
        }
    }

    private fun persistentSettings(label: String): LpReplaySettings = LpReplaySettings(
        label,
        0x4c50573034L,
        LpReplaySolverKind.PERSISTENT,
        componentSplit = false,
    )

    private fun pivotModel(columns: Int): LpModel {
        val random = Random(7)
        val builder = LpBuilder()
        val vars = IntArray(columns) { builder.addVar(0L, 9L, random.nextLong(1L, 6L)) }
        repeat(columns / 2) { row ->
            builder.addRow(
                IntArray(4) { vars[(row * 3 + it) % columns] },
                LongArray(4) { 1L },
                Relation.GE,
                random.nextLong(2L, 8L),
            )
        }
        return builder.build(Sense.MINIMIZE)
    }

    private fun coveringModel(): LpModel {
        val builder = LpBuilder()
        val variables = IntArray(4) { builder.addVar(0L, 10L, cost = 1L) }
        builder.addRow(intArrayOf(variables[0], variables[1]), longArrayOf(1L, 1L), Relation.GE, 3L)
        builder.addRow(intArrayOf(variables[1], variables[2]), longArrayOf(1L, 1L), Relation.GE, 4L)
        builder.addRow(intArrayOf(variables[2], variables[3]), longArrayOf(1L, 1L), Relation.GE, 5L)
        builder.addRow(intArrayOf(variables[0], variables[3]), longArrayOf(1L, 1L), Relation.GE, 2L)
        return builder.build(Sense.MINIMIZE)
    }

    private fun shapeFutureEvent(event: LpReplayEvent): LpReplayEvent = when (event) {
        is LpReplayEvent.BoundWrite -> LpReplayEvent.BoundWrite(0, true, 1L, false, 2)
        is LpReplayEvent.RowActivation -> LpReplayEvent.RowActivation(0, false)
        else -> event
    }
}
