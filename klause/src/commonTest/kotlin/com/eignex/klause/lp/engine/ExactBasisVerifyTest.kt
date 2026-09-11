package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
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
        val large = BigFraction.of(BigInteger.ONE shl 140, BigInteger.ONE)
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
    fun `free column Farkas verifies both equations and complete box support`() {
        val zero = ExactLpNumber.of(0L)
        val fixed = ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)), ExactLpEntry(1, ExactLpNumber.of(1L)))),
            listOf(zero, ExactLpNumber.of(1L)),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(fixed), ExactLpColumn(fixed)),
            List(2) { ExactLpRow() },
            ExactLpObjective(List(3) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED))

        val checked = verifyExactBasis(model, basis, rayRow = 1)

        val conflict = assertNotNull(checked.conflict)
        assertEquals(BigFraction.ZERO, conflict.multipliers.reduce { a, b -> a + b })
        assertTrue(checkedLpConflict(model, conflict))
        assertEquals(listOf(0, 1), checked.conflictSupport?.rows?.map { it.first })
        assertEquals(setOf(1, 2), checked.conflictSupport?.sides?.map { it.column }?.toSet())
        assertTrue(sourceFarkasValid(model, assertNotNull(checked.integerRay)))
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
    fun `large basis decline retains the declared dense storage limit`() {
        val model = LpBuilder().apply {
            repeat(129) { j ->
                addVar(0L, 1L)
                addRow(intArrayOf(j), longArrayOf(1L), Relation.LE, 1L)
            }
        }.build(Sense.MINIMIZE)
        val basis = Basis(
            IntArray(129) { 129 + it },
            Array(258) { if (it < 129) VarStatus.AT_LOWER else VarStatus.BASIC },
        )

        val checked = verifyExactBasis(model, basis)

        assertEquals(ExactBasisDecline.DIMENSION, checked.metrics.decline)
        assertEquals(0, checked.metrics.factoryCalls)
        assertNull(checked.witness)
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
    fun `persistent bound and objective adoption reuses factors without pivots`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(-1L)))),
            listOf(ExactLpNumber.of(-2L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(ExactLpNumber.of(1L), zero)),
        )
        val trail = LpBoundTrail(source)
        var model = assertNotNull(trail.state.toWorkingModel())
        RevisedSimplex(model).use { solver ->
            val first = assertNotNull(solver.solve())
            val checked = verifyExactBasis(model, first.basis, cache = solver.exactBasisCache)
            assertEquals(1, checked.metrics.factoryCalls)
            assertTrue(trail.push())
            assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(8L)), 7L))
            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(ExactLpNumber.of(3L), zero))))
            assertTrue(solver.adopt(trail.state))
            model = assertNotNull(trail.state.toWorkingModel())

            val changed = assertNotNull(solver.resolveBounds())
            val proof = verifyExactBasis(model, changed.basis, cache = solver.exactBasisCache)

            assertEquals(0, changed.pivots)
            assertEquals(first.basis.basicVars.toList(), changed.basis.basicVars.toList())
            assertEquals(BigFraction.ofLong(6L), proof.bound?.value)
            assertEquals(0, proof.metrics.factoryCalls)
            assertEquals(1, proof.metrics.reuse)
            assertEquals(BigFraction.ofLong(2L), checked.bound?.value)
        }
    }

    @Test
    fun `restored and replacement owners do not inherit rational factors`() {
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
            val snapshot = assertNotNull(solver.captureBasisRestart())
            assertTrue(solver.restoreBasisRestart(snapshot))
            val restored = assertNotNull(solver.resolveBounds())
            assertEquals(
                1,
                verifyExactBasis(model, restored.basis, cache = solver.exactBasisCache).metrics.factoryCalls,
            )
            snapshot.close()
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
    fun `overflowing solve rhs restarts from authoritative input`() {
        val huge = BigFraction.of(BigInteger.ONE shl 140, BigInteger.ONE)
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
            listOf(ExactLpNumber.of(huge)),
            listOf(
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())

        val checked = verifyExactBasis(model, Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED)))

        assertEquals(listOf(huge), checked.witness?.primal)
        assertTrue(checked.complementary)
        assertEquals(1, checked.metrics.factoryCalls)
        assertTrue(checked.metrics.builds >= 2)
        assertTrue(checked.metrics.restarts >= 1)
    }

    @Test
    fun `full rational conflict survives nonrepresentable integer projection in live ladder`() {
        val large = BigFraction.of(BigInteger.ONE shl 70, BigInteger.ONE)
        val zero = ExactLpNumber.of(0L)
        val fixed = ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))
        val source = ExactLpModel(
            listOf(
                listOf(
                    ExactLpEntry(0, ExactLpNumber.of(large + BigFraction.ONE)),
                    ExactLpEntry(1, ExactLpNumber.of(large + BigFraction.ofLong(3L))),
                ),
            ),
            listOf(zero, ExactLpNumber.of(1L)),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(fixed), ExactLpColumn(fixed)),
            List(2) { ExactLpRow() },
            ExactLpObjective(List(3) { zero }),
        )
        val state = LpExactState(source)
        val model = assertNotNull(state.toWorkingModel())
        val solver = object : LpSolver {
            override val solvedExactState = state
            override val infeasibleRay = doubleArrayOf(Double.NaN, Double.NaN)
            override val infeasibleBasis = Basis(
                intArrayOf(0, 1),
                arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED),
            )
            override val infeasibleRow = 1
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        }

        val result = certifyLpResult(model, solver, null)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertTrue(checkedLpConflict(model, assertNotNull(result.rationalConflict)))
        assertNotNull(result.conflictSupport)
        assertNull(result.farkasRay)
        assertEquals(ExactBasisDecline.PROJECTION, result.basisVerification?.decline)
        assertEquals(1, result.basisVerification?.factoryCalls)
    }

    @Test
    fun `basis dual supplies a weaker bound after float and primal rejection`() {
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
        val result = newLpSolver(model).use { certifyLpResult(model, it, hint) }

        assertEquals(LpVerdict.CERTIFIED_BOUND, result.verdict)
        assertEquals(BigFraction.ZERO, result.lowerBound)
        assertNull(result.witness)
        assertEquals(1, result.basisVerification?.factoryCalls)
    }

    @Test
    fun `later work exhaustion retains a separately verified primal package`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(3L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val complete = verifyExactBasis(model, basis)
        val work = complete.metrics.work
        val checked = verifyExactBasis(
            model,
            basis,
            limits = ExactBasisLimits(RationalBasisLimits(work = work * 2L / 3L)),
        )

        assertEquals(ExactBasisDecline.WORK, checked.metrics.decline)
        assertNotNull(checked.witness)
        assertEquals(BigFraction.ONE, checked.witness.primal.single() * BigFraction.ofLong(3L))
        assertTrue(checked.metrics.work <= work * 2L / 3L)
        assertNull(checked.singularRank)
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
            val repaired = assertNotNull(solver.resolveBounds())
            val verified = verifyExactBasis(model, repaired.basis, cache = solver.exactBasisCache)

            assertFalse(solver.lastWarmStarted)
            assertEquals(1, verified.metrics.factoryCalls)
            assertEquals(BigFraction.ONE, verified.bound?.value)
        }
    }

    @Test
    fun `popped scoped row reuses factors while discarding its current conflict`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val fixed = ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(zero),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(fixed)),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val premises = ExactLpPremises(emptyList(), listOf(23))
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        assertTrue(
            trail.append(
                LpScopedRow(
                    1L,
                    listOf(0 to one),
                    one,
                    ExactLpColumn(fixed),
                    ExactLpRow(global = false, premises = premises),
                ),
                scoped = true,
            ),
        )
        val state = trail.state
        val cache = ExactBasisCache()
        val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED))
        val conflict = verifyExactBasis(assertNotNull(state.toWorkingModel()), basis, rayRow = 1, cache = cache)
        assertNotNull(conflict.conflict)
        assertTrue(trail.pop(0))
        basis.status[2] = VarStatus.FREE

        val popped = verifyExactBasis(assertNotNull(trail.state.toWorkingModel()), basis, rayRow = 1, cache = cache)

        assertNull(popped.conflict)
        assertEquals(1, popped.metrics.reuse)
        assertEquals(state, conflict.conflictSupport?.state)
        assertEquals(premises, conflict.conflictSupport?.rows?.last()?.second?.premises)
        assertTrue(checkedLpConflict(assertNotNull(state.toWorkingModel()), assertNotNull(conflict.conflict)))
    }

    @Test
    fun `live legacy Farkas retains the representable ray projection`() {
        val model = LpBuilder().apply {
            addFreeVar(null, null)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 0L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val solver = object : LpSolver {
            override val infeasibleRay = doubleArrayOf(Double.NaN, Double.NaN)
            override val infeasibleBasis = Basis(
                intArrayOf(0, 1),
                arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED),
            )
            override val infeasibleRow = 1
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
        }

        val result = certifyLpResult(model, solver, null)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertTrue(sourceFarkasValid(model, assertNotNull(result.farkasRay)))
        assertNotNull(result.rationalConflict)
        assertEquals(1, result.basisVerification?.factoryCalls)
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

    @Test
    fun `basis witness survives withheld rational objective proof`() {
        val model = LpBuilder().apply {
            addRealVar(0.0, 3.0, cost = 1.0)
            addRealRow(intArrayOf(0), doubleArrayOf(3.0), Relation.EQ, 1.0)
        }.build(Sense.MINIMIZE)
        val policy = LpCertificationPolicy { certifier, success ->
            certifier == LpCertifier.EXACT_BASIS && success
        }

        val result = solveAndCertify(model, context = LpSolveContext(certificationPolicy = policy))

        assertNotNull(result.witness)
        assertNull(result.bound)
        assertEquals(LpVerdict.FEASIBLE, result.verdict)
    }
}
