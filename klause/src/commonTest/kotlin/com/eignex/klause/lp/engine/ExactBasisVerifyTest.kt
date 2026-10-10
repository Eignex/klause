package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class ExactBasisVerifyTest {
    @Test
    fun `reordered nonsymmetric basis verifies primal and true dual objective`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L, cost = 3L)
            addVar(0L, 10L, cost = 4L)
            addRow(intArrayOf(0, 1), longArrayOf(2L, 1L), Relation.EQ, 4L)
            addRow(intArrayOf(0, 1), longArrayOf(1L, 3L), Relation.EQ, 7L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(1, 0), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED))

        val checked = verifyExactBasis(model, basis)

        assertEquals(listOf(1L, 2L).map(BigFraction::ofLong), checked.witness?.primal)
        assertEquals(BigFraction.ofLong(11L), checked.bound?.value)
        assertTrue(checked.complementary)
        assertEquals(1, checked.metrics.factoryCalls)
        assertEquals(2, checked.metrics.solves)
        assertNull(checked.metrics.decline)
    }

    @Test
    fun `logical basic cost and source objective units are authoritative`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(2L)))),
            listOf(ExactLpNumber.of(10L)),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(3L))),
                    origin = ExactLpNumber.of(4L),
                ),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(
                listOf(ExactLpNumber.of(5L), ExactLpNumber.of(2L)),
                constant = ExactLpNumber.of(7L),
                scale = ExactLpNumber.of(3L),
                externalConstant = ExactLpNumber.of(11L),
            ),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())

        val checked = verifyExactBasis(model, Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC)))

        assertEquals(listOf(BigFraction.ofLong(4L)), checked.witness?.primal)
        assertEquals(BigFraction.ofLong(20L), checked.witness?.objective)
        assertEquals(checked.witness?.objective, checked.bound?.value)
        assertTrue(checked.complementary)
    }

    @Test
    fun `free upper only and fixed nonbasic seats contribute exact rhs`() {
        val zero = ExactLpNumber.of(0L)
        val three = ExactLpSide(ExactLpNumber.of(3L))
        val source = ExactLpModel(
            List(3) { listOf(ExactLpEntry(0, ExactLpNumber.of(1L))) },
            listOf(ExactLpNumber.of(5L)),
            listOf(
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(upper = ExactLpSide(ExactLpNumber.of(-2L)))),
                ExactLpColumn(ExactLpBounds(three, three)),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(List(4) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(intArrayOf(3), arrayOf(VarStatus.FREE, VarStatus.AT_UPPER, VarStatus.FIXED, VarStatus.BASIC))

        val checked = verifyExactBasis(model, basis)

        assertEquals(listOf(0L, -2L, 3L).map(BigFraction::ofLong), checked.witness?.primal)
        assertTrue(checked.complementary)
    }

    @Test
    fun `near singular beyond Long source restarts without rounded coefficients`() {
        val large = BigFraction.of(BIG_ONE shl 140, BIG_ONE)
        val one = BigFraction.ONE
        val zero = ExactLpNumber.of(0L)
        val fixed = ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))
        val source = ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, ExactLpNumber.of(large)), ExactLpEntry(1, ExactLpNumber.of(large - one))),
                listOf(ExactLpEntry(0, ExactLpNumber.of(large + one)), ExactLpEntry(1, ExactLpNumber.of(large))),
            ),
            listOf(ExactLpNumber.of(large + large + one), ExactLpNumber.of(large + large - one)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L)))),
                ExactLpColumn(fixed),
                ExactLpColumn(fixed),
            ),
            List(2) { ExactLpRow() },
            ExactLpObjective(listOf(ExactLpNumber.of(1L), ExactLpNumber.of(1L), zero, zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(intArrayOf(1, 0), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED))

        val checked = verifyExactBasis(model, basis)

        assertEquals(listOf(one, one), checked.witness?.primal)
        assertEquals(BigFraction.ofLong(2L), checked.bound?.value)
        assertTrue(checked.complementary)
        assertTrue(checked.metrics.restarts > 0)
        assertTrue(checked.metrics.builds >= 2)
    }

    @Test
    fun `BTRAN with nonzero free column residue cannot prove infeasibility`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
            listOf(ExactLpNumber.of(2L)),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())

        val checked = verifyExactBasis(
            model,
            Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER)),
            rayRow = 0,
        )

        assertNull(checked.conflict)
        assertNull(checked.integerRay)
        assertEquals(ExactBasisDecline.CANDIDATE, checked.metrics.decline)
        assertNull(checked.singularRank)
    }

    @Test
    fun `bound status rhs and cost edits reuse factors and recompute proofs`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L, cost = 1L)
            addVar(0L, 2L)
            addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.EQ, 5L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_UPPER, VarStatus.FIXED))
        val cache = ExactBasisCache()
        val first = verifyExactBasis(model, basis, cache = cache)
        assertEquals(BigFraction.ofLong(3L), first.witness?.objective)
        assertEquals(1, first.metrics.factoryCalls)

        model.upper[1] = 1L
        model.rhs[0] = 7L
        model.cost[0] = 2L
        basis.status[1] = VarStatus.AT_LOWER
        val changed = verifyExactBasis(model, basis, cache = cache)

        assertEquals(listOf(7L, 0L).map(BigFraction::ofLong), changed.witness?.primal)
        assertEquals(BigFraction.ofLong(14L), changed.witness?.objective)
        assertEquals(BigFraction.ofLong(12L), changed.bound?.value)
        assertFalse(changed.complementary)
        assertEquals(1, changed.metrics.reuse)
        assertEquals(0, changed.metrics.factoryCalls)
        assertEquals(0, changed.metrics.builds)
        assertEquals(BigFraction.ofLong(3L), first.witness?.objective)
    }

    @Test
    fun `mutated legacy matrix and reordered headings cannot reuse factors`() {
        val model = LpBuilder().apply {
            repeat(2) { addVar(0L, 2L) }
            addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 1L)
            addRow(intArrayOf(1), longArrayOf(1L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED))
        val cache = ExactBasisCache()
        assertNotNull(verifyExactBasis(model, basis, cache = cache).witness)
        model.csc.colVal[0] = 2L

        val changed = verifyExactBasis(model, basis, cache = cache)
        val reordered = verifyExactBasis(
            model,
            Basis(intArrayOf(1, 0), basis.status),
            cache = cache,
        )

        assertEquals(BigFraction.ofDouble(0.5), changed.witness?.primal?.first())
        assertEquals(changed.witness?.primal, reordered.witness?.primal)
        assertEquals(1, changed.metrics.factoryCalls)
        assertEquals(1, reordered.metrics.factoryCalls)
    }

    @Test
    fun `exact singularity remains distinct from failed resource attempts`() {
        val model = LpBuilder().apply {
            repeat(2) { addVar(0L, 2L) }
            repeat(2) { addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.EQ, 1L) }
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED))
        val cache = ExactBasisCache()

        val singular = verifyExactBasis(model, basis, cache = cache)
        val limited = verifyExactBasis(
            model,
            basis,
            cache = cache,
            limits = ExactBasisLimits(RationalBasisLimits(fill = 0)),
        )
        val retried = verifyExactBasis(
            model,
            basis,
            cache = cache,
            limits = ExactBasisLimits(RationalBasisLimits(fill = 0)),
        )

        assertEquals(1, singular.singularRank)
        assertEquals(ExactBasisDecline.SINGULAR, singular.metrics.decline)
        for (declined in listOf(limited, retried)) {
            assertNull(declined.singularRank)
            assertEquals(ExactBasisDecline.FILL, declined.metrics.decline)
            assertEquals(1, declined.metrics.factoryCalls)
            assertEquals(1, declined.metrics.builds)
            assertTrue(declined.metrics.work > 0L)
        }
    }

    @Test
    fun `dimension work time and cancellation declines publish no proof`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val variants = listOf(
            RationalBasisLimits(dimension = 0) to ExactBasisDecline.DIMENSION,
            RationalBasisLimits(work = 0L) to ExactBasisDecline.WORK,
            RationalBasisLimits(time = Duration.ZERO) to ExactBasisDecline.TIME,
            RationalBasisLimits(allocationBytes = 0L) to ExactBasisDecline.MEMORY,
        )
        for ((limits, reason) in variants) {
            val checked = verifyExactBasis(model, basis, limits = ExactBasisLimits(limits))
            assertNull(checked.witness)
            assertNull(checked.bound)
            assertNull(checked.singularRank)
            assertEquals(reason, checked.metrics.decline)
        }
        val cancelled = verifyExactBasis(model, basis, cancellation = Cancellation { true })
        assertEquals(ExactBasisDecline.CANCELLED, cancelled.metrics.decline)
        assertNull(cancelled.witness)
    }

    @Test
    fun `logical support retains local row premises even with zero dual`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val premises = ExactLpPremises(emptyList(), listOf(17))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow(global = false, premises = premises)),
            ExactLpObjective(listOf(zero, one)),
        )
        val state = LpExactState(source)
        val checked = verifyExactBasis(
            assertNotNull(state.toWorkingModel()),
            Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER)),
        )

        val support = assertNotNull(checked.bound?.support)
        assertEquals(state, support.state)
        assertEquals(listOf(0), support.rows.map { it.first })
        assertEquals(premises, support.rows.single().second.premises)
        assertEquals(listOf(1), support.sides.map { it.column })
        assertTrue(checked.complementary)
    }

    @Test
    fun `strict selected side proves contradiction with zero surplus`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
            listOf(zero),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val checked = verifyExactBasis(
            model,
            Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC)),
            rayRow = 0,
        )

        assertTrue(checkedLpConflict(model, assertNotNull(checked.conflict)))
        assertTrue(assertNotNull(checked.conflictSupport).sides.any { it.side.strict })
        assertNull(checked.witness)
    }

    @Test
    fun `replacement owners do not inherit rational factors`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
            listOf(ExactLpNumber.of(1L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        RevisedSimplex(model).use { solver ->
            val first = assertNotNull(solver.solve())
            assertEquals(1, verifyExactBasis(model, first.basis, cache = solver.exactBasisCache).metrics.factoryCalls)
            RevisedSimplex(model).use { replacement ->
                val result = assertNotNull(replacement.solve())
                assertEquals(
                    1,
                    verifyExactBasis(model, result.basis, cache = replacement.exactBasisCache).metrics.factoryCalls,
                )
            }
        }
    }

    @Test
    fun `repair requests reject stale authority and invalidate only matching owner state`() {
        val model = LpBuilder().apply {
            addVar(0L, 2L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val foreign = LpBuilder().apply {
            addVar(0L, 2L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        RevisedSimplex(model).use { solver ->
            val result = assertNotNull(solver.solve())
            assertEquals(1, verifyExactBasis(model, result.basis, cache = solver.exactBasisCache).metrics.factoryCalls)
            assertFalse(solver.rejectSingularBasis(foreign, result.basis))
            assertEquals(1, verifyExactBasis(model, result.basis, cache = solver.exactBasisCache).metrics.reuse)

            assertTrue(solver.rejectSingularBasis(model, result.basis))
            assertNull(solver.resolveBounds())
            val verified = verifyExactBasis(model, result.basis, cache = solver.exactBasisCache)

            assertFalse(solver.lastWarmStarted)
            assertEquals(1, verified.metrics.factoryCalls)
            assertEquals(BigFraction.ONE, verified.bound?.value)
        }
    }

    @Test
    fun `basis proof cannot bypass a disabled basis acceptance policy`() {
        val model = LpBuilder().apply {
            addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val hint = FloatLpResult(
            Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC)),
            0.0,
            doubleArrayOf(Double.NaN),
            doubleArrayOf(0.0),
        )
        val policy = LpCertificationPolicy { certifier, success -> certifier != LpCertifier.EXACT_BASIS && success }

        val result = newLpSolver(model).use { certifyLpResult(model, it, hint, policy = policy) }

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(result.bound)
        assertEquals(1, result.basisVerification?.factoryCalls)
    }

}
