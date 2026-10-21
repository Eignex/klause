package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class SparseExactBasisFactorsTest {
    @Test
    fun `large triangular bases should certify shifted source optima in either heading order`() {
        val model = LpBuilder().apply {
            repeat(129) { addVar(10L, 1000L, cost = if (it == 128) 3L else 0L) }
            addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 11L)
            for (column in 1..128) {
                addRow(intArrayOf(column, column - 1), longArrayOf(1L, -1L), Relation.EQ, 1L)
            }
        }.build(Sense.MINIMIZE)
        for (reversed in listOf(false, true)) {
            val headings = IntArray(129) { if (reversed) 128 - it else it }
            val basis = Basis(headings, Array(258) { if (it < 129) VarStatus.BASIC else VarStatus.FIXED })

            val ordinary = verifyExactBasis(model, basis)
            val recovered = verifyExactBasis(model, basis, limits = ExactBasisLimits(sparse = true))

            assertEquals(ExactBasisDecline.DIMENSION, ordinary.metrics.decline)
            assertEquals(List(129) { BigFraction.ofLong(11L + it) }, recovered.witness?.primal)
            assertEquals(BigFraction.ofLong(417L), recovered.bound?.value)
            assertTrue(recovered.complementary)
        }
    }

    @Test
    fun `logical singleton elimination should preserve nonsymmetric transpose costs`() {
        val zero = ExactLpNumber.of(0L)
        val side = ExactLpSide(zero)
        val source = ExactLpModel(
            List(2) { column ->
                List(131) { row ->
                    val value = when (row) {
                        0 -> if (column == 0) 1L else 2L
                        1 -> if (column == 0) 3L else 1L
                        else -> 1L
                    }
                    ExactLpEntry(row, ExactLpNumber.of(value))
                }
            },
            List(131) { ExactLpNumber.of(if (it < 2) 5L else 10L) },
            List(133) { column -> ExactLpColumn(
                when {
                    column < 2 -> ExactLpBounds(side, ExactLpSide(ExactLpNumber.of(10L)))
                    column < 4 -> ExactLpBounds(side, side)
                    else -> ExactLpBounds(side)
                },
                integral = false,
            ) },
            List(131) { ExactLpRow() },
            ExactLpObjective(List(133) {
                ExactLpNumber.of(when (it) {
                    0 -> 3L
                    1 -> 4L
                    4 -> 2L
                    else -> 0L
                })
            }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(intArrayOf(0, 1) + IntArray(129) { it + 4 },
            Array(133) { if (it == 2 || it == 3) VarStatus.FIXED else VarStatus.BASIC })

        val recovered = verifyExactBasis(model, basis,
            limits = ExactBasisLimits(RationalBasisLimits(dimension = 2), sparse = true))

        assertEquals(listOf(BigFraction.ONE, BigFraction.ofLong(2L)), recovered.witness?.primal)
        assertEquals(BigFraction.ofLong(25L), recovered.witness?.objective)
        assertEquals(recovered.witness?.objective, recovered.bound?.value)
        assertTrue(recovered.complementary)
    }

    @Test
    fun `independent residual cores should share the containing factor allowance`() {
        val model = LpBuilder().apply {
            repeat(130) { addVar(0L, 10L, cost = 1L) }
            repeat(65) { block ->
                addRow(intArrayOf(2 * block, 2 * block + 1), longArrayOf(2L, 1L), Relation.EQ, 4L)
                addRow(intArrayOf(2 * block, 2 * block + 1), longArrayOf(1L, 3L), Relation.EQ, 7L)
            }
        }.build(Sense.MINIMIZE)
        val basis = Basis(IntArray(130) { it },
            Array(260) { if (it < 130) VarStatus.BASIC else VarStatus.FIXED })

        val recovered = verifyExactBasis(model, basis,
            limits = ExactBasisLimits(RationalBasisLimits(dimension = 2), sparse = true))

        assertEquals(List(130) { BigFraction.ofLong(if (it % 2 == 0) 1L else 2L) }, recovered.witness?.primal)
        assertEquals(BigFraction.ofLong(195L), recovered.bound?.value)
        assertTrue(recovered.complementary)
    }

    @Test
    fun `large strict bases should produce source interior points`() {
        val model = LpBuilder().apply {
            addRealVar(0.0, 1.0)
            repeat(129) { addRealRow(intArrayOf(0), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true) }
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0) + IntArray(128) { model.slackCol(it + 1) },
            Array(130) { if (it == 1) VarStatus.AT_LOWER else VarStatus.BASIC })

        val recovered = verifyExactBasis(model, basis, limits = ExactBasisLimits(sparse = true))

        val point = assertNotNull(recovered.witness)
        assertTrue(point.primal[0] > BigFraction.ZERO && point.primal[0] < BigFraction.ONE)
        assertNotNull(checkedLpWitness(model, point.primal))
    }

    @Test
    fun `large rounded equality contradictions should produce checked refutations`() {
        val model = LpBuilder().apply {
            repeat(129) { addRealVar(0.0, 100.0) }
            addRealRow(intArrayOf(0), doubleArrayOf(1.0), Relation.EQ, 0.1)
            addRealRow(intArrayOf(0), doubleArrayOf(0.1), Relation.EQ, 0.010000000000000002)
            for (column in 1..128) {
                addRealRow(intArrayOf(column, column - 1), doubleArrayOf(1.0, -1.0), Relation.EQ, 0.1)
            }
        }.build(Sense.MINIMIZE)
        val basis = Basis(IntArray(129) { it } + intArrayOf(model.slackCol(1)),
            Array(259) { if (it < 129 || it == model.slackCol(1)) VarStatus.BASIC else VarStatus.FIXED })

        val recovered = verifyExactBasis(model, basis, limits = ExactBasisLimits(sparse = true))

        assertNull(recovered.witness)
        assertNotNull(recovered.conflict)
    }

    @Test
    fun `an infeasible sparse basis should not refute a feasible source model`() {
        val model = LpBuilder().apply {
            repeat(129) { addVar(0L, 1L) }
            addVar(-2L, 2L)
            repeat(129) { addRow(intArrayOf(it, 129), longArrayOf(1L, 1L), Relation.EQ, 1L) }
        }.build(Sense.MINIMIZE)
        val basis = Basis(IntArray(129) { it }, Array(259) {
            when {
                it < 129 -> VarStatus.BASIC
                it == 129 -> VarStatus.AT_UPPER
                else -> VarStatus.FIXED
            }
        })

        val recovered = verifyExactBasis(model, basis, limits = ExactBasisLimits(sparse = true))

        assertNull(recovered.conflict)
        assertNotNull(checkedLpWitness(model, assertNotNull(recovered.witness).primal))
        assertNotNull(checkedLpWitness(model, List(130) { if (it < 129) BigFraction.ONE else BigFraction.ZERO }))
    }

    @Test
    fun `retained sparse factors should obey current resource and stop limits`() {
        val model = LpBuilder().apply {
            repeat(129) { addVar(0L, 1000L) }
            addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 1L)
            for (column in 1..128) {
                addRow(intArrayOf(column, column - 1), longArrayOf(1L, -1L), Relation.EQ, 1L)
            }
        }.build(Sense.MINIMIZE)
        val basis = Basis(IntArray(129) { it }, Array(258) { if (it < 129) VarStatus.BASIC else VarStatus.FIXED })
        val cache = ExactBasisCache()
        assertNotNull(verifyExactBasis(model, basis, cache = cache, limits = ExactBasisLimits(sparse = true)).witness)
        val variants = listOf(
            RationalBasisLimits(dimension = 0) to ExactBasisDecline.DIMENSION,
            RationalBasisLimits(work = 0L) to ExactBasisDecline.WORK,
            RationalBasisLimits(allocationBytes = 0L) to ExactBasisDecline.MEMORY,
            RationalBasisLimits(fill = 0) to ExactBasisDecline.FILL,
            RationalBasisLimits(bits = 0) to ExactBasisDecline.BITS,
            RationalBasisLimits(time = Duration.ZERO) to ExactBasisDecline.TIME,
        )
        for ((limit, reason) in variants) {
            val limited = verifyExactBasis(model, basis, cache = cache, limits = ExactBasisLimits(limit, sparse = true))
            assertNull(limited.witness)
            assertNull(limited.conflict)
            assertEquals(reason, limited.metrics.decline)
        }
        val stopped = verifyExactBasis(model, basis, cache = cache, cancellation = Cancellation { true },
            limits = ExactBasisLimits(sparse = true))
        val retried = verifyExactBasis(model, basis, cache = cache, limits = ExactBasisLimits(sparse = true))
        assertEquals(ExactBasisDecline.CANCELLED, stopped.metrics.decline)
        assertNull(stopped.witness)
        assertNotNull(retried.witness)
    }

    @Test
    fun `retained sparse factors should follow bounds across pop`() {
        val built = LpBuilder().apply {
            repeat(129) { addVar(0L, 1000L) }
            addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 1L)
            for (column in 1..128) {
                addRow(intArrayOf(column, column - 1), longArrayOf(1L, -1L), Relation.EQ, 1L)
            }
        }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(built.authoritativeModel()))
        val basis = Basis(IntArray(129) { it }, Array(258) { if (it < 129) VarStatus.BASIC else VarStatus.FIXED })
        val cache = ExactBasisCache()
        val limits = ExactBasisLimits(sparse = true)
        val initial = verifyExactBasis(assertNotNull(trail.state.ownerWorkingModel()), basis, cache = cache,
            limits = limits)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(128, true, ExactLpSide(ExactLpNumber.of(100L)), 7L))

        val refuted = verifyExactBasis(assertNotNull(trail.state.ownerWorkingModel()), basis, cache = cache,
            limits = limits)
        assertTrue(trail.pop(0))
        val restored = verifyExactBasis(assertNotNull(trail.state.ownerWorkingModel()), basis, cache = cache,
            limits = limits)

        assertNotNull(initial.witness)
        assertNull(refuted.witness)
        assertNotNull(refuted.conflict)
        assertNotNull(restored.witness)
        assertNull(restored.conflict)
    }

    @Test
    fun `irreducible sparse cores should retain the dense dimension limit`() {
        val model = LpBuilder().apply {
            repeat(130) { addVar(0L, 2L) }
            repeat(130) { row -> addRow(IntArray(130) { it },
                LongArray(130) { if (it == row) 2L else 1L }, Relation.EQ, 131L) }
        }.build(Sense.MINIMIZE)
        val basis = Basis(IntArray(130) { it }, Array(260) { if (it < 130) VarStatus.BASIC else VarStatus.FIXED })

        val recovered = verifyExactBasis(model, basis,
            limits = ExactBasisLimits(RationalBasisLimits(fill = 20000), sparse = true))

        assertEquals(ExactBasisDecline.DIMENSION, recovered.metrics.decline)
        assertNull(recovered.witness)
        assertNull(recovered.conflict)
    }
}
