package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpFloatTermination
import com.eignex.klause.lp.engine.LpRootGuidance
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthoritySessionTest {
    private val observer = object : LpCertificationObserver {
        override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
        override fun observeExactInput(accepted: Boolean) = Unit
        override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
    }
    private val scope = SourceScope(SourceRevision("session-fixture", "exact-affine"))

    @Test
    fun `unchanged funding stops should cache until the query allowance is promoted`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
        )
        val packed = assertNotNull(LinearAuthorityModel.prepare(rows, 2, Cancellation.Never))
        val costs = List(2) { BigFraction.ONE }
        val budget = SourceLpBudget(maxOperations = 1)
        val policy = SparseSourceLpPolicy(floating = LpFloatAllowance(100_000L, 1), quantum = 100_000L)
        LinearAuthoritySession(budget).use { session ->
            fun advance(policy: SparseSourceLpPolicy): LpRootGuidance = assertNotNull(session.advance(
                packed, costs, BigFraction.ZERO, scope, Cancellation.Never, policy, observer,
            ) { guidance, _, _ -> guidance })
            val stopped = advance(policy)
            val work = budget.measuredFloatWork
            val preparation = budget.measuredPreparationWork

            assertFalse(stopped.paused)
            assertEquals(LpFloatTermination.PIVOTS, stopped.termination)
            assertNull(stopped.primal)
            assertEquals(LpFloatTermination.PIVOTS, advance(policy).termination)
            assertEquals(work, budget.measuredFloatWork)
            val resumed = advance(policy.copy(floating = LpFloatAllowance(200_000L, 100)))

            assertNotNull(resumed.primal)
            assertEquals(preparation, budget.measuredPreparationWork)
            assertEquals(1L, session.preparations)
            assertEquals(1, budget.operations)
        }
    }

    @Test
    fun `a caller funded query should pass the unfunded pivot ceiling without another preparation`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
        )
        val packed = assertNotNull(LinearAuthorityModel.prepare(rows, 2, Cancellation.Never))
        val funding = SourceLpFunding(1_000_000L, 1_000_000L)
        val budget = SourceLpBudget(maxOperations = 1, funding = funding)
        val policy = SparseSourceLpPolicy(floating = LpFloatAllowance(100_000L, 1), quantum = 1L)
        LinearAuthoritySession(budget).use { session ->
            funding.grant(100_000L)
            var result: LpRootGuidance? = null
            repeat(16) {
                if (result?.primal == null) {
                    result = session.advance(packed, List(2) { BigFraction.ONE }, BigFraction.ZERO, scope,
                        Cancellation.Never, policy, observer) { guidance, _, _ -> guidance }
                    funding.grant(100_000L)
                }
            }

            assertNotNull(assertNotNull(result).primal)
            assertFalse(funding.exhausted)
            assertTrue(funding.spent >= budget.measuredFloatWork)
            assertEquals(1L, session.preparations)
            assertEquals(1, budget.operations)
        }
    }
}
