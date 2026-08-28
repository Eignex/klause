package com.eignex.klause.solver.search

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ProblemSpec
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.FactorOwner
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlannedSearchTest {

    @Test
    fun `planned hybrid search builds CP and theory components without theory domains`() {
        val openUpper = Bits(3).also { it.set(1) }
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(3, 0, 3), null, openUpper),
            factors = arrayOf(
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 4),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
            ),
        )
        val plan = model.componentPlan()

        val planned = plan.search(model, mapOf(0 to IntDomain(0, 3), 2 to IntDomain(0, 3)))

        assertNotNull(planned.cp)
        assertNotNull(planned.theory)
        assertIs<ComponentResult.Consistent>(planned.session.initialize())
        assertEquals(0L, planned.session.intLowerBound(0))
        assertEquals(3L, planned.session.intUpperBound(0))
        assertIs<ComponentCheck.Feasible>(planned.session.check())
    }

    @Test
    fun `planned pure real search uses the shared Boolean engine`() {
        val model = ProblemSpec(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    2.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(3.0),
        )

        val planned = model.componentPlan().search(model, emptyMap())

        assertIs<ComponentResult.Consistent>(planned.session.initialize())
        val result = assertIs<BooleanSearchResult.Satisfied>(planned.session.solveBoolean(1))

        assertNotNull(result.model.valueOf<Any>(SearchRealValue(0)))
    }

    @Test
    fun `shared engine branches a finite global constraint and assembles its model`() {
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(3, 3), null, null),
            factors = arrayOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val planned = model.componentPlan().search(model, mapOf(0 to IntDomain(0, 3), 1 to IntDomain(0, 3)))

        assertIs<ComponentResult.Consistent>(planned.session.initialize())
        val result = assertIs<SearchResult.Satisfied>(planned.session.solve(0))

        val sample = checkNotNull(result.model.valueOf<Sample>(checkNotNull(planned.cp)))
        assertEquals(0L, sample.ints[0])
        assertEquals(0L, result.model.valueOf<Long>(SearchIntValue(0)))
    }

    @Test
    fun `shared engine combines CP branching with a symbolic theory`() {
        val openUpper = Bits(3).also { it.set(1) }
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(3, 0, 3), null, openUpper),
            factors = arrayOf(
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 4),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
            ),
        )
        val planned = model.componentPlan().search(model, mapOf(0 to IntDomain(0, 3), 2 to IntDomain(0, 3)))

        assertIs<ComponentResult.Consistent>(planned.session.initialize())

        val result = assertIs<SearchResult.Satisfied>(planned.session.solve(0))

        assertNotNull(result.model.valueOf<Long>(SearchIntValue(1)))
    }

    @Test
    fun `hybrid theory rows receive CP-owned values through the shared trail`() {
        val openUpper = Bits(3).also { it.set(2) }
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(1, 1, 0), null, openUpper),
            factors = arrayOf(
                AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 2), intArrayOf(0, 2), LinearOp.EQ, 1),
            ),
        )
        val plan = model.componentPlan()
        val planned = plan.search(model, mapOf(0 to IntDomain(0, 1), 1 to IntDomain(0, 1)))

        assertEquals(FactorOwner.CP, plan.factorOwner(0))
        assertEquals(FactorOwner.THEORY, plan.factorOwner(1))
        assertEquals(FactorOwner.THEORY, plan.factorOwner(2))

        assertIs<ComponentResult.Consistent>(planned.session.initialize())

        assertIs<SearchResult.Exhausted>(planned.session.solve(0))
    }

    @Test
    fun `hybrid QF LIRA rows receive CP-owned values through the shared trail`() {
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 1), longArrayOf(1, 1), null, null),
            factors = arrayOf(
                AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val planned = model.componentPlan().search(
            model,
            mapOf(0 to IntDomain(0, 1), 1 to IntDomain(1, 1)),
        )

        assertIs<ComponentResult.Consistent>(planned.session.initialize())

        assertIs<SearchResult.Exhausted>(planned.session.solve(0))
    }

    @Test
    fun `hybrid root conflict is reported through the shared component session`() {
        val openUpper = Bits(3).also { it.set(1) }
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(3, 0, 3), null, openUpper),
            factors = arrayOf(
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 4),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, -1),
            ),
        )
        val planned = model.componentPlan().search(model, mapOf(0 to IntDomain(0, 3), 2 to IntDomain(0, 3)))

        assertIs<ComponentResult.Conflict>(planned.session.initialize())
    }

    @Test
    fun `root difference equality pins a finite peer and its model value before branching`() {
        val model = differenceHybridFixture()
        val planned = model.componentPlan().search(model, differenceHybridDomains())

        assertIs<ComponentResult.Consistent>(planned.session.initialize())

        assertEquals(7L, planned.session.intLowerBound(HYBRID_X))
        assertEquals(7L, planned.session.intUpperBound(HYBRID_X))
        assertEquals(7L, planned.session.model().valueOf<Long>(SearchIntValue(HYBRID_X)))
        val branch = assertNotNull(planned.session.branchAlternatives())
        assertTrue(
            branch.all { decision ->
                when (decision) {
                    is SearchDecision.IntAtLeast -> decision.variable == HYBRID_Z
                    is SearchDecision.IntAtMost -> decision.variable == HYBRID_Z
                    is SearchDecision.IntEqual -> decision.variable == HYBRID_Z
                    is SearchDecision.Bool, is SearchDecision.Theory -> false
                }
            },
        )
    }

    @Test
    fun `unconditional difference root bounds expose a hybrid root conflict`() {
        val model = differenceHybridFixture(fixZAtSeven = true)
        val planned = model.componentPlan().search(model, differenceHybridDomains())

        assertIs<ComponentResult.Conflict>(planned.session.initialize())
    }

    private fun differenceHybridDomains(): Map<Int, IntDomain> =
        mapOf(HYBRID_X to IntDomain(0, 7), HYBRID_Z to IntDomain(0, 7))

    private fun differenceHybridFixture(fixZAtSeven: Boolean = false): ProblemSpec {
        val openLower = Bits(3).also { it.set(HYBRID_Y) }
        val openUpper = Bits(3).also { it.set(HYBRID_Y) }
        val factors = ArrayList<Factor>().apply {
            add(Clause(intArrayOf(Lit.make(0, true))))
            add(Linear(intArrayOf(1), intArrayOf(HYBRID_X), LinearOp.GE, 0))
            add(Linear(intArrayOf(1), intArrayOf(HYBRID_X), LinearOp.LE, 7))
            add(Linear(intArrayOf(1), intArrayOf(HYBRID_Z), LinearOp.GE, 0))
            add(Linear(intArrayOf(1), intArrayOf(HYBRID_Z), LinearOp.LE, 7))
            add(AllDifferent(intArrayOf(HYBRID_X, HYBRID_Z), domainMin = 0, domainSize = 8))
            add(Linear(intArrayOf(1), intArrayOf(HYBRID_Y), LinearOp.EQ, 0))
            add(Linear(intArrayOf(1, -1), intArrayOf(HYBRID_X, HYBRID_Y), LinearOp.EQ, 7))
            if (fixZAtSeven) add(Linear(intArrayOf(1), intArrayOf(HYBRID_Z), LinearOp.EQ, 7))
        }
        return ProblemSpec(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0, 0, 0),
                longArrayOf(7, 7, 0),
                openLower,
                openUpper,
            ),
            factors = factors.toTypedArray(),
        )
    }

    private companion object {
        const val HYBRID_X = 0
        const val HYBRID_Z = 1
        const val HYBRID_Y = 2
    }
}
