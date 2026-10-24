package com.eignex.klause.lp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceLpFundingTest {
    @Test
    fun `all numerical and certificate stages should debit the same cumulative allowance`() {
        val funding = SourceLpFunding(20, 100)
        val budget = SourceLpBudget(funding = funding)
        funding.grant(20)

        budget.observe(preparation = 1, floatWork = 2, continuation = 3, refinement = 4,
            boundCertification = 5, conflictCertification = 6)

        assertEquals(21L, funding.spent)
        assertTrue(funding.exhausted)
    }

    @Test
    fun `a spent grant pauses without exhausting the containing allowance`() {
        val funding = SourceLpFunding(10, 10)
        funding.grant(3)

        funding.charge(4)

        assertTrue(funding.paused)
        assertFalse(funding.exhausted)
        funding.grant(3)
        assertFalse(funding.paused)
        assertEquals(4L, funding.spent)
    }

    @Test
    fun `grants cannot renew cumulative work or allocation`() {
        for (allocation in listOf(false, true)) {
            val funding = SourceLpFunding(10, 10)
            funding.grant(100)
            funding.charge(if (allocation) 1 else 11, if (allocation) 11 else 0)

            funding.grant(100)

            assertTrue(funding.exhausted)
            assertTrue(funding.paused)
        }
    }
}
