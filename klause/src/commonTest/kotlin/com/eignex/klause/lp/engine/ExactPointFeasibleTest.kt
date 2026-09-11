package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactPointFeasibleTest {
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


}
