package com.eignex.klause.theory

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.SearchSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TheorySearchComponentTest {

    @Test
    fun `theory check should read shared integer bounds without a CP domain`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = emptyArray(),
        )
        val theory = object : Theory<Long> {
            override val model: Problem = model

            override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<Long> =
                TheoryCheck.Sat(checkNotNull(context.intUpperBound(0)))
        }
        val publisher = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult =
                context.publish(SearchDecision.IntAtMost(0, 4))
        }
        val component = TheorySearchComponent(theory, SearchModelKey(Long::class))
        val session = SearchSession(listOf(publisher, component))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentCheck.Feasible>(session.check())

        assertEquals(4L, session.model().valueOf(component.assignmentKey))
    }

    @Test
    fun `adapter should refute a complete shared Boolean assignment during propagation`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = emptyArray(),
        )
        val theory = object : Theory<Unit> {
            override val model: Problem = model

            override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<Unit> =
                if (bools[0]) TheoryCheck.Infeasible(SearchExplanation(intArrayOf(1))) else TheoryCheck.Sat(Unit)
        }
        val session = SearchSession(listOf(TheorySearchComponent(theory, SearchModelKey(Unit::class))))

        assertIs<ComponentResult.Consistent>(session.initialize())
        val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(0)))
        assertEquals(true, conflict.explanation?.literals?.contentEquals(intArrayOf(1)))
        session.popTo(0)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
    }

    @Test
    fun `undecided batch checks should retry without publishing a witness`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = emptyArray(),
        )
        var available = false
        val theory = object : Theory<Long> {
            override val model: Problem = model

            override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<Long> =
                if (available) TheoryCheck.Sat(7L) else TheoryCheck.Cancelled
        }
        val component = TheorySearchComponent(theory, SearchModelKey(Long::class))
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Indeterminate>(session.initialize())
        assertNull(session.model().valueOf(component.assignmentKey))
        available = true
        assertIs<ComponentCheck.Feasible>(session.check())
        assertEquals(7L, session.model().valueOf(component.assignmentKey))
    }

    @Test
    fun `semantic bound changes should invalidate cached batch assignments`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = emptyArray(),
        )
        val theory = object : Theory<Long> {
            override val model: Problem = model

            override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<Long> =
                TheoryCheck.Sat(context.intUpperBound(0) ?: 10L)
        }
        val component = TheorySearchComponent(theory, SearchModelKey(Long::class))
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertEquals(10L, session.model().valueOf(component.assignmentKey))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtMost(0, 4)))

        assertEquals(4L, session.model().valueOf(component.assignmentKey))
    }

    @Test
    fun `retraction should clear batch assignments before checking the restored conditions`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = emptyArray(),
        )
        val theory = object : Theory<Long> {
            override val model: Problem = model

            override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<Long> =
                TheoryCheck.Sat(context.intUpperBound(0) ?: 10L)
        }
        val component = TheorySearchComponent(theory, SearchModelKey(Long::class))
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtMost(0, 4)))

        session.popTo(0)

        assertNull(session.model().valueOf(component.assignmentKey))
        assertIs<ComponentCheck.Feasible>(session.check())
        assertEquals(10L, session.model().valueOf(component.assignmentKey))
    }
}
