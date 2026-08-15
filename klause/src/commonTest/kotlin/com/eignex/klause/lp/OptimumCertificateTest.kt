package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Certifying that an optimum found inside the search box is the model's optimum.
 *
 * The risk runs one way: claiming nothing is better when something is turns a merely-feasible answer into
 * a false `OPTIMUM FOUND`. So the tests that matter are the ones asserting it stays silent.
 */
class OptimumCertificateTest {

    /** `x0 >= 3` with `x0` open above, minimising `x0`; the optimum is 3. */
    private fun atLeastThree(): DeferredIntBounds = DeferredIntBounds(
        openBounds = arrayOf(OpenIntBounds(0L, null)),
        intConstraints = listOf(Linear(longArrayOf(1L), intArrayOf(0), LinearOp.GE, 3L)),
        realConstraints = emptyList(),
        numReal = 0,
        fallbackLo = -1000L,
        fallbackHi = 1000L,
        lossy = true,
    )

    @Test
    fun `nothing beats the true optimum`() {
        assertTrue(atLeastThree().noBetterThan(longArrayOf(1L), constant = 0L, maximize = false, value = 3L))
    }

    @Test
    fun `stays silent when something better exists`() {
        // 4 is feasible and beats 5, so no certificate may be issued.
        assertFalse(atLeastThree().noBetterThan(longArrayOf(1L), constant = 0L, maximize = false, value = 5L))
    }

    @Test
    fun `the objective constant is accounted for`() {
        // With a constant of 10 the same optimum reports as 13; the row must subtract it back out.
        val d = atLeastThree()
        assertTrue(d.noBetterThan(longArrayOf(1L), constant = 10L, maximize = false, value = 13L))
        assertFalse(d.noBetterThan(longArrayOf(1L), constant = 10L, maximize = false, value = 15L))
    }

    @Test
    fun `a maximisation is certified in its own direction`() {
        // x0 <= 3 from the row, x0 open below and declared no higher than 10: maximising, the optimum is 3.
        val d = DeferredIntBounds(
            openBounds = arrayOf(OpenIntBounds(null, 10L)),
            intConstraints = listOf(Linear(longArrayOf(1L), intArrayOf(0), LinearOp.LE, 3L)),
            realConstraints = emptyList(),
            numReal = 0,
            fallbackLo = -1000L,
            fallbackHi = 1000L,
            lossy = true,
        )
        assertTrue(d.noBetterThan(longArrayOf(1L), constant = 0L, maximize = true, value = 3L))
        assertFalse(d.noBetterThan(longArrayOf(1L), constant = 0L, maximize = true, value = 1L))
    }

    @Test
    fun `an objective with no integer terms is not certifiable`() {
        assertFalse(atLeastThree().noBetterThan(longArrayOf(0L), constant = 0L, maximize = false, value = 3L))
    }

    @Test
    fun `a target that would wrap is refused`() {
        assertFalse(
            atLeastThree().noBetterThan(longArrayOf(1L), constant = 1L, maximize = false, value = Long.MIN_VALUE),
        )
    }
}
