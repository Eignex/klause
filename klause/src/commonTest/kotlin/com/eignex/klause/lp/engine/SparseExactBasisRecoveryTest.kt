package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparseExactBasisRecoveryTest {
    @Test
    fun `sparse feasibility should recover opposing strict interiors in either direction`() {
        for (upper in listOf(false, true)) {
            val model = LpBuilder().apply {
                repeat(2) { addRealVar(0.0, 1.0) }
                val relation = if (upper) Relation.LE else Relation.GE
                addRealRow(intArrayOf(0, 1), doubleArrayOf(1.0, -1.0), relation, 0.0, strict = true)
                addRealRow(intArrayOf(1), doubleArrayOf(1.0), relation, if (upper) 1.0 else 0.0, strict = true)
                repeat(127) { addRealRow(intArrayOf(0), doubleArrayOf(1.0), Relation.LE, 1.0) }
            }.build(Sense.MINIMIZE)
            val basis = Basis(IntArray(129) { model.slackCol(it) }, Array(131) {
                if (it >= 2) VarStatus.BASIC else if (upper) VarStatus.AT_UPPER else VarStatus.AT_LOWER
            })

            val recovered = verifyExactBasis(model, basis,
                limits = ExactBasisLimits(sparse = true, maxRecoveryPivots = 2))

            val point = assertNotNull(recovered.witness)
            assertNotNull(checkedLpWitness(model, point.primal))
            assertTrue(point.primal.all { it > BigFraction.ZERO && it < BigFraction.ONE })
            assertTrue(if (upper) point.primal[0] < point.primal[1] else point.primal[0] > point.primal[1])
            assertNull(recovered.conflict)
        }
    }

    @Test
    fun `sparse feasibility should refute a strict cycle after changing its basis`() {
        val model = LpBuilder().apply {
            repeat(2) { addRealVar(0.0, 1.0) }
            addRealRow(intArrayOf(0, 1), doubleArrayOf(1.0, -1.0), Relation.GE, 0.0, strict = true)
            addRealRow(intArrayOf(1, 0), doubleArrayOf(1.0, -1.0), Relation.GE, 0.0)
            repeat(127) { addRealRow(intArrayOf(0), doubleArrayOf(1.0), Relation.LE, 1.0) }
        }.build(Sense.MINIMIZE)
        val basis = Basis(IntArray(129) { model.slackCol(it) },
            Array(131) { if (it < 2) VarStatus.AT_LOWER else VarStatus.BASIC })

        val recovered = verifyExactBasis(model, basis,
            limits = ExactBasisLimits(sparse = true, maxRecoveryPivots = 1))

        assertNull(recovered.witness)
        assertNotNull(recovered.conflict)
    }

    @Test
    fun `a zero sparse pivot allowance should leave cached factors usable`() {
        val model = LpBuilder().apply {
            repeat(2) { addRealVar(0.0, 1.0) }
            addRealRow(intArrayOf(0, 1), doubleArrayOf(1.0, -1.0), Relation.GE, 0.0, strict = true)
            addRealRow(intArrayOf(1), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
            repeat(127) { addRealRow(intArrayOf(0), doubleArrayOf(1.0), Relation.LE, 1.0) }
        }.build(Sense.MINIMIZE)
        val basis = Basis(IntArray(129) { model.slackCol(it) },
            Array(131) { if (it < 2) VarStatus.AT_LOWER else VarStatus.BASIC })
        val cache = ExactBasisCache()

        val limited = verifyExactBasis(model, basis, cache = cache,
            limits = ExactBasisLimits(sparse = true, maxRecoveryPivots = 0))
        val retried = verifyExactBasis(model, basis, cache = cache,
            limits = ExactBasisLimits(sparse = true, maxRecoveryPivots = 2))

        assertEquals(ExactBasisDecline.PIVOTS, limited.metrics.decline)
        assertNull(limited.witness)
        assertNull(limited.conflict)
        assertNotNull(checkedLpWitness(model, assertNotNull(retried.witness).primal))
    }

    @Test
    fun `an exhausted sparse pivot prefix should preserve its independent bound`() {
        val model = LpBuilder().apply {
            repeat(2) { addRealVar(0.0, 1.0) }
            addRealRow(intArrayOf(0, 1), doubleArrayOf(1.0, -1.0), Relation.GE, 0.0, strict = true)
            addRealRow(intArrayOf(1), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
            repeat(127) { addRealRow(intArrayOf(0), doubleArrayOf(1.0), Relation.LE, 1.0) }
        }.build(Sense.MINIMIZE)
        val basis = Basis(IntArray(129) { model.slackCol(it) },
            Array(131) { if (it < 2) VarStatus.AT_LOWER else VarStatus.BASIC })

        val limited = verifyExactBasis(model, basis,
            limits = ExactBasisLimits(sparse = true, maxRecoveryPivots = 1))

        assertEquals(ExactBasisDecline.PIVOTS, limited.metrics.decline)
        assertEquals(BigFraction.ZERO, assertNotNull(limited.bound).value)
        assertNull(limited.witness)
        assertNull(limited.conflict)
    }

    @Test
    fun `sparse feasibility should check fresh strict sibling bounds after pop`() {
        val built = LpBuilder().apply {
            repeat(2) { addRealVar(0.0, 1.0) }
            addRealRow(intArrayOf(0, 1), doubleArrayOf(1.0, -1.0), Relation.GE, 0.0, strict = true)
            addRealRow(intArrayOf(1), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
            repeat(127) { addRealRow(intArrayOf(0), doubleArrayOf(1.0), Relation.LE, 1.0) }
        }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(built.authoritativeModel()))
        val basis = Basis(IntArray(129) { built.slackCol(it) },
            Array(131) { if (it < 2) VarStatus.AT_LOWER else VarStatus.BASIC })
        val cache = ExactBasisCache()
        val limits = ExactBasisLimits(sparse = true, maxRecoveryPivots = 2)
        val initial = verifyExactBasis(assertNotNull(trail.state.ownerWorkingModel()), basis,
            cache = cache, limits = limits)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(0L)), 9L))

        val refuted = verifyExactBasis(assertNotNull(trail.state.ownerWorkingModel()), basis,
            cache = cache, limits = limits)
        assertTrue(trail.pop(0))
        val restoredModel = assertNotNull(trail.state.ownerWorkingModel())
        val restored = verifyExactBasis(restoredModel, basis, cache = cache, limits = limits)

        assertNotNull(initial.witness)
        assertNull(refuted.witness)
        assertNotNull(refuted.conflict)
        assertNotNull(checkedLpWitness(restoredModel, assertNotNull(restored.witness).primal))
        assertNull(restored.conflict)
    }
}
