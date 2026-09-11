package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.basis.RationalBasisOrder
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactBasisOrderingTest {
    @Test
    fun `live nonsymmetric order verifies original coefficients beyond double precision`() {
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
        RevisedSimplex(model, reuseRationalOrder = true).use { solver ->
            val candidate = assertNotNull(solver.solve(warm))
            val checked = verifyExactBasis(model, candidate.basis, cache = solver.exactBasisCache)
            val standalone = verifyExactBasis(model, candidate.basis)

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
    fun `near singular exact input remains complete when float ordering is unavailable`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val epsilon = BigFraction.ofLong(1L shl 60).reciprocal()
        for (delta in listOf(epsilon, BigFraction.ZERO)) {
            val source = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one), ExactLpEntry(1, one)), listOf(
                    ExactLpEntry(0, one), ExactLpEntry(1, ExactLpNumber.of(BigFraction.ONE + delta)),
                )),
                listOf(ExactLpNumber.of(2L), ExactLpNumber.of(BigFraction.ofLong(2L) + delta)),
                List(2) { ExactLpColumn(ExactLpBounds()) } + List(2) {
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))
                },
                List(2) { ExactLpRow() }, ExactLpObjective(List(4) { zero }),
            )
            val model = assertNotNull(LpExactState(source).toWorkingModel())
            val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED))
            RevisedSimplex(model, reuseRationalOrder = true).use { solver ->
                val result = verifyExactBasis(model, basis, cache = solver.exactBasisCache)
                assertEquals(0, result.metrics.orderProposals)
                if (delta.isZero) {
                    assertEquals(1, result.singularRank)
                    assertEquals(ExactBasisDecline.SINGULAR, result.metrics.decline)
                } else {
                    assertEquals(listOf(BigFraction.ONE, BigFraction.ONE), result.witness?.primal)
                    assertNull(result.singularRank)
                }
            }
        }
    }

    @Test
    fun `current state and ordered basis guards reject stale live hints`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))), listOf(one),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))),
            listOf(ExactLpRow()), ExactLpObjective(listOf(zero, zero)),
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
            assertEquals(ExactBasisOrderDecline.STALE,
                verifyExactBasis(model, alternate, cache = solver.exactBasisCache).metrics.orderDecline)
            solver.exactBasisCache.clear()
            assertEquals(1, verifyExactBasis(model, warm, cache = solver.exactBasisCache).metrics.orderProposals)
            solver.close()
            assertEquals(ExactBasisOrderDecline.STALE,
                verifyExactBasis(model, warm, cache = solver.exactBasisCache).metrics.orderDecline)
            assertNull(solver.continuationBasis(model))
        }
    }

    @Test
    fun `off and legacy unsupported paths perform no export`() {
        val model = LpBuilder().apply {
            addVar(0L, 2L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        for (enabled in listOf(false, true)) {
            var exports = 0
            RevisedSimplex(model, reuseRationalOrder = enabled, basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ordering() = delegate.ordering().also { exports++ }
                }
            }).use { solver ->
                val candidate = assertNotNull(solver.solve())
                val checked = verifyExactBasis(model, candidate.basis, cache = solver.exactBasisCache)
                assertNotNull(checked.witness)
                assertEquals(0, exports)
                assertEquals(if (enabled) 1 else 0, checked.metrics.orderOffers)
                assertEquals(0, checked.metrics.orderProposals)
            }
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
            listOf(listOf(ExactLpEntry(0, one), ExactLpEntry(1, one))), listOf(zero, one),
            listOf(ExactLpColumn(ExactLpBounds()), fixed, fixed),
            listOf(ExactLpRow(), ExactLpRow(global = false, premises = premises)),
            ExactLpObjective(List(3) { zero }),
        )
        val state = LpExactState(source)
        val model = assertNotNull(state.toWorkingModel())
        val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED))
        val off = verifyExactBasis(model, basis, rayRow = 1)
        val hinted = verifyExactBasis(model, basis, rayRow = 1,
            cache = ExactBasisCache { RationalBasisOrder(intArrayOf(0, 1), intArrayOf(0, 1)) })

        assertNotNull(hinted.conflict)
        assertEquals(off.conflict?.multipliers, hinted.conflict.multipliers)
        assertEquals(off.conflictSupport?.rows, hinted.conflictSupport?.rows)
        assertEquals(off.conflictSupport?.sides, hinted.conflictSupport?.sides)
        assertEquals(premises, hinted.conflictSupport?.rows?.last()?.second?.premises)
        assertTrue(checkedLpConflict(model, hinted.conflict))
    }

    @Test
    fun `current continuation targets survive numerical retirement but not explicit close`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))), listOf(one),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))),
            listOf(ExactLpRow()), ExactLpObjective(listOf(one, zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val solver = RevisedSimplex(model, basisSolverFactory = { matrix ->
            val delegate = KotlinBasisSolver(matrix)
            object : BasisSolver by delegate {
                override fun ftran(x: IndexedVector, expectedDensity: Double) {
                    throw BasisArithmeticException("injected solve failure")
                }
            }
        })
        assertNull(solver.solve(basis))
        assertNull(solver.solvedExactState)
        val target = assertNotNull(solver.continuationBasis(model))
        assertFalse(target.captureEligible)
        target.basicVars[0] = 1
        target.status[0] = VarStatus.FREE
        assertContentEquals(intArrayOf(0), assertNotNull(solver.continuationBasis(model)).basicVars)
        assertNull(solver.continuationBasis(assertNotNull(LpExactState(source).toWorkingModel())))
        solver.close()
        assertNull(solver.continuationBasis(model))
    }
    @Test
    fun `same basis rhs and objective edits reuse only factors`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val matrix = listOf(listOf(ExactLpEntry(0, one)))
        val columns = listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))))
        val source = ExactLpModel(matrix, listOf(one), columns, listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)))
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        RevisedSimplex(model, reuseRationalOrder = true).use { solver ->
            val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
            assertNotNull(solver.solve(basis))
            val initial = verifyExactBasis(model, basis, cache = solver.exactBasisCache)
            val changed = LpExactState(ExactLpModel(matrix, listOf(ExactLpNumber.of(2L)), columns,
                listOf(ExactLpRow()), ExactLpObjective(listOf(ExactLpNumber.of(3L), zero))))
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
    fun `default off and unsupported exact owners preserve complete certification`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(listOf(listOf(ExactLpEntry(0, one))), listOf(one),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))),
            listOf(ExactLpRow()), ExactLpObjective(listOf(zero, zero)))
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        for (enabled in listOf(false, true)) {
            var exports = 0
            RevisedSimplex(model, reuseRationalOrder = enabled, basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ordering(): com.eignex.klause.simplex.basis.BasisOrdering? {
                        exports++
                        return null
                    }
                }
            }).use { solver ->
                val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
                val candidate = assertNotNull(solver.solve(basis))
                val checked = verifyExactBasis(model, candidate.basis, cache = solver.exactBasisCache)
                assertNotNull(checked.witness)
                assertEquals(if (enabled) 1 else 0, exports)
                assertEquals(0, checked.metrics.orderProposals)
                assertEquals(0, checked.metrics.orderAttempts)
            }
        }
    }

    @Test
    fun `accepted live updates decline ordering while exact witnesses remain available`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(listOf(listOf(ExactLpEntry(0, one))), listOf(one),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))),
            listOf(ExactLpRow()), ExactLpObjective(listOf(zero, zero)))
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        RevisedSimplex(model, reuseRationalOrder = true).use { solver ->
            val candidate = assertNotNull(solver.solve())
            val checked = verifyExactBasis(model, candidate.basis, cache = solver.exactBasisCache)
            assertEquals(ExactBasisOrderDecline.UPDATED, checked.metrics.orderDecline)
            assertEquals(0, checked.metrics.orderProposals)
            assertEquals(BigFraction.ONE, checked.witness?.primal?.single())
        }
    }

    @Test
    fun `repaired logical headings feed the accepted live order`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(List(2) { listOf(ExactLpEntry(0, one), ExactLpEntry(1, one)) },
            listOf(one, one), List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) } + List(2) {
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))
            }, List(2) { ExactLpRow() }, ExactLpObjective(List(4) { zero }))
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        RevisedSimplex(model, reuseRationalOrder = true).use { solver ->
            val warm = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED))
            val candidate = assertNotNull(solver.solve(warm))
            assertTrue(candidate.basis.basicVars.any { it >= 2 })
            val checked = verifyExactBasis(model, candidate.basis, cache = solver.exactBasisCache)
            assertEquals(1, checked.metrics.orderProposals)
            val primal = assertNotNull(checked.witness).primal
            assertEquals(BigFraction.ONE, primal[0] + primal[1])
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
        val limited = verifyExactBasis(model, basis, cache = cache,
            limits = ExactBasisLimits(RationalBasisLimits(work = complete.metrics.work * 2L / 3L)))

        assertEquals(ExactBasisDecline.WORK, limited.metrics.decline)
        assertEquals(1, limited.metrics.orderProposals)
        assertNotNull(limited.witness)
        assertNull(limited.bound)
        assertNull(limited.singularRank)
    }

    @Test
    fun `hinted fixed width overflow restarts the whole exact factor operation`() {
        val zero = ExactLpNumber.of(0L)
        val wide = ExactLpNumber.of(BigFraction.of(BigInteger.ONE shl 140, BigInteger.ONE))
        val source = ExactLpModel(listOf(listOf(ExactLpEntry(0, wide))), listOf(wide),
            listOf(ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))),
            listOf(ExactLpRow()), ExactLpObjective(listOf(zero, zero)))
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        RevisedSimplex(model, reuseRationalOrder = true).use { solver ->
            assertNotNull(solver.solve(basis))
            val result = verifyExactBasis(model, basis, cache = solver.exactBasisCache)
            val standalone = verifyExactBasis(model, basis)
            assertEquals(BigFraction.ONE, result.witness?.primal?.single())
            assertEquals(1, result.metrics.orderProposals)
            assertTrue(result.metrics.restarts > 0)
            assertTrue(result.metrics.builds >= 2)
            assertEquals(standalone.metrics.restarts, result.metrics.restarts)
            assertEquals(standalone.witness?.primal, result.witness?.primal)
        }
    }

    @Test
    fun `matrix edits below double precision invalidate exact factors and live hints`() {
        val zero = ExactLpNumber.of(0L)
        val wide = 9_007_199_254_740_992L
        val columns = listOf(ExactLpColumn(ExactLpBounds()),
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))))
        val models = listOf(wide, wide + 1L).map { coefficient ->
            val source = ExactLpModel(listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(coefficient)))),
                listOf(ExactLpNumber.of(wide)), columns, listOf(ExactLpRow()), ExactLpObjective(listOf(zero, zero)))
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
            assertEquals(BigFraction.ofLong(wide) * BigFraction.ofLong(wide + 1L).reciprocal(),
                changed.witness?.primal?.single())
            assertFalse(solver.adopt(assertNotNull(next.exactState), Cancellation.Never))
            assertNull(solver.continuationBasis(next))
        }
    }

    @Test
    fun `work stopped basis targets retain current declarations without a solved claim`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(listOf(listOf(ExactLpEntry(0, one))), listOf(one),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))),
            listOf(ExactLpRow()), ExactLpObjective(listOf(zero, zero)))
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        RevisedSimplex(model, workLimit = 1L).use { solver ->
            assertNull(solver.solve())
            assertNull(solver.solvedExactState)
            val target = assertNotNull(solver.continuationBasis(model))
            assertContentEquals(intArrayOf(1), target.basicVars)
            assertEquals(VarStatus.BASIC, target.status[1])
        }
    }

}
