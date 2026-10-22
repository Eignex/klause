package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolveSessionTest {
    @Test
    fun `root views should add no propagation or construction work after a drop`() {
        for (size in listOf(1, 16, 64)) {
            val budget = PresolveBudget(Long.MAX_VALUE)
            val base = Problem(
                0, size, Array(size) { IntDomain(0, 10) },
                List(size) { Linear(intArrayOf(1), intArrayOf(it), LinearOp.LE, 5) },
            ).bake(budget.orSpent(Cancellation.Never))
            val session = PresolveSession(base)
            val construction = budget.rootConstructionEntries
            val propagation = budget.rootPropagationWork

            val first = session.passInput()
            assertSame(first, session.passInput())
            assertTrue(session.applyDelta(PassDelta(droppedIndices = intArrayOf(0))))
            val next = session.passInput()
            val result = session.materialize()

            assertEquals(size - 1, next.numFactors)
            assertEquals(List(size) { IntDomain(0, 5) }, next.rootIntDomains().toList())
            assertEquals(next.rootIntDomains().toList(), result.rootIntDomains().toList())
            assertEquals(construction, budget.rootConstructionEntries)
            assertEquals(propagation, budget.rootPropagationWork)
        }
    }

    @Test
    fun `a domain delta should refresh captured Boolean deductions and retain holes`() {
        val source = Problem(
            1, 1, arrayOf(IntDomain(0, 10)),
            listOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 5)),
        ).bake()
        val session = PresolveSession(source)
        assertEquals(null, rootBool(session.passInput(), 0))
        val target = IntDomain(1, 5).excludeValue(3)

        assertTrue(session.applyDelta(PassDelta(domains = arrayOf(target))))
        val input = session.passInput()

        assertEquals(true, rootBool(input, 0))
        assertEquals(target, input.rootIntDomain(0))
        assertEquals(target, session.materialize().rootIntDomain(0))
    }

    @Test
    fun `root views should retain an established conflict after its factors drop`() {
        for (config in listOf(BakeConfig.NONE, BakeConfig(probeFailedLiterals = true))) {
            val source = Problem(
                1, 1, arrayOf(IntDomain(0, 10)),
                listOf(Clause(intArrayOf(Lit.make(0, true))), Clause(intArrayOf(Lit.make(0, false)))),
            ).bake()
            val session = PresolveSession(source, config)
            assertTrue(session.infeasible)
            session.apply(PresolveDelta(droppedIds = intArrayOf(0, 1)))

            assertIs<PropagationResult.Unsat>(session.passInput().baked)
            assertIs<PropagationResult.Unsat>(session.materialize().baked)
            assertEquals(IntDomain(0, 10), session.passInput().rootIntDomain(0))
        }
    }

    @Test
    fun `cancelled root views should retain pins without spending more work`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        var cancelled = false
        val token = budget.orSpent(Cancellation { cancelled })
        val session = PresolveSession(unitBase(), cancellation = token)
        cancelled = true
        val spent = budget.spent()

        val input = session.passInput()
        val result = session.materialize()

        assertEquals(true, rootBool(input, 0))
        assertEquals(true, rootBool(result, 0))
        assertEquals(spent, budget.spent())
        assertFalse(session.infeasible)
        assertSame(token, input.cancellation)
        assertSame(token, result.cancellation)
    }

    @Test
    fun `root views should reuse captured projection selection`() {
        var selections = 0
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider {
                selections++
                PropagationProjection(
                    PropagationCapability.INERT,
                    AssignmentCheckCapability.SOURCE_CERTIFICATION,
                    "Original finite factors are checked at complete points.",
                ) { NoPropagator }
            },
        )
        val source = Problem(1, 0, emptyArray(), listOf(Clause(intArrayOf(0))))
        val base = source.bake(providers.prepare(source))
        val session = PresolveSession(base)
        val captured = selections

        for (view in listOf(session.passInput(), session.materialize())) {
            assertSame(providers, view.projectionProviders)
            assertEquals(PropagationCapability.INERT, view.factorProjections!!.capabilities(0).propagation)
            assertSame(base.settings, view.settings)
            assertSame(base.semantics, view.semantics)
        }
        assertEquals(captured, selections)
    }

    @Test
    fun `probing enabled materialization should still derive failed literals`() {
        val base = Problem(
            2, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ),
        ).bake()
        val session = PresolveSession(base, BakeConfig(probeFailedLiterals = true))

        assertEquals(null, rootBool(session.passInput(), 0))
        assertEquals(false, rootBool(session.materialize(), 0))
    }

    @Test
    fun `shared factors should keep distinct stable ids across additions and drops`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val session = PresolveSession(Problem(2, 0, emptyArray(), listOf(clause, clause)).bake())
        val originalIds = session.passFactorIds()
        val mark = session.changeMark()
        assertTrue(session.applyDelta(PassDelta(droppedIndices = intArrayOf(1), addedFactors = listOf(clause))))
        session.passInput()
        assertTrue(session.applyDelta(PassDelta(droppedIndices = intArrayOf(1))))

        assertEquals(listOf(0, 1), originalIds.toList())
        assertEquals(listOf(0), session.passFactorIds().toList())
        assertEquals(listOf(1, 2), session.droppedIdsSince(mark).toList())
        assertSame(clause, session.factorAt(0))
        assertEquals(null, session.factorAt(1))
        assertEquals(null, session.factorAt(2))
    }


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
