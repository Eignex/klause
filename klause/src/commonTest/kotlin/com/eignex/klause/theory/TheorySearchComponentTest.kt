package com.eignex.klause.theory

import com.eignex.klause.solver.IntBounds
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TheorySearchComponentTest {

    @Test
    fun `theory check reads shared integer bounds without a CP domain`() {
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = emptyArray(),
        )
        val theory = object : Theory<Long> {
            override val model: ProblemSpec = model

            override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<Long> =
                TheoryCheck.Sat(checkNotNull(context.intUpperBound(0)))
        }
        val publisher = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult =
                context.publish(SearchDecision.IntAtMost(0, 4))
        }
        val component = TheorySearchComponent(theory)
        val session = SearchSession(listOf(publisher, component))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentCheck.Feasible>(session.check())

        assertEquals(4L, session.model().valueOf<Long>(component))
    }

    @Test
    fun `adapter refutes a complete shared Boolean assignment during propagation`() {
        val model = ProblemSpec(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = emptyArray(),
        )
        val theory = object : Theory<Unit> {
            override val model: ProblemSpec = model

            override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<Unit> =
                if (bools[0]) TheoryCheck.Infeasible(SearchExplanation(intArrayOf(1))) else TheoryCheck.Sat(Unit)
        }
        val session = SearchSession(listOf(TheorySearchComponent(theory)))

        assertIs<ComponentResult.Consistent>(session.initialize())
        val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(0)))
        assertEquals(true, conflict.explanation?.literals?.contentEquals(intArrayOf(1)))
        session.popTo(0)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
    }
}
