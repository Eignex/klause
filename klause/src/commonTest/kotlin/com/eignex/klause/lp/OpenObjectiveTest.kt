package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Certifying an in-box optimum against the relaxation's dual bound.
 *
 * The risk runs one way: claiming nothing is better when something is turns a merely-feasible answer into
 * a false `OPTIMUM FOUND`. So the tests that matter are the ones asserting it stays silent.
 */
class OpenObjectiveTest {

    private fun open(lo: Long?, hi: Long?) = arrayOf(OpenIntBounds(lo, hi))

    private fun certify(
        bounds: Array<OpenIntBounds>,
        rows: List<Linear>,
        coeffs: LongArray,
        value: Long,
        maximize: Boolean = false,
        constant: Long = 0L,
    ) = nothingBeatsOverOpenRanges(
        bounds,
        rows,
        realConstraints = emptyList(),
        realLower = DoubleArray(0),
        realUpper = DoubleArray(0),
        objective = OpenObjective(coeffs, DoubleArray(0), constant, maximize),
        value = value,
    )

    @Test
    fun `an incumbent at the relaxation optimum is certified`() {
        // x0 >= 3, x0 open above, minimising x0: the relaxation's optimum is 3, so 3 cannot be beaten.
        val rows = listOf(Linear(longArrayOf(1L), intArrayOf(0), LinearOp.GE, 3L))
        assertTrue(certify(open(0L, null), rows, longArrayOf(1L), value = 3L))
    }

    @Test
    fun `stays silent when the bound leaves room below the incumbent`() {
        val rows = listOf(Linear(longArrayOf(1L), intArrayOf(0), LinearOp.GE, 3L))
        assertFalse(certify(open(0L, null), rows, longArrayOf(1L), value = 5L))
    }

    @Test
    fun `a maximisation is certified in its own direction`() {
        // x0 <= 3 with x0 open below, maximising: the optimum is 3.
        val rows = listOf(Linear(longArrayOf(1L), intArrayOf(0), LinearOp.LE, 3L))
        assertTrue(certify(open(null, 10L), rows, longArrayOf(1L), value = 3L, maximize = true))
        assertFalse(certify(open(null, 10L), rows, longArrayOf(1L), value = 1L, maximize = true))
    }

    @Test
    fun `the objective constant is accounted for`() {
        val rows = listOf(Linear(longArrayOf(1L), intArrayOf(0), LinearOp.GE, 3L))
        assertTrue(certify(open(0L, null), rows, longArrayOf(1L), value = 13L, constant = 10L))
        assertFalse(certify(open(0L, null), rows, longArrayOf(1L), value = 15L, constant = 10L))
    }

    @Test
    fun `an objective unbounded below yields no certificate`() {
        // Minimising x0 with nothing stopping it: the relaxation has no optimum to compare against.
        val rows = listOf(Linear(longArrayOf(1L), intArrayOf(0), LinearOp.LE, 100L))
        assertFalse(certify(open(null, null), rows, longArrayOf(1L), value = -5L))
    }

    @Test
    fun `a closed model is left to the search that already decided it`() {
        val rows = listOf(Linear(longArrayOf(1L), intArrayOf(0), LinearOp.GE, 3L))
        assertFalse(certify(arrayOf(OpenIntBounds(0L, 10L)), rows, longArrayOf(1L), value = 3L))
    }

    @Test
    fun `a continuous objective term is certified without any rounding step`() {
        // min x0 + 0.5*r0 over x0 >= 3 (open above) and r0 >= 4: the optimum is 5, so 5 cannot be beaten.
        val intRow = Linear(longArrayOf(1L), intArrayOf(0), LinearOp.GE, 3L)
        val realRow = Linear(IntArray(0), DoubleArray(0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 4.0)
        fun ask(value: Long) = nothingBeatsOverOpenRanges(
            open(0L, null),
            listOf(intRow),
            realConstraints = listOf(realRow),
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            objective = OpenObjective(longArrayOf(1L), doubleArrayOf(0.5), 0L, maximize = false),
            value = value,
        )
        assertTrue(ask(5L), "nothing beats the optimum of 5")
        assertFalse(ask(9L), "4 and 5 both beat 9 so no certificate may be issued")
    }

    @Test
    fun `an empty objective yields no certificate`() {
        val rows = listOf(Linear(longArrayOf(1L), intArrayOf(0), LinearOp.GE, 3L))
        assertFalse(certify(open(0L, null), rows, longArrayOf(0L), value = 3L))
    }
}
