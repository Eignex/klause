package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolveSessionTest {
    @Test
    fun `incremental and rebuilt complexity should agree across factor drops and domain narrowings`() {
        for (domains in listOf(
            emptyArray<IntDomain>(),
            arrayOf(IntDomain(-5, 5).excludeValue(0)),
            arrayOf(IntDomain(Long.MIN_VALUE, Long.MIN_VALUE), IntDomain(Long.MAX_VALUE, Long.MAX_VALUE)),
            arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
            arrayOf(IntDomain(0, Long.MAX_VALUE - 2)),
            Array(4) { IntDomain(0, 3_000_000_000_000_000_000L) },
        )) {
            val problem = Problem(
                2, domains.size, domains,
                listOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                ),
            ).bake()
            val session = PresolveSession(problem)
            assertEquals(
                PresolveShared.problemComplexity(problem.factors.size, problem.rootIntDomainsInPlace),
                session.complexity(),
            )
            val narrowed = Array(domains.size) { domains[it].withMaxAtMost(domains[it].min) }
            val rebuilt = problem.withPassDelta(
                PassDelta(droppedIndices = intArrayOf(0), domains = narrowed), BakeConfig.NONE,
            )

            assertTrue(session.apply(PresolveDelta(droppedIds = intArrayOf(0), domains = narrowed)))

            assertEquals(1L, session.complexity())
            assertEquals(
                PresolveShared.problemComplexity(rebuilt.factors.size, rebuilt.rootIntDomainsInPlace),
                session.complexity(),
            )
        }
    }

    private fun domains() = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10))

    private fun leq(coeffs: IntArray, vars: IntArray, bound: Int) = Linear(coeffs, vars, LinearOp.LE, bound)

    private fun base(vararg factors: Factor) = Problem(0, 3, domains(), factors.toList()).bake()

    private fun unitBase() = Problem(1, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, true))))).bake()

    private fun rootBool(problem: Problem, v: Int) = (problem.baked as PropagationResult.Implied).boolValueOrNull(v)

    @Test
    fun `incremental views and materialization should keep the exhausted phase token`() {
        val budget = PresolveBudget(1)
        val token = budget.orSpent(Cancellation.Never)
        val session = PresolveSession(unitBase(), cancellation = token)

        val input = session.passInput()
        val result = session.materialize()

        assertEquals(0L, budget.remaining())
        assertEquals(0L, budget.probeCalls)
        assertSame(token.workMeter(), input.cancellation.workMeter())
        assertSame(token.workMeter(), result.cancellation.workMeter())
        assertEquals(true, rootBool(result, 0))
        assertFalse(session.infeasible)
    }

    @Test
    fun `a pass input should keep a boolean fixed by a dropped factor`() {
        val session = PresolveSession(unitBase())
        assertTrue(session.apply(PresolveDelta(droppedIds = intArrayOf(0))))

        assertEquals(true, rootBool(session.passInput(), 0))
    }

    @Test
    fun `the materialized problem should keep a boolean fixed by a dropped factor`() {
        val session = PresolveSession(unitBase())
        assertTrue(session.apply(PresolveDelta(droppedIds = intArrayOf(0))))

        assertEquals(true, rootBool(session.materialize(), 0))
    }

    @Test
    fun `a conflicting narrowing should latch infeasibility`() {
        val f0 = leq(intArrayOf(1), intArrayOf(0), 5)
        val g = leq(intArrayOf(-1), intArrayOf(0), -8)

        val session = PresolveSession(base(f0))
        assertFalse(session.apply(PresolveDelta(addedFactors = listOf(g))))
        assertTrue(session.infeasible)
    }
}
