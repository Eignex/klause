package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactBasisSolveTest {
    @Test
    fun `basis reconstruction restores each minor after row exchanges`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 10L)
            val y = addVar(0L, 10L)
            val z = addVar(0L, 10L)
            addRow(intArrayOf(y, z), longArrayOf(2L, 1L), Relation.EQ, 7L)
            addRow(intArrayOf(x, y), longArrayOf(3L, 1L), Relation.EQ, 5L)
            addRow(intArrayOf(x, z), longArrayOf(1L, 4L), Relation.EQ, 13L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(
            intArrayOf(0, 1, 2),
            Array(model.numVars) { if (it < model.n) VarStatus.BASIC else VarStatus.AT_LOWER },
        )

        val witness = assertNotNull(exactBasisWitness(model, basis))

        assertEquals(listOf(1L, 2L, 3L).map(BigFraction::ofLong), witness.primal)
    }

    @Test
    fun `basis rays annihilate other columns after minor row exchanges`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 10L)
            val y = addVar(0L, 10L)
            val z = addVar(0L, 10L)
            addRow(intArrayOf(y, z), longArrayOf(2L, 1L), Relation.EQ, 7L)
            addRow(intArrayOf(x, y), longArrayOf(3L, 1L), Relation.EQ, 5L)
            addRow(intArrayOf(x, z), longArrayOf(1L, 4L), Relation.EQ, 13L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(
            intArrayOf(0, 1, 2),
            Array(model.numVars) { if (it < model.n) VarStatus.BASIC else VarStatus.AT_LOWER },
        )

        for (row in 0 until model.m) {
            val ray = assertNotNull(exactFarkasRay(model, basis, row))
            for (col in 0 until model.n) {
                var product = 0L
                model.forEachInColumn(col) { i, value -> product += ray[i] * value }
                if (col == row) assertTrue(product > 0L) else assertEquals(0L, product)
            }
        }
    }

    @Test
    fun `near zero equalities remain contradictory for every point candidate`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 1e-10)
        }.build(Sense.MINIMIZE)

        for (candidate in listOf(0.0, 1e-10)) {
            assertFalse(exactPointFeasible(model, doubleArrayOf(candidate)))
            assertNull(exactPointWitness(model, doubleArrayOf(candidate)))
        }
    }

    @Test
    fun `reconstructed point is accepted only after exact row validation`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(3L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)

        val witness = assertNotNull(exactPointWitness(model, doubleArrayOf(1.0 / 3.0)))

        assertEquals(BigFraction.ONE, BigFraction.ofLong(3L) * witness.primal.single())
        assertEquals(BigFraction.ZERO, witness.objective)
        assertTrue(exactPointFeasible(model, doubleArrayOf(1.0 / 3.0)))
    }

    @Test
    fun `binary decimal projection is not a parsed decimal witness`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.1)
        }.build(Sense.MINIMIZE)
        val decimal = BigFraction.of(BigInteger.ONE, BigInteger.fromInt(10))

        val witness = assertNotNull(exactPointWitness(model, doubleArrayOf(0.1)))

        assertEquals(BigFraction.ofDouble(0.1), witness.primal.single())
        assertTrue(witness.primal.single() != decimal)
        assertNull(checkedLpWitness(model, listOf(decimal)))
    }

    @Test
    fun `strict boundaries fail both point and basis acceptance`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 1.0, strict = true)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))

        assertFalse(exactPointFeasible(model, doubleArrayOf(1.0)))
        assertEquals(false, exactBasisFeasible(model, basis))
        assertNull(exactBasisWitness(model, basis))
        assertTrue(exactPointFeasible(model, doubleArrayOf(0.5)))
    }

    @Test
    fun `dyadic basis reconstruction checks the fractional original upper`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 0.5)
            addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.EQ, 0.25)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))

        val witness = assertNotNull(exactBasisWitness(model, basis))

        assertEquals(BigFraction.ofDouble(0.5), witness.primal.single())
        assertEquals(true, exactBasisFeasible(model, basis))
        model.doubleView!!.upper[0] = 0.25
        assertEquals(false, exactBasisFeasible(model, basis))
    }

    @Test
    fun `fractional nonbasic upper cannot be replaced by a rounded seat`() {
        val model = LpBuilder().apply { addRealVar(0.0, 0.5) }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(), arrayOf(VarStatus.AT_UPPER))

        assertNull(exactBasisFeasible(model, basis))
        assertNull(exactBasisWitness(model, basis))
        assertTrue(exactPointFeasible(model, doubleArrayOf(0.5)))
    }

    @Test
    fun `malformed declarations decline before a determinant or point read`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 2L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 1L)
        }.build(Sense.MINIMIZE)
        val declarations = listOf(
            Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER, VarStatus.AT_LOWER)),
            Basis(intArrayOf(0), arrayOf(VarStatus.AT_LOWER, VarStatus.AT_LOWER)),
            Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.BASIC)),
            Basis(intArrayOf(2), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC)),
            Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_UPPER)),
        )

        for (basis in declarations) assertNull(exactBasisFeasible(model, basis))
        for (point in listOf(doubleArrayOf(), doubleArrayOf(Double.NaN), doubleArrayOf(Double.POSITIVE_INFINITY))) {
            assertFalse(exactPointFeasible(model, point))
        }
    }

    @Test
    fun `overflowing basis minor declines without certifying a point`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val y = addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(Long.MAX_VALUE), Relation.EQ, 1L)
            addRow(intArrayOf(y), longArrayOf(Long.MAX_VALUE), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(
            intArrayOf(0, 1),
            arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.AT_LOWER, VarStatus.AT_LOWER),
        )

        assertNull(exactBasisFeasible(model, basis))
        assertNull(exactBasisWitness(model, basis))
    }

    @Test
    fun `point reconstruction declines a vector beyond its common denominator budget`() {
        val denominators = longArrayOf(1000003L, 1000033L, 1000037L)
        val model = LpBuilder().apply {
            for (denominator in denominators) {
                val x = addVar(0L, 1L)
                addRow(intArrayOf(x), longArrayOf(denominator), Relation.EQ, 1L)
            }
        }.build(Sense.MINIMIZE)
        val candidate = DoubleArray(denominators.size) { 1.0 / denominators[it] }

        assertNull(exactPointWitness(model, candidate))
        assertFalse(exactPointFeasible(model, candidate))
        val basis = Basis(
            intArrayOf(0, 1, 2),
            Array(model.numVars) { if (it < model.n) VarStatus.BASIC else VarStatus.AT_LOWER },
        )
        val witness = assertNotNull(exactBasisWitness(model, basis))
        for (j in denominators.indices) {
            assertEquals(BigFraction.ONE, BigFraction.ofLong(denominators[j]) * witness.primal[j])
        }
    }

    @Test
    fun `basis minors retain the original nonunit matrix`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val y = addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(3L), Relation.EQ, 1L)
            addRow(intArrayOf(y), longArrayOf(5L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(
            intArrayOf(0, 1),
            arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.AT_LOWER, VarStatus.AT_LOWER),
        )

        val witness = assertNotNull(exactBasisWitness(model, basis))

        assertEquals(BigFraction.ONE, BigFraction.ofLong(3L) * witness.primal[0])
        assertEquals(BigFraction.ONE, BigFraction.ofLong(5L) * witness.primal[1])
        assertEquals(true, exactBasisFeasible(model, basis))
    }
}
