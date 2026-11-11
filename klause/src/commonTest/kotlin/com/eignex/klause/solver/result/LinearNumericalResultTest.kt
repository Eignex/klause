package com.eignex.klause.solver.result

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.optimization.LinearNumericalClaim
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearNumericalResultTest {
    @Test
    fun `numerical completion should apply its declared tolerance on both sides of the bound`() {
        for (sense in listOf(ObjectiveSense.MINIMIZE, ObjectiveSense.MAXIMIZE)) {
            val scope = SourceScope(SourceRevision("numeric-result-tolerance", "linear"),
                objective = SourceObjective("x", sense))
            val source = SourceProofContext(scope, emptySet(), emptyMap(), emptyMap())
            val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
            val claim = assertNotNull(LinearNumericalClaim.create(source, 2.0, false, budget, Cancellation.Never))
            for (sign in listOf(-1.0, 1.0)) {
                val near = assertNotNull(BigFraction.ofDouble(2.0 + sign * 0.0000001))
                val far = assertNotNull(BigFraction.ofDouble(2.0 + sign * 0.00001))

                assertTrue(LinearNumericalResult(claim, near, true).optimal)
                assertFalse(LinearNumericalResult(claim, far, true).optimal)
            }
        }
    }

    @Test
    fun `a numerical bound contradicted by an incumbent should withhold optimum status`() {
        for (sense in listOf(ObjectiveSense.MINIMIZE, ObjectiveSense.MAXIMIZE)) {
            val scope = SourceScope(SourceRevision("numeric-result-contradiction", "linear"),
                objective = SourceObjective("x", sense))
            val source = SourceProofContext(scope, emptySet(), emptyMap(), emptyMap())
            val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
            val claim = assertNotNull(LinearNumericalClaim.create(source, 2.0, false, budget, Cancellation.Never))
            val value = BigFraction.ofLong(if (sense == ObjectiveSense.MINIMIZE) 1L else 3L)

            val result = LinearNumericalResult(claim, value, true)

            assertFalse(result.optimal)
            assertTrue(result.contradicts(value))
        }
    }

    @Test
    fun `numerical result bounds should retain source direction and relative gap`() {
        for (sense in listOf(ObjectiveSense.MINIMIZE, ObjectiveSense.MAXIMIZE)) {
            val scope = SourceScope(SourceRevision("numeric-result-gap", "linear"),
                objective = SourceObjective("x", sense))
            val source = SourceProofContext(scope, emptySet(), emptyMap(), emptyMap())
            val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
            val bound = if (sense == ObjectiveSense.MINIMIZE) 2.0 else 4.0
            val claim = assertNotNull(LinearNumericalClaim.create(source, bound, false, budget, Cancellation.Never))

            val result = LinearNumericalResult(claim, BigFraction.ofLong(3), true)

            assertEquals(scope, result.scope)
            assertEquals(bound, result.bound)
            assertEquals(1.0 / 3.0, assertNotNull(result.relativeGap), 0.000000000001)
            assertFalse(result.optimal)
        }
    }

    @Test
    fun `numerical gaps should avoid overflowing finite objective differences`() {
        for (sense in listOf(ObjectiveSense.MINIMIZE, ObjectiveSense.MAXIMIZE)) {
            val scope = SourceScope(SourceRevision("numeric-result-wide-gap", "linear"),
                objective = SourceObjective("x", sense))
            val source = SourceProofContext(scope, emptySet(), emptyMap(), emptyMap())
            val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
            val sign = if (sense == ObjectiveSense.MINIMIZE) 1.0 else -1.0
            val claim = assertNotNull(LinearNumericalClaim.create(source, -sign * Double.MAX_VALUE,
                false, budget, Cancellation.Never))

            val result = LinearNumericalResult(claim, assertNotNull(BigFraction.ofDouble(sign * Double.MAX_VALUE)),
                true)

            assertEquals(2.0, result.relativeGap)
            assertFalse(result.optimal)
        }
    }

    @Test
    fun `partial numerical results should retain bounds without completion authority`() {
        val scope = SourceScope(SourceRevision("numeric-result-partial", "linear"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, emptySet(), emptyMap(), emptyMap())
        val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
        val claim = assertNotNull(LinearNumericalClaim.create(source, 2.0, false, budget, Cancellation.Never))
        val completed = LinearNumericalResult(claim, BigFraction.ofLong(2), true)

        val partial = assertNotNull(completed.partial(BigFraction.ofLong(2)))

        assertTrue(completed.optimal)
        assertFalse(partial.optimal)
        assertFalse(partial.infeasible)
        assertEquals(2.0, partial.bound)
        assertEquals(0.0, partial.relativeGap)
    }

    @Test
    fun `numerical infeasibility should not create exact source evidence`() {
        val scope = SourceScope(SourceRevision("numeric-result-conflict", "linear"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(scope, emptySet(), emptyMap(), emptyMap())
        val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
        val claim = assertNotNull(LinearNumericalClaim.create(source, null, true, budget, Cancellation.Never))
        val numerical = LinearNumericalResult(claim, null, true)

        val result = LinearOptimizationResult(scope, null, null, null, null, TerminationReason.SearchExhausted,
            SolveStats.EMPTY, numerical = numerical)

        assertTrue(assertNotNull(result.numerical).infeasible)
        assertFalse(numerical.optimal)
        assertNull(numerical.relativeGap)
        assertNull(numerical.partial(null))
        assertNull(result.terminal)
        assertNull(result.bound)
        assertTrue(result.evidence.derivations.isEmpty())
    }
}
