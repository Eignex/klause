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
    fun `short premise metadata declines keys and safe snapshots without throwing`() {
        val model = LpModel(1, 1, Csc(intArrayOf(0, 1), intArrayOf(0), longArrayOf(1L)),
            longArrayOf(1L), longArrayOf(1L, 0L), longArrayOf(1L, 0L), booleanArrayOf(true, false),
            longArrayOf(0L), 0L, Sense.MINIMIZE, intArrayOf(0), rowPremises = emptyArray())

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
        val model = ExactLpModel(listOf(emptyList()), emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(), ExactLpObjective(listOf(ExactLpNumber.of(-1L))))
        val nextBound = ExactLpNumber.of(BigFraction.ONE + BigFraction.of(
            BigInteger.ONE, BigInteger.ONE shl 54))
        val next = model.copy(columns = listOf(model.column(0).copy(
            bounds = ExactLpBounds(ExactLpSide(zero), ExactLpSide(nextBound)))))
        val cache = LpCounterResults()
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, solveAndCertify(model, counterResults = cache).verdict)

        val result = solveAndCertify(next, counterResults = cache)

        assertEquals(one.value.toDouble(), nextBound.value.toDouble())
        assertTrue(!model.sameAuthority(next))
        assertNotNull(exactLpStateKey(model))
        assertNull(exactLpStateKey(next))
        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(result.safeLowerBound)
        assertTrue(cache.storageDeclined)
        assertNull(cache.read(assertNotNull(model.toLegacy()), ProductionLpCertificationPolicy))
    }

    @Test
    fun `unsupported exact metadata declines before engine injection`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(listOf(emptyList()), emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L))))),
            emptyList(), ExactLpObjective(listOf(zero)))
        val variants = listOf(
            model.copy(columns = listOf(model.column(0).copy(integral = false))),
            model.copy(columns = listOf(model.column(0).copy(bounds = ExactLpBounds(
                ExactLpSide(zero, premises = ExactLpPremises(emptyList(), listOf(7))))))),
            model.copy(objective = ExactLpObjective(listOf(zero), scale = ExactLpNumber.of(2L))),
            model.copy(objective = ExactLpObjective(listOf(zero), externalConstant = ExactLpNumber.of(1L))),
            model.copy(objective = ExactLpObjective(listOf(ExactLpNumber.ofIeee(0.0)))),
        )
        val context = LpSolveContext(engineFactory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newGeneralSolver(model: LpModel, cancellation: Cancellation): LpSolver = error("unsupported engine entry")
            override fun newComponentSolver(
                model: LpModel, parts: List<LpNeighborhood>, solvers: List<LpSolver>, isolated: IntArray,
            ): ComponentLpSolverCapability = error("unsupported component entry")
        })
        for (variant in variants) {
            assertNull(variant.toLegacy())
            assertNull(exactLpStateKey(variant))
            val result = solveAndCertify(variant, context = context)
            assertEquals(LpVerdict.INDETERMINATE, result.verdict)
            assertNull(result.float)
            assertNull(result.bound)
            assertNull(result.witness)
            assertNull(result.safeLowerBound)
        }
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
    fun `near zero reconstruction cannot use a missing upper bound as finite support`() {
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
        assertEquals(LpVerdict.FEASIBLE, result.verdict)
        assertEquals(listOf(BigFraction.ONE), result.exactPrimal)
        assertEquals(BigFraction.ofDouble(-1e-10), result.witness?.objective)
        assertTrue(assertNotNull(result.lowerBound) < assertNotNull(result.witness).objective)
        assertEquals(listOf(false), attempts)
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
}
