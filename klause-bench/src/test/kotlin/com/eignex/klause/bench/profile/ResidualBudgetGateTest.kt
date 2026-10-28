package com.eignex.klause.bench.profile

import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.Cancellation
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResidualBudgetGateTest {
    @Test
    fun `exact budget should discharge only an implied cutoff row`() {
        val weights = List(10) { 1L }
        val literals = IntArray(10) { it * 2 }
        val exact = PseudoBoolean(LongArray(10) { 1L }, literals, PbOp.LE, 5L)
        val stronger = PseudoBoolean(LongArray(10) { 1L }, literals, PbOp.LE, 4L)

        assertTrue(ResidualBudgetGate.impliedByCutoff(exact, weights, BigInteger.ZERO, BigInteger.valueOf(6),
            emptyMap(), ComponentAllowance(Cancellation.Never)))
        assertFalse(ResidualBudgetGate.impliedByCutoff(stronger, weights, BigInteger.ZERO, BigInteger.valueOf(6),
            emptyMap(), ComponentAllowance(Cancellation.Never)))
    }

    @Test
    fun `normalization should preserve signed costs constants repetitions and fixed terms`() {
        val row = PseudoBoolean(longArrayOf(2L, 2L, 6L), intArrayOf(1, 1, 2), PbOp.GE, 4L)

        assertTrue(ResidualBudgetGate.impliedByCutoff(row, listOf(4L, -6L), BigInteger.TEN,
            BigInteger.valueOf(11), mapOf(0 to true), ComponentAllowance(Cancellation.Never)))
    }

    @Test
    fun `unrelated learned bridge should remain enforcing`() {
        val row = PseudoBoolean(longArrayOf(1L, 2L), intArrayOf(0, 2), PbOp.LE, 2L)

        assertFalse(ResidualBudgetGate.impliedByCutoff(row, listOf(1L, 1L), BigInteger.ZERO,
            BigInteger.valueOf(3), emptyMap(), ComponentAllowance(Cancellation.Never)))
    }

    @Test
    fun `domain satisfied row should need no budget edge`() {
        val row = PseudoBoolean(longArrayOf(1L, 2L), intArrayOf(0, 2), PbOp.LE, 3L)

        assertTrue(ResidualBudgetGate.impliedByCutoff(row, listOf(1L, 1L), BigInteger.ZERO,
            BigInteger.valueOf(3), emptyMap(), ComponentAllowance(Cancellation.Never)))
    }

    @Test
    fun `unnameable atom should prevent budget certification`() {
        val row = PseudoBoolean(longArrayOf(1L), intArrayOf(20), PbOp.LE, 5L)

        assertFalse(ResidualBudgetGate.impliedByCutoff(row, listOf(1L), BigInteger.ZERO,
            BigInteger.valueOf(6), emptyMap(), ComponentAllowance(Cancellation.Never)))
    }

    @Test
    fun `interrupted normalization should publish no budget certification`() {
        val row = PseudoBoolean(longArrayOf(1L), intArrayOf(0), PbOp.LE, 5L)

        assertFalse(ResidualBudgetGate.impliedByCutoff(row, listOf(1L), BigInteger.ZERO,
            BigInteger.valueOf(6), emptyMap(), ComponentAllowance(Cancellation { true })))
    }
}
