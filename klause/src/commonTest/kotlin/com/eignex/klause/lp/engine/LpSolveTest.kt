package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpSolveTest {
    @Test
    fun `supplied legacy certificates cannot raise an exact state bound`() {
        val legacy = LpBuilder().apply { addVar(5L, 10L, cost = 1L) }.build(Sense.MINIMIZE)
        val certificate = assertNotNull(integerCertify(legacy, doubleArrayOf()))
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(1L))),
        )
        val working = assertNotNull(LpExactState(source).toWorkingModel())

        val bound = tightObjectiveLowerBound(working, doubleArrayOf(), certificate)
        val accepted = certifiedTightObjectiveLowerBound(
            working,
            doubleArrayOf(),
            certificate,
            null,
            ProductionLpCertificationPolicy,
        )

        assertEquals(0.0, bound)
        assertEquals(0.0, accepted)
    }

    @Test
    fun `crossed bound witnesses honor proof acceptance policy`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(upper = ExactLpSide(zero)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(source)
        assertTrue(trail.assertBound(0, false, ExactLpSide(zero, strict = true), 7L))
        val working = assertNotNull(trail.state.toWorkingModel())
        val decline = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })

        val accepted = solveAndCertify(working)
        val rejected = solveAndCertify(working, context = decline)

        assertEquals(LpVerdict.INFEASIBLE, accepted.verdict)
        assertEquals(7L, assertNotNull(accepted.boundConflict).lower.witness)
        assertEquals(-2L, accepted.boundConflict.upper.witness)
        assertEquals(trail.state, assertNotNull(accepted.conflictSupport).state)
        assertEquals(LpVerdict.INDETERMINATE, rejected.verdict)
        assertNull(rejected.boundConflict)
        assertNull(rejected.conflictSupport)
    }

    @Test
    fun `recentered bound conflict retains source state and premises after pop`() {
        val zero = ExactLpNumber.of(0L)
        val lowerPremises = ExactLpPremises(emptyList(), listOf(11))
        val upperPremises = ExactLpPremises(emptyList(), listOf(12))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(upper = ExactLpSide(zero, premises = upperPremises)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(zero, strict = true, premises = lowerPremises), 7L))
        assertTrue(trail.recenter(listOf(ExactLpNumber.of(1L))))
        val state = trail.state

        val result = solveAndCertify(assertNotNull(state.toWorkingModel()))
        assertTrue(trail.pop(0))

        val support = assertNotNull(result.conflictSupport)
        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertEquals(state, support.state)
        assertEquals(ExactLpNumber.of(1L), support.state.model.column(0).origin)
        assertEquals(2L, support.state.boundRevision)
        assertEquals(1L, support.state.objectiveRevision)
        assertEquals(0L, support.state.popRevision)
        assertEquals(listOf(7L, -2L), support.sides.map { it.witness })
        assertEquals(listOf(lowerPremises, upperPremises), support.sides.map { it.side.premises })
        assertTrue(support.sides.all { it.side.number.value == BigFraction.MINUS_ONE })
        assertTrue(support.rows.isEmpty())
        assertEquals(1L, trail.state.popRevision)
        assertNull(trail.state.conflict)
    }

    @Test
    fun `cancellation during direct conflict acceptance withholds the proof`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(zero)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        var cancelled = false
        val context = LpSolveContext(
            certificationPolicy = LpCertificationPolicy { _, success ->
                cancelled = true
                success
            },
        )

        val result = solveAndCertify(source, cancellation = Cancellation { cancelled }, context = context)

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(result.boundConflict)
        assertNull(result.conflictSupport)
    }

    @Test
    fun `exact objective units and source origins survive certification`() {
        val third = BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3))
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(ExactLpNumber.of(third)), ExactLpSide(ExactLpNumber.of(2L))),
                    origin = ExactLpNumber.of(10L),
                    integral = false,
                    tag = 17,
                ),
            ),
            emptyList(),
            ExactLpObjective(
                listOf(ExactLpNumber.of(6L)),
                constant = ExactLpNumber.of(9L),
                scale = ExactLpNumber.of(3L),
                externalConstant = ExactLpNumber.of(4L),
                sense = Sense.MAXIMIZE,
            ),
        )

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(BigFraction.ofLong(10L) + third, assertNotNull(result.exactPrimal).single())
        assertEquals(BigFraction.ofLong(7L) + third + third, result.lowerBound)
        assertNull(result.integerObjectiveLowerBound)
        assertNull(result.certificate)
        assertEquals(Sense.MAXIMIZE, assertNotNull(result.bound?.support).state.model.objective.sense)
    }

    @Test
    fun `strict closure vertex proves only a bound`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(ExactLpNumber.of(1L))))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.CERTIFIED_BOUND, result.verdict)
        assertEquals(BigFraction.ZERO, result.lowerBound)
        assertNull(result.witness)
        assertNull(result.farkasRay)
    }

    @Test
    fun `binary and parsed equations retain different acceptance authority`() {
        val tenth = ExactLpNumber.of(BigFraction.of(BigInteger.ONE, BigInteger.fromInt(10)))
        val zero = ExactLpNumber.of(0L)
        for (rhs in listOf(tenth, ExactLpNumber.ofIeee(0.1))) {
            val model = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
                listOf(rhs),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(tenth), ExactLpSide(tenth))),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(zero, zero)),
            )

            val result = solveAndCertify(model)

            if (rhs == tenth) {
                assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
                assertEquals(listOf(tenth.value), result.exactPrimal)
            } else {
                assertNull(result.witness)
                assertTrue(result.verdict != LpVerdict.ATTAINED_OPTIMUM)
            }
        }
    }

    @Test
    fun `unrepresentable exact costs decline before engine creation`() {
        val huge = ExactLpNumber.of(BigFraction.of(BigInteger.ONE shl 4096, BigInteger.ONE))
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(huge)),
        )
        val context = LpSolveContext(
            engineFactory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newGeneralSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    pricing: LpPricingOptions,
                ): LpSolver = error("engine entered")
            },
        )

        val result = solveAndCertify(model, context = context)

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(result.float)
        assertNull(exactLpStateKey(model))
    }

    @Test
    fun `weaker witnesses invalidate counters while old lazy bounds retain their source state`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(1L))),
        )
        val trail = LpBoundTrail(source)
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 1L))
        val model = assertNotNull(trail.state.toWorkingModel())
        val counters = LpCounterResults()
        val result = solveAndCertify(model, counterResults = counters)

        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 2L))
        val next = assertNotNull(trail.state.toWorkingModel())

        assertNull(counters.read(next, ProductionLpCertificationPolicy))
        assertEquals(3.0, result.safeLowerBound)
        assertEquals(1L, assertNotNull(result.bound?.support).sides.single().witness)
        assertEquals(2, trail.state.assertions.size)
        assertNull(integerCertify(next, doubleArrayOf()))
        assertNull(rationalizeToIntegerModel(next, outwardRealUppers = true))
    }

    @Test
    fun `short premise metadata declines keys and safe snapshots without throwing`() {
        val model = LpModel(
            1, 1, Csc(intArrayOf(0, 1), intArrayOf(0), longArrayOf(1L)),
            longArrayOf(1L), longArrayOf(1L, 0L), longArrayOf(1L, 0L), booleanArrayOf(true, false),
            longArrayOf(0L), 0L, Sense.MINIMIZE, intArrayOf(0), rowPremises = emptyArray(),
        )

        assertNull(LpCapturedModel.captureOrNull(model))
        assertNull(exactLpStateKey(model))
        assertNull(solveAndCertify(model).safeLowerBound)
    }

    @Test
    fun `raw objective mutation invalidates counters but not an existing lazy snapshot`() {
        val model = LpBuilder().apply { addVar(0L, 3L, cost = 1L) }.build(Sense.MINIMIZE)
        val cache = LpCounterResults()
        val result = solveAndCertify(model, counterResults = cache)

        model.cost[0] = -1L

        assertNull(cache.read(model, ProductionLpCertificationPolicy))
        assertTrue(assertNotNull(result.safeLowerBound) > -1.0)
        assertEquals(BigFraction.ofLong(-3L), solveAndCertify(model).lowerBound)
    }

    @Test
    fun `an uncapturable remember attempt retires earlier evidence`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        val cache = LpCounterResults()
        val result = solveAndCertify(model, counterResults = cache)
        model.colContinuous[0] = true

        cache.remember(model, result, ProductionLpCertificationPolicy)
        model.colContinuous[0] = false

        assertTrue(cache.storageDeclined)
        assertNull(cache.read(model, ProductionLpCertificationPolicy))
    }

    @Test
    fun `uncapturable legacy input withholds key and lazy safe bound`() {
        val model = LpBuilder().apply { addVar(0L, 3L, cost = 1L) }.build(Sense.MINIMIZE)
        model.colContinuous[0] = true

        val result = solveAndCertify(model)

        assertNull(exactLpStateKey(model))
        assertNull(result.safeLowerBound)
    }

    @Test
    fun `same double with different exact authority cannot reuse counters`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(-1L))),
        )
        val nextBound = ExactLpNumber.of(
            BigFraction.ONE + BigFraction.of(
                BigInteger.ONE,
                BigInteger.ONE shl 54,
            ),
        )
        val next = model.copy(
            columns = listOf(
                model.column(0).copy(
                    bounds = ExactLpBounds(ExactLpSide(zero), ExactLpSide(nextBound)),
                ),
            ),
        )
        val cache = LpCounterResults()
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, solveAndCertify(model, counterResults = cache).verdict)

        val result = solveAndCertify(next, counterResults = cache)

        assertEquals(one.value.toDouble(), nextBound.value.toDouble())
        assertTrue(!model.sameAuthority(next))
        assertNotNull(exactLpStateKey(model))
        assertNotNull(exactLpStateKey(next))
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(nextBound.value.negated(), result.lowerBound)
        assertTrue(assertNotNull(result.safeLowerBound) <= nextBound.value.negated().toDouble())
        assertTrue(!cache.storageDeclined)
        assertNull(cache.read(assertNotNull(model.toLegacy()), ProductionLpCertificationPolicy))
    }

    @Test
    fun `general exact metadata reaches the engine without losing authority`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L))))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val variants = listOf(
            model.copy(columns = listOf(model.column(0).copy(integral = false))),
            model.copy(
                columns = listOf(
                    model.column(0).copy(
                        bounds = ExactLpBounds(
                            ExactLpSide(zero, premises = ExactLpPremises(emptyList(), listOf(7))),
                        ),
                    ),
                ),
            ),
            model.copy(objective = ExactLpObjective(listOf(zero), scale = ExactLpNumber.of(2L))),
            model.copy(objective = ExactLpObjective(listOf(zero), externalConstant = ExactLpNumber.of(1L))),
            model.copy(objective = ExactLpObjective(listOf(ExactLpNumber.ofIeee(0.0)))),
        )
        var calls = 0
        val context = LpSolveContext(
            engineFactory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newGeneralSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    pricing: LpPricingOptions,
                ): LpSolver {
                    calls++
                    assertNotNull(model.exactState)
                    return ProductionLpEngineFactory.newGeneralSolver(model, cancellation, pricing)
                }
                override fun newComponentSolver(
                    model: LpModel,
                    parts: List<LpNeighborhood>,
                    solvers: List<LpSolver>,
                    isolated: IntArray,
                ): ComponentLpSolverCapability = error("unsupported component entry")
            },
        )
        for (variant in variants) {
            assertNull(variant.toLegacy())
            assertNotNull(exactLpStateKey(variant))
            val result = solveAndCertify(variant, context = context)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
            assertTrue(assertNotNull(result.float).exactState?.model?.sameAuthority(variant) == true)
            assertNotNull(result.bound)
            assertNotNull(result.witness)
            assertNotNull(result.safeLowerBound)
        }
        assertEquals(variants.size, calls)
    }

    @Test
    fun `a feasible LP certifies an optimum matching the float optimum`() {
        // minimize x + y  subject to  x + y >= 3,  0 <= x, y <= 5.
        val b = LpBuilder()
        b.addVar(0L, 5L, cost = 1L)
        b.addVar(0L, 5L, cost = 1L)
        b.addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.GE, 3L)
        val model = b.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertNotNull(result.certificate)
        assertNull(result.farkasRay)
        assertEquals(3L, result.integerObjectiveLowerBound)
        val float = assertNotNull(result.float)
        val safe = assertNotNull(result.safeLowerBound)
        assertTrue(safe <= float.objective + 1e-6, "safe bound $safe exceeds the optimum ${float.objective}")
    }

    @Test
    fun `an infeasible LP is certified infeasible by a Farkas ray`() {
        // 0 <= x <= 1 with x >= 2 has no feasible point.
        val b = LpBuilder()
        b.addVar(0L, 1L, cost = 1L)
        b.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        val model = b.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertNotNull(result.farkasRay)
        assertNull(result.float)
        assertNull(result.certificate)
    }

    @Test
    fun `a nonoptimal feasible point retains its exact witness and a separate bound`() {
        val model = LpBuilder().apply { addVar(0L, 5L, cost = 1L) }.build(Sense.MINIMIZE)
        val hint = FloatLpResult(
            Basis(intArrayOf(), arrayOf(VarStatus.AT_UPPER)),
            0.0,
            doubleArrayOf(),
            doubleArrayOf(5.0),
        )

        val result = newLpSolver(model).use { certifyLpResult(model, it, hint) }

        assertEquals(LpVerdict.FEASIBLE, result.verdict)
        assertEquals(listOf(BigFraction.ofLong(5L)), result.exactPrimal)
        assertEquals(BigFraction.ofLong(5L), result.witness?.objective)
        assertEquals(BigFraction.ZERO, result.lowerBound)
    }

    @Test
    fun `a bound on an infeasible relaxation proves neither a point nor attainment`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val hint = FloatLpResult(
            Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC)),
            2.0,
            doubleArrayOf(-1.0),
            doubleArrayOf(0.0),
        )

        val result = newLpSolver(model).use { certifyLpResult(model, it, hint) }

        assertEquals(LpVerdict.CERTIFIED_BOUND, result.verdict)
        assertEquals(BigFraction.ofLong(2L), result.lowerBound)
        assertNull(result.exactPrimal)
        assertEquals(LpVerdict.INFEASIBLE, solveAndCertify(model).verdict)
    }

    @Test
    fun `an interrupted hint is bounded using true costs and the original constant`() {
        val model = LpBuilder().apply { addVar(2L, 5L, cost = 3L) }.build(Sense.MINIMIZE)
        val hint = FloatLpResult(
            Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)),
            999.0,
            doubleArrayOf(),
            doubleArrayOf(999.0),
            optimal = false,
        )
        val boundOnly = LpCertificationPolicy { certifier, success -> success && certifier == LpCertifier.INTEGER }

        val result = newLpSolver(model).use {
            certifyLpResult(model, it, hint, Cancellation { true }, policy = boundOnly)
        }

        assertEquals(LpVerdict.CERTIFIED_BOUND, result.verdict)
        assertEquals(BigFraction.ofLong(6L), result.lowerBound)
        assertNull(result.witness)
    }

    @Test
    fun `a real lower bound stays rational and proves attainment only by exact equality`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 10.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.GE, 3.0)
        }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)
        val point = assertNotNull(result.exactPrimal).single()

        assertEquals(BigFraction.ofDouble(1.5), result.lowerBound)
        assertEquals(BigFraction.ofLong(3L), BigFraction.ofLong(2L) * point)
        assertEquals(point, result.witness?.objective)
        assertNull(result.integerObjectiveLowerBound)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
    }

    @Test
    fun `a strict infimum retains an interior witness without claiming attainment`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)
        val point = assertNotNull(result.exactPrimal).single()

        assertTrue(point > BigFraction.ZERO && point <= BigFraction.ONE)
        assertEquals(point, result.witness?.objective)
        assertEquals(BigFraction.ZERO, result.lowerBound)
        assertEquals(LpVerdict.FEASIBLE, result.verdict)
    }

    @Test
    fun `an exact feasible counter suppresses a repeated infeasibility candidate`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        val cache = LpCounterResults()
        val first = solveAndCertify(model, counterResults = cache)
        val repeated = object : LpSolver {
            override val infeasibleRay: DoubleArray? get() = error("an exact point already refutes this claim")
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        }

        val result = certifyLpResult(model, repeated, null, Cancellation { true }, counterResults = cache)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(first.exactPrimal, result.exactPrimal)
        assertEquals(BigFraction.ZERO, result.witness?.objective)
    }

    @Test
    fun `candidate rejection is not cached as a model counter result`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val hint = FloatLpResult(
            Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC)),
            0.0,
            doubleArrayOf(Double.NaN),
            doubleArrayOf(0.0),
        )
        val cache = LpCounterResults()
        val rejected = newLpSolver(model).use { certifyLpResult(model, it, hint, counterResults = cache) }

        assertEquals(LpVerdict.INDETERMINATE, rejected.verdict)
        assertNull(cache.read(model, ProductionLpCertificationPolicy))
        val accepted = solveAndCertify(model, counterResults = cache)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, accepted.verdict)
        assertEquals(listOf(BigFraction.ofLong(2L)), accepted.exactPrimal)
    }

    @Test
    fun `sibling bounds cannot reuse an exact point from another node`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 3L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val cache = LpCounterResults()
        assertNotNull(solveAndCertify(model, counterResults = cache).witness)
        val sibling = model.rebind(longArrayOf(0L), longArrayOf(1L))

        assertNull(cache.read(sibling, ProductionLpCertificationPolicy))
        assertEquals(LpVerdict.INFEASIBLE, solveAndCertify(sibling, counterResults = cache).verdict)
    }

    @Test
    fun `objective replacement invalidates only the replacement counter authority`() {
        val model = LpBuilder().apply { addVar(0L, 3L, cost = 1L) }.build(Sense.MINIMIZE)
        val cache = LpCounterResults()
        assertEquals(BigFraction.ZERO, solveAndCertify(model, counterResults = cache).lowerBound)
        val next = model.withSingleColumnObjective(0, -1L, 0)

        assertNotNull(cache.read(model, ProductionLpCertificationPolicy))
        assertNull(cache.read(next, ProductionLpCertificationPolicy))
        val result = solveAndCertify(next, counterResults = cache)
        assertEquals(BigFraction.ofLong(-3L), result.lowerBound)
        assertEquals(listOf(BigFraction.ofLong(3L)), result.exactPrimal)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
    }

    @Test
    fun `changing source premises invalidates exact counter authority`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 1.0)
        }.build(Sense.MINIMIZE)
        val cache = LpCounterResults()
        solveAndCertify(model, counterResults = cache)
        model.rowPremises[0] = LpRowPremises(intArrayOf(), booleanArrayOf(), longArrayOf(), intArrayOf(4))
        model.rowGlobal[0] = false

        assertNull(cache.read(model, ProductionLpCertificationPolicy))
    }

    @Test
    fun `an unsupported unbounded producer retains feasibility without claiming unboundedness`() {
        val model = LpBuilder().apply { addOpenAboveVar(0L, cost = -1L) }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.FEASIBLE, result.verdict)
        assertEquals(listOf(BigFraction.ZERO), result.exactPrimal)
        assertNull(result.unboundedness)
        assertNull(result.lowerBound)
    }

    @Test
    fun `unboundedness requires a feasible point and an improving recession direction`() {
        val model = LpBuilder().apply { addOpenAboveVar(0L, cost = -1L) }.build(Sense.MINIMIZE)
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override val recessionDirection = doubleArrayOf(1.0)
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        }

        val result = certifyLpResult(model, solver, null)
        val proof = assertNotNull(result.unboundedness)

        assertEquals(LpVerdict.UNBOUNDED, result.verdict)
        assertTrue(proof.witness.primal.single() >= BigFraction.ZERO)
        assertTrue(proof.direction.single() > BigFraction.ZERO)
        assertTrue(BigFraction.MINUS_ONE * proof.direction.single() < BigFraction.ZERO)
        assertNull(checkedLpUnboundedness(model, proof.witness, doubleArrayOf(-1.0)))
    }

    @Test
    fun `a rational witness is checked against exact IEEE input rather than guessed decimals`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(3.0), Relation.EQ, 0.3)
        }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)
        val point = assertNotNull(result.exactPrimal).single()

        assertEquals(BigFraction.ofDouble(0.3), BigFraction.ofLong(3L) * point)
        assertTrue(point != BigFraction.ofDouble(point.toDouble()))
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
    }

    @Test
    fun `an improving cone on an infeasible model cannot establish unboundedness`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L)
            addOpenAboveVar(0L, cost = -1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override val recessionDirection = doubleArrayOf(0.0, 1.0)
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        }

        val result = certifyLpResult(model, solver, null)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertNull(result.exactPrimal)
        assertNull(result.unboundedness)
    }

    @Test
    fun `oversized model identity explicitly declines counter storage`() {
        val model = LpBuilder().apply { repeat(257) { addVar(0L, 1L) } }.build(Sense.MINIMIZE)
        val cache = LpCounterResults()

        val witness = assertNotNull(checkedLpWitness(model, List(model.n) { BigFraction.ZERO }))
        val result = CertifiedLpResult(null, null, witness, null, null, true, { null })

        cache.remember(model, result, ProductionLpCertificationPolicy)

        assertTrue(cache.storageDeclined)
        assertNull(cache.read(model, ProductionLpCertificationPolicy))
    }

    @Test
    fun `a reconstructed dual establishes the exact objective of a scaled equality`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(10.0), Relation.EQ, 5.0)
        }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)
        val point = assertNotNull(result.exactPrimal).single()

        assertEquals(BigFraction.ofLong(5L), BigFraction.ofLong(10L) * point)
        assertEquals(point, result.lowerBound)
        assertEquals(BigFraction.ofDouble(0.5), result.witness?.objective)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
    }

    @Test
    fun `exact tiny dual retains finite support that rounding to zero loses`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, null, cost = -1e-10)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 1.0)
        }.build(Sense.MINIMIZE)
        val hint = FloatLpResult(
            Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER)),
            -1e-10,
            doubleArrayOf(-1e-10),
            doubleArrayOf(1.0),
        )

        val attempts = ArrayList<Boolean>()
        val policy = LpCertificationPolicy { certifier, success ->
            if (certifier == LpCertifier.RATIONAL) attempts += success
            success
        }
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?) = hint
            override fun solvePrimal(warm: Basis?) = hint
        }
        val result = certifyLpResult(model, solver, hint, policy = policy)

        assertEquals(0L, assertNotNull(reconstructRational(-1e-10)).numerator)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(listOf(BigFraction.ONE), result.exactPrimal)
        assertEquals(BigFraction.ofDouble(-1e-10), result.witness?.objective)
        assertEquals(result.witness?.objective, result.lowerBound)
        assertNull(exactLagrangian(model, listOf(BigFraction.ZERO)))
        assertEquals(listOf(true, true), attempts)
    }

    @Test
    fun `a ray over guessed decimal rows cannot refute the original source system`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, null)
            val y = addRealVar(0.0, null)
            addRealRow(intArrayOf(x, y), doubleArrayOf(1.0000000001, -1.0), Relation.EQ, 1.0)
            addRealRow(intArrayOf(x, y), doubleArrayOf(1.0, -1.0), Relation.EQ, 0.0)
        }.build(Sense.MINIMIZE)
        val candidate = doubleArrayOf(1.0, -1.0)
        val solver = object : LpSolver {
            override val infeasibleRay = candidate
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        }

        assertNull(certifyLpFarkas(model, candidate))
        val result = certifyLpResult(model, solver, null)
        val point = assertNotNull(result.exactPrimal)

        assertEquals(LpVerdict.FEASIBLE, result.verdict)
        assertTrue(point.all { it >= BigFraction.ZERO })
        assertEquals(point[0], point[1])
        assertEquals(BigFraction.ONE, checkNotNull(BigFraction.ofDouble(1.0000000001)) * point[0] - point[1])
    }

    @Test
    fun `strictness changes invalidate a remembered boundary witness`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0)
        }.build(Sense.MINIMIZE)
        val cache = LpCounterResults()
        assertEquals(BigFraction.ZERO, solveAndCertify(model, counterResults = cache).witness?.objective)
        model.rowStrict[0] = true

        assertNull(cache.read(model, ProductionLpCertificationPolicy))
        val result = solveAndCertify(model, counterResults = cache)
        assertEquals(LpVerdict.FEASIBLE, result.verdict)
        assertTrue(assertNotNull(result.exactPrimal).single() > BigFraction.ZERO)
    }

    @Test
    fun `strict infeasibility carries exact row multipliers and all blocking sides`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)
        val conflict = assertNotNull(result.rationalConflict)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertEquals(listOf(0), conflict.rows.toList())
        assertEquals(listOf(BigFraction.ONE), conflict.multipliers)
        assertTrue(conflict.bounds.any { it.column == 0 && !it.upper })
        assertTrue(conflict.bounds.any { it.column == 1 && !it.upper })
        assertTrue(checkedLpConflict(model, conflict))
    }

    @Test
    fun `contradictory near zero equalities cannot seed counter witnesses`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.0, premiseLits = intArrayOf(7))
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 1e-10, premiseLits = intArrayOf(9))
        }.build(Sense.MINIMIZE)
        val counters = LpCounterResults()

        val result = solveAndCertify(model, counterResults = counters)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertNull(result.witness)
        assertNull(counters.read(model, ProductionLpCertificationPolicy)?.witness)
        val conflict = assertNotNull(result.rationalConflict)
        assertEquals(setOf(0, 1), conflict.rows.toSet())
        var lhs = BigFraction.ZERO
        var rhs = BigFraction.ZERO
        for (entry in conflict.rows.indices) {
            val row = conflict.rows[entry]
            lhs += conflict.multipliers[entry]
            rhs += conflict.multipliers[entry] * assertNotNull(BigFraction.ofDouble(if (row == 0) 0.0 else 1e-10))
        }
        assertEquals(BigFraction.ZERO, lhs)
        assertTrue(rhs < BigFraction.ZERO)
        assertEquals(setOf(7, 9), conflict.rows.map { model.rowPremises[it]!!.boolLits.single() }.toSet())
    }

    @Test
    fun `reconstruction alone can certify thirds through the live policy seam`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(3L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val onlyReconstruction = LpSolveContext(
            certificationPolicy = LpCertificationPolicy { route, success -> route == LpCertifier.RATIONAL && success },
        )

        val result = solveAndCertify(model, context = onlyReconstruction)

        val point = assertNotNull(result.witness).primal.single()
        assertEquals(BigFraction.ONE, BigFraction.ofLong(3L) * point)
        assertEquals(point, result.lowerBound)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertTrue(assertNotNull(result.reconstruction).pointSuccesses > 0)
    }

    @Test
    fun `policy rejection withholds all reconstructed proof packages`() {
        val model = LpBuilder().apply { addVar(0L, 1L, cost = 1L) }.build(Sense.MINIMIZE)
        val reject = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })

        val result = solveAndCertify(model, context = reject)

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(result.witness)
        assertNull(result.bound)
        assertTrue(assertNotNull(result.reconstruction).pointSuccesses > 0)
        assertTrue(result.reconstruction.dualSuccesses > 0)
    }

    @Test
    fun `completed exact reconstruction survives cancellation after policy acceptance`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        var cancelled = false
        val context = LpSolveContext(
            certificationPolicy = LpCertificationPolicy { route, success ->
                if (route == LpCertifier.RATIONAL && success) cancelled = true
                success
            },
        )

        val state = LpExactState(source)
        val candidate = FloatLpResult(
            Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)),
            0.0,
            doubleArrayOf(),
            doubleArrayOf(0.0),
            exactState = state,
        )
        val solver = object : LpSolver {
            override val solvedExactState = state
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?) = candidate
            override fun solvePrimal(warm: Basis?) = candidate
        }
        val result = certifyLpResult(
            assertNotNull(state.toWorkingModel()),
            solver,
            candidate,
            Cancellation { cancelled },
            policy = context.certificationPolicy,
        )

        assertTrue(cancelled)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(BigFraction.ZERO, result.lowerBound)
        assertEquals(listOf(BigFraction.ZERO), result.exactPrimal)
        assertNotNull(result.bound?.support)
    }

    @Test
    fun `stale exact candidate cannot enter reconstruction after a trail edit`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        val trail = LpBoundTrail(source)
        val old = trail.state
        val candidate = FloatLpResult(
            Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)),
            0.0,
            doubleArrayOf(),
            doubleArrayOf(0.0),
            exactState = old,
        )
        val solver = object : LpSolver {
            override val solvedExactState = old
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?) = candidate
            override fun solvePrimal(warm: Basis?) = candidate
        }
        assertTrue(trail.assertBound(0, false, ExactLpSide(one), 5L))

        val result = certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, candidate)

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(result.reconstruction)
        assertNull(result.witness)
    }

    @Test
    fun `rational ray beyond Long reaches the live exact conflict surface`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val huge = assertNotNull(BigFraction.ofDouble(1e30))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one), ExactLpEntry(1, ExactLpNumber.of(huge.negated())))),
            listOf(one, zero),
            listOf(
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow(), ExactLpRow()),
            ExactLpObjective(listOf(zero, zero, zero)),
        )
        val state = LpExactState(source)
        val solver = object : LpSolver {
            override val solvedExactState = state
            override val infeasibleRay = doubleArrayOf(1e30, 1.0)
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        }

        val result = certifyLpResult(assertNotNull(state.toWorkingModel()), solver, null)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        val conflict = assertNotNull(result.rationalConflict)
        assertEquals(BigFraction.ZERO, conflict.multipliers[0] - huge * conflict.multipliers[1])
        assertTrue(conflict.multipliers[0] < BigFraction.ZERO)
        assertNotNull(result.conflictSupport)
        assertTrue(assertNotNull(result.reconstruction).raySuccesses > 0)
    }

    @Test
    fun `cancelled zero row fallback records a decline without creating a fresh witness`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        val attempts = ArrayList<Pair<LpCertifier, Boolean>>()
        val policy = LpCertificationPolicy { route, success ->
            attempts += route to success
            success
        }
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        }

        val result = certifyLpResult(model, solver, null, Cancellation { true }, policy = policy)

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(result.witness)
        assertEquals(listOf(LpCertifier.RATIONAL to false), attempts)
    }
}
