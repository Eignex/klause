package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.basis.RationalBasisOrder
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactBasisOrderingTest {
    @Test
    fun `default live order verifies original coefficients beyond double precision`() {
        val wide = 9_007_199_254_740_993L
        val zero = ExactLpNumber.of(0L)
        val fixed = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(1, ExactLpNumber.of(wide))), listOf(ExactLpEntry(0, ExactLpNumber.of(3L)))),
            listOf(ExactLpNumber.of(6L), ExactLpNumber.of(wide)),
            List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) } + List(2) { fixed },
            List(2) { ExactLpRow() },
            ExactLpObjective(listOf(ExactLpNumber.of(5L), ExactLpNumber.of(7L), zero, zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val warm = Basis(intArrayOf(1, 0), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED))
        RevisedSimplex(model).use { solver ->
            val candidate = assertNotNull(solver.solve(warm))
            val checked = verifyExactBasis(model, candidate.basis, cache = solver.exactBasisCache)
            val standalone = verifyExactBasis(model, candidate.basis)

            val orderPhases = setOf(
                ExactBasisPhase.ORDER_IDENTITY,
                ExactBasisPhase.ORDER_EXPORT,
                ExactBasisPhase.ORDER_VALIDATION,
            )
            assertEquals(
                listOf(
                    ExactBasisWork(ExactBasisPhase.ORDER_IDENTITY, 160L, 304L),
                    ExactBasisWork(ExactBasisPhase.ORDER_EXPORT, 36L, 880L),
                    ExactBasisWork(ExactBasisPhase.ORDER_VALIDATION, 12L, 292L),
                ),
                checked.metrics.operations.filter { it.phase in orderPhases },
            )
            val cached = verifyExactBasis(model, candidate.basis, cache = solver.exactBasisCache)
            assertEquals(1, cached.metrics.reuse)
            assertEquals(0, cached.metrics.orderOffers)
            assertTrue(
                cached.metrics.operations.filter {
                    it.phase in orderPhases
                }.all { it.work == 0L && it.allocation == 0L },
            )
            assertTrue(
                standalone.metrics.operations.filter { it.phase in orderPhases }.all {
                    it.work == 0L &&
                        it.allocation == 0L
                },
            )
            assertEquals(1, checked.metrics.orderProposals)
            assertEquals(1, checked.metrics.orderAttempts)
            assertEquals(0, checked.metrics.orderFallbacks)
            assertEquals(listOf(1L, 2L).map(BigFraction::ofLong), checked.witness?.primal)
            assertEquals(BigFraction.ofLong(19L), checked.bound?.value)
            assertEquals(standalone.witness?.primal, checked.witness?.primal)
            assertEquals(standalone.bound?.value, checked.bound?.value)
            assertTrue(checked.complementary)
            assertEquals(1, certifyLpResult(model, solver, candidate).basisVerification?.reuse)
        }
    }

    @Test
    fun `zero hinted pivots restart authoritative verification and retain fallback cost`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L)
            addVar(0L, 10L)
            addRow(intArrayOf(1), longArrayOf(1L), Relation.EQ, 2L)
            addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.EQ, 3L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED))
        val order = RationalBasisOrder(intArrayOf(0, 1), intArrayOf(0, 1))
        val cache = ExactBasisCache { order }
        val standalone = verifyExactBasis(model, basis)

        val checked = verifyExactBasis(model, basis, cache = cache)
        order.rows.fill(8)
        order.columns.fill(8)
        val reused = verifyExactBasis(model, basis, cache = cache)

        assertEquals(1, checked.metrics.orderFallbacks)
        assertEquals(2, checked.metrics.builds)
        assertEquals(1, checked.metrics.factoryCalls)
        assertTrue(checked.metrics.work > standalone.metrics.work)
        assertEquals(standalone.witness?.primal, checked.witness?.primal)
        assertEquals(checked.witness?.primal, reused.witness?.primal)
        assertEquals(1, reused.metrics.reuse)
        assertEquals(0, reused.metrics.orderOffers)
    }

    @Test
    fun `malformed permutations decline only the hint`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L)
            addVar(0L, 10L)
            addRow(intArrayOf(0), longArrayOf(2L), Relation.EQ, 4L)
            addRow(intArrayOf(1), longArrayOf(1L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED))
        for (order in listOf(
            RationalBasisOrder(intArrayOf(2, 1), intArrayOf(0, 1)),
            RationalBasisOrder(intArrayOf(0, 0), intArrayOf(0, 1)),
            RationalBasisOrder(intArrayOf(0, 1), intArrayOf(1, 1)),
            RationalBasisOrder(intArrayOf(0, 1), intArrayOf(-1, 0)),
            RationalBasisOrder(intArrayOf(), intArrayOf(0)),
        )) {
            val result = verifyExactBasis(model, basis, cache = ExactBasisCache { order })
            assertEquals(ExactBasisOrderDecline.INVALID, result.metrics.orderDecline)
            assertEquals(0, result.metrics.orderAttempts)
            assertEquals(BigFraction.ofLong(2L), result.witness?.primal?.first())
            assertNull(result.metrics.decline)
        }
    }

    @Test
    fun `current state and ordered basis guards reject stale live hints`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val state = LpExactState(source)
        val model = assertNotNull(state.toWorkingModel())
        val warm = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        RevisedSimplex(model, reuseRationalOrder = true).use { solver ->
            assertNotNull(solver.solve(warm))
            val foreign = assertNotNull(LpExactState(source).toWorkingModel())
            val declined = verifyExactBasis(foreign, warm, cache = solver.exactBasisCache)
            assertEquals(ExactBasisOrderDecline.STALE, declined.metrics.orderDecline)
            assertNotNull(declined.witness)
            solver.exactBasisCache.clear()
            val alternate = Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC))
            assertEquals(
                ExactBasisOrderDecline.STALE,
                verifyExactBasis(model, alternate, cache = solver.exactBasisCache).metrics.orderDecline,
            )
            solver.exactBasisCache.clear()
            assertEquals(1, verifyExactBasis(model, warm, cache = solver.exactBasisCache).metrics.orderProposals)
            solver.close()
            assertEquals(
                ExactBasisOrderDecline.STALE,
                verifyExactBasis(model, warm, cache = solver.exactBasisCache).metrics.orderDecline,
            )
            assertNull(solver.continuationBasis(model))
        }
    }

    @Test
    fun `export reservations and cancellation stop before exact factorization`() {
        val model = LpBuilder().apply {
            addVar(0L, 2L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        var exported = false
        val cache = ExactBasisCache { authority ->
            authority.meter.charge(100_000_001L)
            exported = true
            RationalBasisOrder(intArrayOf(0), intArrayOf(0))
        }
        val budget = verifyExactBasis(model, basis, cache = cache)
        assertFalse(exported)
        assertEquals(ExactBasisDecline.WORK, budget.metrics.decline)
        assertEquals(0, budget.metrics.factoryCalls)
        var cancelled = false
        val cancelledCache = ExactBasisCache {
            cancelled = true
            RationalBasisOrder(intArrayOf(0), intArrayOf(0))
        }
        val stopped = verifyExactBasis(model, basis, cache = cancelledCache, cancellation = Cancellation { cancelled })
        assertEquals(ExactBasisDecline.CANCELLED, stopped.metrics.decline)
        assertEquals(0, stopped.metrics.factoryCalls)
        assertNull(stopped.witness)
    }

    @Test
    fun `hinted verification retains complete farkas source support`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val premises = ExactLpPremises(emptyList(), listOf(23))
        val fixed = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one), ExactLpEntry(1, one))),
            listOf(zero, one),
            listOf(ExactLpColumn(ExactLpBounds()), fixed, fixed),
            listOf(ExactLpRow(), ExactLpRow(global = false, premises = premises)),
            ExactLpObjective(List(3) { zero }),
        )
        val state = LpExactState(source)
        val model = assertNotNull(state.toWorkingModel())
        val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED))
        val off = verifyExactBasis(model, basis, rayRow = 1)
        val hinted = verifyExactBasis(
            model,
            basis,
            rayRow = 1,
            cache = ExactBasisCache { RationalBasisOrder(intArrayOf(0, 1), intArrayOf(0, 1)) },
        )

        assertNotNull(hinted.conflict)
        assertEquals(off.conflict?.multipliers, hinted.conflict.multipliers)
        assertEquals(off.conflictSupport?.rows, hinted.conflictSupport?.rows)
        assertEquals(off.conflictSupport?.sides, hinted.conflictSupport?.sides)
        assertEquals(premises, hinted.conflictSupport?.rows?.last()?.second?.premises)
        assertTrue(checkedLpConflict(model, hinted.conflict))
    }

    @Test
    fun `same basis rhs and objective edits reuse only factors`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val matrix = listOf(listOf(ExactLpEntry(0, one)))
        val columns = listOf(
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
        )
        val source = ExactLpModel(
            matrix,
            listOf(one),
            columns,
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        RevisedSimplex(model, reuseRationalOrder = true).use { solver ->
            val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
            assertNotNull(solver.solve(basis))
            val initial = verifyExactBasis(model, basis, cache = solver.exactBasisCache)
            val changed = LpExactState(
                ExactLpModel(
                    matrix,
                    listOf(ExactLpNumber.of(2L)),
                    columns,
                    listOf(ExactLpRow()),
                    ExactLpObjective(listOf(ExactLpNumber.of(3L), zero)),
                ),
            )
            assertTrue(solver.adopt(changed, Cancellation.Never))
            val current = assertNotNull(changed.toWorkingModel())

            val next = verifyExactBasis(current, basis, cache = solver.exactBasisCache)

            assertEquals(BigFraction.ONE, initial.witness?.primal?.single())
            assertEquals(BigFraction.ofLong(2L), next.witness?.primal?.single())
            assertEquals(BigFraction.ofLong(6L), next.bound?.value)
            assertEquals(1, next.metrics.reuse)
            assertEquals(0, next.metrics.orderOffers)
            assertNull(solver.continuationBasis(current))
            solver.exactBasisCache.clear()
            assertEquals(1, verifyExactBasis(current, basis, cache = solver.exactBasisCache).metrics.orderProposals)
        }
    }

    @Test
    fun `accepted live updates decline ordering while exact witnesses remain available`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        RevisedSimplex(model, reuseRationalOrder = true).use { solver ->
            val candidate = assertNotNull(solver.solve())
            val checked = verifyExactBasis(model, candidate.basis, cache = solver.exactBasisCache)
            assertEquals(ExactBasisOrderDecline.UPDATED, checked.metrics.orderDecline)
            assertEquals(0, checked.metrics.orderProposals)
            assertEquals(BigFraction.ONE, checked.witness?.primal?.single())
            val standalone = verifyExactBasis(model, candidate.basis)
            assertEquals(standalone.witness?.primal, checked.witness?.primal)
            assertEquals(standalone.bound?.value, checked.bound?.value)
            assertTrue(checked.complementary)
            assertNull(checked.metrics.decline)
            assertNull(checked.singularRank)
            assertEquals(standalone.metrics.work + 1L, checked.metrics.work)
            assertEquals(standalone.metrics.allocation, checked.metrics.allocation)
            assertEquals(
                ExactBasisWork(ExactBasisPhase.ORDER_IDENTITY, 1L, 0L),
                checked.metrics.operations.single { it.phase == ExactBasisPhase.ORDER_IDENTITY },
            )
            assertTrue(
                checked.metrics.operations.filter {
                    it.phase == ExactBasisPhase.ORDER_EXPORT || it.phase == ExactBasisPhase.ORDER_VALIDATION
                }.all { it.work == 0L && it.allocation == 0L },
            )
            val cached = verifyExactBasis(model, candidate.basis, cache = solver.exactBasisCache)
            assertEquals(1, cached.metrics.reuse)
            assertEquals(0, cached.metrics.orderOffers)
            assertEquals(checked.witness?.primal, cached.witness?.primal)
        }
    }

    @Test
    fun `hint attempts share later verification budgets`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(3L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val cache = ExactBasisCache { RationalBasisOrder(intArrayOf(0), intArrayOf(0)) }
        val complete = verifyExactBasis(model, basis, cache = cache)
        cache.clear()
        val limited = verifyExactBasis(
            model,
            basis,
            cache = cache,
            limits = ExactBasisLimits(RationalBasisLimits(work = complete.metrics.work * 2L / 3L)),
        )

        assertEquals(ExactBasisDecline.WORK, limited.metrics.decline)
        assertEquals(1, limited.metrics.orderProposals)
        assertNotNull(limited.witness)
        assertNull(limited.bound)
        assertNull(limited.singularRank)
    }

    @Test
    fun `matrix edits below double precision invalidate exact factors and live hints`() {
        val zero = ExactLpNumber.of(0L)
        val wide = 9_007_199_254_740_992L
        val columns = listOf(
            ExactLpColumn(ExactLpBounds()),
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
        )
        val models = listOf(wide, wide + 1L).map { coefficient ->
            val source = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(coefficient)))),
                listOf(ExactLpNumber.of(wide)),
                columns,
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(zero, zero)),
            )
            assertNotNull(LpExactState(source).toWorkingModel())
        }
        val first = models[0]
        val next = models[1]
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        RevisedSimplex(first, reuseRationalOrder = true).use { solver ->
            assertNotNull(solver.solve(basis))
            assertEquals(1, verifyExactBasis(first, basis, cache = solver.exactBasisCache).metrics.orderProposals)
            val changed = verifyExactBasis(next, basis, cache = solver.exactBasisCache)
            assertEquals(0, changed.metrics.reuse)
            assertEquals(ExactBasisOrderDecline.STALE, changed.metrics.orderDecline)
            assertEquals(
                BigFraction.ofLong(wide) * BigFraction.ofLong(wide + 1L).reciprocal(),
                changed.witness?.primal?.single(),
            )
            assertFalse(solver.adopt(assertNotNull(next.exactState), Cancellation.Never))
            assertNull(solver.continuationBasis(next))
        }
    }

}
