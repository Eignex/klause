package com.eignex.klause.bench.profile

import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.Cancellation
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResidualBudgetGateTest {
    @ParameterizedTest
    @ValueSource(longs = [4L, 5L])
    fun `exact budget should discharge only an implied cutoff row`(bound: Long) {
        val row = PseudoBoolean(LongArray(10) { 1L }, IntArray(10) { it * 2 }, PbOp.LE, bound)

        val accepted = ResidualBudgetGate.impliedByCutoff(
            row,
            List(10) { 1L },
            BigInteger.ZERO,
            BigInteger.valueOf(6),
            emptyMap(),
            ComponentAllowance(Cancellation.Never),
        )

        assertEquals(bound == 5L, accepted)
    }

    @Test
    fun `normalization should preserve signed costs constants repetitions and fixed terms`() {
        val row = PseudoBoolean(longArrayOf(2L, 2L, 6L), intArrayOf(1, 1, 2), PbOp.GE, 4L)

        val accepted = ResidualBudgetGate.impliedByCutoff(
            row,
            listOf(4L, -6L),
            BigInteger.TEN,
            BigInteger.valueOf(11),
            mapOf(0 to true),
            ComponentAllowance(Cancellation.Never),
        )

        assertTrue(accepted)
    }

    @Test
    fun `unrelated learned bridge should remain enforcing`() {
        val row = PseudoBoolean(longArrayOf(1L, 2L), intArrayOf(0, 2), PbOp.LE, 2L)

        val accepted = ResidualBudgetGate.impliedByCutoff(
            row,
            listOf(1L, 1L),
            BigInteger.ZERO,
            BigInteger.valueOf(3),
            emptyMap(),
            ComponentAllowance(Cancellation.Never),
        )

        assertFalse(accepted)
    }

    @Test
    fun `domain satisfied row should need no budget edge`() {
        val row = PseudoBoolean(longArrayOf(1L, 2L), intArrayOf(0, 2), PbOp.LE, 3L)

        val accepted = ResidualBudgetGate.impliedByCutoff(
            row,
            listOf(1L, 1L),
            BigInteger.ZERO,
            BigInteger.valueOf(3),
            emptyMap(),
            ComponentAllowance(Cancellation.Never),
        )

        assertTrue(accepted)
    }

    @Test
    fun `unnameable atom should prevent budget certification`() {
        val row = PseudoBoolean(longArrayOf(1L), intArrayOf(20), PbOp.LE, 5L)

        val accepted = ResidualBudgetGate.impliedByCutoff(
            row,
            listOf(1L),
            BigInteger.ZERO,
            BigInteger.valueOf(6),
            emptyMap(),
            ComponentAllowance(Cancellation.Never),
        )

        assertFalse(accepted)
    }

    @Test
    fun `interrupted normalization should publish no budget certification`() {
        val row = PseudoBoolean(longArrayOf(1L), intArrayOf(0), PbOp.LE, 5L)

        val accepted = ResidualBudgetGate.impliedByCutoff(
            row,
            listOf(1L),
            BigInteger.ZERO,
            BigInteger.valueOf(6),
            emptyMap(),
            ComponentAllowance(Cancellation { true }),
        )

        assertFalse(accepted)
    }
}
