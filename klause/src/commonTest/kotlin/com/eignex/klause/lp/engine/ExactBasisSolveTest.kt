package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactBasisSolveTest {
    @Test
    fun `basis reconstruction preserves nonsymmetric source equations`() {
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

        val witness = assertNotNull(verifyExactBasis(model, basis).witness)

        assertEquals(listOf(1L, 2L, 3L).map(BigFraction::ofLong), witness.primal)
    }

    @Test
    fun `strict boundaries fail both point and basis acceptance`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 1.0, strict = true)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))

        assertFalse(exactPointFeasible(model, doubleArrayOf(1.0)))
        assertNull(verifyExactBasis(model, basis).witness)
        assertNull(verifyExactBasis(model, basis).witness)
        assertTrue(exactPointFeasible(model, doubleArrayOf(0.5)))
    }

    @Test
    fun `dyadic basis reconstruction checks the fractional original upper`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 0.5)
            addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.EQ, 0.25)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))

        val witness = assertNotNull(verifyExactBasis(model, basis).witness)

        assertEquals(BigFraction.ofDouble(0.5), witness.primal.single())
        assertNotNull(verifyExactBasis(model, basis).witness)
        model.doubleView!!.upper[0] = 0.25
        assertNull(verifyExactBasis(model, basis).witness)
    }

    @Test
    fun `fractional nonbasic upper is seated exactly`() {
        val model = LpBuilder().apply { addRealVar(0.0, 0.5) }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(), arrayOf(VarStatus.AT_UPPER))

        assertNotNull(verifyExactBasis(model, basis).witness)
        assertNotNull(verifyExactBasis(model, basis).witness)
        assertTrue(exactPointFeasible(model, doubleArrayOf(0.5)))
    }

    @Test
    fun `malformed declarations decline before basis solving`() {
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

        for (basis in declarations) assertNull(verifyExactBasis(model, basis).witness)
        for (point in listOf(doubleArrayOf(), doubleArrayOf(Double.NaN), doubleArrayOf(Double.POSITIVE_INFINITY))) {
            assertFalse(exactPointFeasible(model, point))
        }
    }

    @Test
    fun `large determinant does not prevent exact basis verification`() {
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

        assertNotNull(verifyExactBasis(model, basis).witness)
        assertNotNull(verifyExactBasis(model, basis).witness)
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
        val witness = assertNotNull(verifyExactBasis(model, basis).witness)
        for (j in denominators.indices) {
            assertEquals(BigFraction.ONE, BigFraction.ofLong(denominators[j]) * witness.primal[j])
        }
    }

    @Test
    fun `basis reconstruction retains the original nonunit matrix`() {
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

        val witness = assertNotNull(verifyExactBasis(model, basis).witness)

        assertEquals(BigFraction.ONE, BigFraction.ofLong(3L) * witness.primal[0])
        assertEquals(BigFraction.ONE, BigFraction.ofLong(5L) * witness.primal[1])
        assertNotNull(verifyExactBasis(model, basis).witness)
    }
}
