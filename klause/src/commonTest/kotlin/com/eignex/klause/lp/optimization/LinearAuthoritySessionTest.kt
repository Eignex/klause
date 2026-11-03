package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.SparseSourceLp
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.SparseSourceLpRowPolicy
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpFloatTermination
import com.eignex.klause.lp.engine.LpRootGuidance
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinearAuthoritySessionTest {
    private val observer = object : LpCertificationObserver {
        override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
        override fun observeExactInput(accepted: Boolean) = Unit
        override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
    }
    private val scope = SourceScope(SourceRevision("session-fixture", "exact-affine"))

    @Test
    fun `suspending a row after a packed equality should preserve scoped bounds and the numerical owner`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(2)),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ofLong(2)),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
        )
        val root = assertNotNull(LinearAuthorityModel.prepare(rows, 2, Cancellation.Never))
        val retired = assertNotNull(LinearAuthorityModel.prepare(rows, 2, Cancellation.Never, setOf(6)))
        val costs = listOf(BigFraction.MINUS_ONE, BigFraction.ZERO)
        val policy = SparseSourceLpPolicy(rowPolicy = SparseSourceLpRowPolicy.UNCONDITIONAL_EQUALITIES)
        LinearAuthoritySession(SourceLpBudget()).use { session ->
            val first = assertNotNull(session.advance(root, costs, BigFraction.ZERO, scope,
                Cancellation.Never, policy, observer) { guidance, _, _ -> guidance })
            assertEquals(BigFraction.ofLong(2).reciprocal().negated(), assertNotNull(first.bound).value)

            val changed = assertNotNull(session.advance(retired, costs, BigFraction.ZERO, scope,
                Cancellation.Never, policy, observer) { guidance, _, _ -> guidance })
            val restored = assertNotNull(session.advance(root, costs, BigFraction.ZERO, scope,
                Cancellation.Never, policy, observer) { guidance, _, _ -> guidance })

            assertEquals(BigFraction.MINUS_ONE, assertNotNull(changed.bound).value)
            assertEquals(assertNotNull(first.bound).value, assertNotNull(restored.bound).value)
            assertEquals(1L, session.preparations)
        }
    }

    @Test
    fun `batched source rebinding should preserve witnesses strict rows and scope retraction`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(5)),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ofLong(5)),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ofLong(9), true),
        )
        val costs = List(2) { BigFraction.ONE }
        val packed = assertNotNull(LinearAuthorityModel.prepare(rows, 2, Cancellation.Never))
        val child = assertNotNull(LinearAuthorityModel.prepare(rows + ExactRationalInequality(intArrayOf(0),
            listOf(BigFraction.MINUS_ONE), BigFraction.ofLong(-2)), 2, Cancellation.Never))
        val model = assertNotNull(SparseSourceLp.prepare(packed.rows,
            packed.columns.map { it.copy(bounds = ExactLpBounds()) }, costs, BigFraction.ZERO,
            SparseSourceLpPolicy(), Cancellation.Never).prepared).model
        LinearAuthoritySession(SourceLpBudget()).use { session ->
            val owner = assertNotNull(session.bind(packed, model.objective, Cancellation.Never, { model }, scope))
            val restricted = scope.copy(condition = SourceCondition(lower = mapOf(
                SourceCoordinate(SourceCoordinate.Kind.Real, 0) to BigFraction.ofLong(2))))
            assertSame(owner, session.bind(child, model.objective, Cancellation.Never, { model }, restricted))
            assertEquals(BigFraction.ofLong(2), owner.state.activeSide(0, false)?.side?.number?.value)

            assertSame(owner, session.bind(packed, model.objective, Cancellation.Never, { model }, scope))

            assertEquals(BigFraction.ZERO, owner.state.activeSide(0, false)?.side?.number?.value)
            assertEquals(listOf(0L, 1L, 2L, 3L), owner.state.assertions.map { it.witness })
            assertEquals(List(4) { 1 }, owner.state.assertions.map { it.depth })
            assertEquals(listOf(false, true, false, true), owner.state.assertions.map { it.upper })
            assertTrue(owner.state.model.column(owner.state.model.numVars - 1).bounds.lower?.strict == true)
            assertEquals(1L, session.preparations)
        }
    }

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
    fun `funding spent during preparation should preserve its admission before binding`() {
        val packed = assertNotNull(LinearAuthorityModel.prepare(listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        ), 1, Cancellation.Never))
        val funding = SourceLpFunding(1_000_000L, 1_000_000L)
        var interrupt = true
        val budget = SourceLpBudget(maxOperations = 1, funding = funding, onWork = { work ->
            if (interrupt && work.preparationWork > 0L) {
                interrupt = false
                funding.charge(funding.availableWork)
            }
        })
        val policy = SparseSourceLpPolicy()
        LinearAuthoritySession(budget).use { session ->
            funding.grant(100_000L)
            val stopped = session.advance(packed, listOf(BigFraction.ONE), BigFraction.ZERO, scope,
                Cancellation.Never, policy, observer) { guidance, _, _ -> guidance }
            val preparation = budget.measuredPreparationWork

            assertNull(stopped)
            assertTrue(funding.paused)
            funding.grant(100_000L)
            val resumed = session.advance(packed, listOf(BigFraction.ONE), BigFraction.ZERO, scope,
                Cancellation.Never, policy, observer) { guidance, _, _ -> guidance }

            assertNotNull(assertNotNull(resumed).primal)
            assertTrue(preparation > 0L)
            assertEquals(1, budget.operations)
            assertEquals(1L, session.preparations)
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
