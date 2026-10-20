package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparseBasisPointTest {
    @Test
    fun `logical basic columns should leave only the structural equation to solve`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(-10.0, 1000.0)
        repeat(130) { row -> builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, row + 1.0) }
        val model = builder.build(Sense.MINIMIZE)
        val headings = IntArray(model.m) { if (it == 0) x else model.slackCol(it) }
        val statuses = Array(model.numVars) { VarStatus.BASIC }.also { it[model.slackCol(0)] = VarStatus.AT_LOWER }
        val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), Cancellation.Never)

        val result = recoverSparseBasisPoint(model, Basis(headings, statuses), meter)

        assertEquals(listOf(BigFraction.ONE), assertNotNull(result.witness).primal)
        assertEquals(1, result.peeled)
        assertEquals(0, result.core)
    }

    @Test
    fun `a long triangular structural basis should peel nonunit pivots exactly`() {
        val builder = LpBuilder()
        val columns = IntArray(130) { builder.addRealVar(0.0, 2.0) }
        builder.addRealRow(intArrayOf(columns[0]), doubleArrayOf(2.0), Relation.EQ, 2.0)
        for (index in 1 until columns.size) {
            builder.addRealRow(
                intArrayOf(columns[index], columns[index - 1]), doubleArrayOf(2.0, -1.0), Relation.EQ, 1.0,
            )
        }
        val model = builder.build(Sense.MINIMIZE)
        val statuses = Array(model.numVars) { if (it < model.n) VarStatus.BASIC else VarStatus.FIXED }
        val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), Cancellation.Never)

        val result = recoverSparseBasisPoint(model, Basis(columns, statuses), meter)

        assertTrue(assertNotNull(result.witness).primal.all { it == BigFraction.ONE })
        assertEquals(130, result.peeled)
        assertEquals(0, result.core)
    }

    @Test
    fun `a coupled residual should use exact factorization after logical reduction`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 10.0)
        val y = builder.addRealVar(0.0, 10.0)
        builder.addRealRow(intArrayOf(x, y), doubleArrayOf(1.0, 1.0), Relation.EQ, 3.0)
        builder.addRealRow(intArrayOf(x, y), doubleArrayOf(1.0, -1.0), Relation.EQ, 1.0)
        repeat(128) { builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, it + 10.0) }
        val model = builder.build(Sense.MINIMIZE)
        val headings = IntArray(model.m) { if (it < 2) 1 - it else model.slackCol(it) }
        val statuses = Array(model.numVars) { VarStatus.BASIC }.also {
            it[model.slackCol(0)] = VarStatus.FIXED
            it[model.slackCol(1)] = VarStatus.FIXED
        }
        val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), Cancellation.Never)

        val result = recoverSparseBasisPoint(model, Basis(headings, statuses), meter)

        assertEquals(listOf(BigFraction.ofLong(2), BigFraction.ONE), assertNotNull(result.witness).primal)
        assertEquals(0, result.peeled)
        assertEquals(2, result.core)
    }

    @Test
    fun `upper nonbasic seats and coordinate shifts should retain source values`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(1.0, 10.0)
        val y = builder.addRealVar(2.0, 7.0)
        builder.addRealRow(intArrayOf(x, y), doubleArrayOf(2.0, 1.0), Relation.EQ, 13.0)
        val model = builder.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(x), arrayOf(VarStatus.BASIC, VarStatus.AT_UPPER, VarStatus.FIXED))
        val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), Cancellation.Never)

        val result = recoverSparseBasisPoint(model, basis, meter)

        assertEquals(listOf(BigFraction.ofLong(3), BigFraction.ofLong(7)), assertNotNull(result.witness).primal)
    }

    @Test
    fun `invalid headings and nonbasic statuses should withhold a point`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 1.0)
        val model = builder.build(Sense.MINIMIZE)
        val variants = listOf(
            Basis(intArrayOf(x, x), arrayOf(VarStatus.BASIC, VarStatus.FIXED)),
            Basis(intArrayOf(model.numVars), arrayOf(VarStatus.BASIC, VarStatus.FIXED)),
            Basis(intArrayOf(x), arrayOf(VarStatus.BASIC, VarStatus.BASIC)),
            Basis(intArrayOf(model.slackCol(0)), arrayOf(VarStatus.FREE, VarStatus.BASIC)),
        )
        for (basis in variants) {
            val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), Cancellation.Never)

            val result = recoverSparseBasisPoint(model, basis, meter)

            assertNull(result.witness)
        }
    }

    @Test
    fun `a singular coupled residual should withhold a point`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0)
        val y = builder.addRealVar(0.0, 2.0)
        builder.addRealRow(intArrayOf(x, y), doubleArrayOf(1.0, 1.0), Relation.EQ, 1.0)
        builder.addRealRow(intArrayOf(x, y), doubleArrayOf(2.0, 2.0), Relation.EQ, 2.0)
        val model = builder.build(Sense.MINIMIZE)
        val statuses = Array(model.numVars) { if (it < model.n) VarStatus.BASIC else VarStatus.FIXED }
        val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), Cancellation.Never)

        val result = recoverSparseBasisPoint(model, Basis(intArrayOf(x, y), statuses), meter)

        assertNull(result.witness)
        assertEquals("SINGULAR", result.outcome)
    }

    @Test
    fun `a solved basis outside a structural bound should withhold a point`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 2.0)
        val model = builder.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(x), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), Cancellation.Never)

        val result = recoverSparseBasisPoint(model, basis, meter)

        assertNull(result.witness)
        assertEquals("REJECTED", result.outcome)
    }

    @Test
    fun `a solved basis on a strict boundary should withhold a point`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0, strict = true)
        val model = builder.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(x), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
        val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), Cancellation.Never)

        val result = recoverSparseBasisPoint(model, basis, meter)

        assertNull(result.witness)
        assertEquals("REJECTED", result.outcome)
    }

    @Test
    fun `an irreducible residual should retain the existing factor dimension limit`() {
        val builder = LpBuilder()
        val columns = IntArray(129) { builder.addRealVar(0.0, 2.0) }
        for (index in columns.indices) {
            builder.addRealRow(intArrayOf(columns[index], columns[(index + 1) % columns.size]),
                doubleArrayOf(1.0, 1.0), Relation.EQ, 2.0)
        }
        val model = builder.build(Sense.MINIMIZE)
        val statuses = Array(model.numVars) { if (it < model.n) VarStatus.BASIC else VarStatus.FIXED }
        val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), Cancellation.Never)

        val result = recoverSparseBasisPoint(model, Basis(columns, statuses), meter)

        assertNull(result.witness)
        assertEquals("CORE_DIMENSION", result.outcome)
        assertEquals(129, result.core)
    }

    @Test
    fun `resource limits should stop sparse basis recovery without a point`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1e50)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 1e40)
        val model = builder.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(x), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val variants = listOf(
            LpRefinementLimits(maxWork = 0) to LpRefinementDecline.WORK,
            LpRefinementLimits(maxAllocation = 0) to LpRefinementDecline.ALLOCATION,
            LpRefinementLimits(maxBits = 24) to LpRefinementDecline.BITS,
        )
        for ((limits, reason) in variants) {
            val meter = RefinementMeter(limits, LpRefinementCache(), Cancellation.Never)

            val stopped = assertFailsWith<RefinementStop> { recoverSparseBasisPoint(model, basis, meter) }

            assertEquals(reason, stopped.reason)
        }
    }

    @Test
    fun `a stopped sparse basis attempt should retain its spent allowance`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 1.0)
        val model = builder.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(x), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val cache = LpRefinementCache()
        val limits = LpRefinementLimits(maxWork = 10)
        assertFailsWith<RefinementStop> {
            recoverSparseBasisPoint(model, basis, RefinementMeter(limits, cache, Cancellation.Never))
        }
        val spent = cache.work

        assertFailsWith<RefinementStop> {
            recoverSparseBasisPoint(model, basis, RefinementMeter(limits, cache, Cancellation.Never))
        }

        assertTrue(spent > 0)
        assertEquals(spent, cache.work)
    }

    @Test
    fun `cancellation should stop sparse basis recovery`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 1.0)
        val model = builder.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(x), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), Cancellation { true })

        val stopped = assertFailsWith<RefinementStop> { recoverSparseBasisPoint(model, basis, meter) }

        assertEquals(LpRefinementDecline.CANCELLED, stopped.reason)
    }

    @Test
    fun `point recovery should verify a basis point independently of the rounded primal`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 2.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 1.0)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val basis = Basis(intArrayOf(x), arrayOf(VarStatus.BASIC, VarStatus.FIXED))

        LpScopedSolver(state).use { owner ->
            val result = recoverExactPointWitness(assertNotNull(state.toWorkingModel()), doubleArrayOf(0.0),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()), Cancellation.Never,
                basis = basis)

            assertEquals(listOf(BigFraction.ONE), assertNotNull(result.witness).primal)
            assertEquals(1, result.checks)
            assertEquals(0, result.repairs)
            assertNull(result.decline)
        }
    }

    @Test
    fun `the certification ladder should recover a large basis through its sparse point request`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        val y = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(2_000_003.0), Relation.EQ, 1.0)
        builder.addRealRow(intArrayOf(y), doubleArrayOf(2_000_029.0), Relation.EQ, 1.0)
        repeat(129) { builder.addRealRow(intArrayOf(x, y), doubleArrayOf(1.0, 1.0), Relation.LE, 1.0) }
        val model = builder.build(Sense.MINIMIZE)
        val phases = ArrayList<LpPhaseMetrics>()
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
            override fun observePhase(metrics: LpPhaseMetrics) { phases += metrics }
        }

        val result = solveAndCertify(model, componentSplit = false, observer = observer,
            refinementLimits = LpRefinementLimits(maxRounds = 0, maxAuxiliaries = 0),
            context = LpSolveContext(sparsePointRecovery = true))

        assertEquals(listOf(BigFraction.of(BIG_ONE, bigIntOf(2_000_003)),
            BigFraction.of(BIG_ONE, bigIntOf(2_000_029))), assertNotNull(result.witness).primal)
        assertEquals("WITNESS", phases.single { it.phase == LpSolvePhase.EXACT_BASIS_POINT }.outcome)
    }

    @Test
    fun `a free nonbasic coordinate should use its exact zero seat`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(2L))), listOf(ExactLpEntry(0, one))),
            listOf(ExactLpNumber.of(6L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(null, null)),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(List(3) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FREE, VarStatus.FIXED))
        val meter = RefinementMeter(LpRefinementLimits(), LpRefinementCache(), Cancellation.Never)

        val result = recoverSparseBasisPoint(model, basis, meter)

        assertEquals(listOf(BigFraction.ofLong(3), BigFraction.ZERO), assertNotNull(result.witness).primal)
    }
}
