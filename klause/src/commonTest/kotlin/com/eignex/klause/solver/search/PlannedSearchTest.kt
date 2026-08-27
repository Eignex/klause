package com.eignex.klause.solver.search

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.FactorOwner
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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
}
