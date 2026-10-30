package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpDirectionalHistoryTest {
    @Test
    fun `both directions need two observations before scoring`() {
        for (priors in listOf(false, true)) {
            val history = LpDirectionalHistory({}, useClassPriors = priors)
            val variable = VarRef.IntVar(0)
            repeat(2) {
                history.beginNode(0)
                history.record(10.0)
                history.branch(variable, 2.25)
                history.commit(SearchDecision.IntAtMost(0, 2), 1)
                history.beginNode(1)
                history.record(11.0)
                assertTrue(history.score(variable, 2.25).isNaN())
                assertFalse(history.mayHaveScores)
                history.commit(SearchDecision.IntAtLeast(0, 3), 1)
                history.beginNode(1)
                history.record(13.0)
            }

            assertEquals(1.5, history.score(variable, 2.25), 1e-9)
            assertTrue(history.mayHaveScores)
        }
    }

    @Test
    fun `missing children cannot borrow a later descendant objective`() {
        val observations = ArrayList<String>()
        val history = LpDirectionalHistory(observations::add)
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.IntVar(0), 2.25)
        history.commit(SearchDecision.IntAtMost(0, 2), 1)
        history.beginNode(1)
        history.finishNode()

        history.beginNode(2)
        history.record(15.0)

        assertEquals(listOf("MISSING"), observations)
    }

    @Test
    fun `negative nonfinite and zero distance observations do not train history`() {
        for (value in listOf(9.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val observations = ArrayList<String>()
            val history = LpDirectionalHistory(observations::add)
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.IntVar(0), 2.25)
            history.commit(SearchDecision.IntAtMost(0, 2), 1)
            history.beginNode(1)

            history.record(value)

            assertEquals(listOf("UNUSABLE"), observations)
        }
        val history = LpDirectionalHistory({})
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.IntVar(0), 2.0)
        history.commit(SearchDecision.IntAtMost(0, 2), 1)
        history.beginNode(1)
        history.record(13.0)
        assertTrue(history.score(VarRef.IntVar(0), 2.0).isNaN())
    }

    @Test
    fun `aged directional observations lose reliability`() {
        val observations = ArrayList<String>()
        val history = LpDirectionalHistory(observations::add, maxAge = 2L)
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        repeat(2) {
            history.commit(SearchDecision.Bool(1), 1)
            history.beginNode(1)
            history.record(11.0)
            history.commit(SearchDecision.Bool(0), 1)
            history.beginNode(1)
            history.record(12.0)
        }
        assertTrue(history.score(VarRef.Bool(0), 0.5).isFinite())
        repeat(3) { history.beginNode(0) }

        assertTrue(history.score(VarRef.Bool(0), 0.5).isNaN())
        assertTrue(history.score(VarRef.Bool(1), 0.5).isNaN())
        assertEquals(2, observations.count { it == "EXPIRED" })
    }

    @Test
    fun `reset discards a pending child observation`() {
        val observations = ArrayList<String>()
        val history = LpDirectionalHistory(observations::add)
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        history.commit(SearchDecision.Bool(1), 1)

        history.clear()
        history.beginNode(1)
        history.record(11.0)

        assertEquals(listOf("RESET"), observations)
    }

    @Test
    fun `checked infeasibility stays separate from missing numeric observations`() {
        val observations = ArrayList<String>()
        val history = LpDirectionalHistory(observations::add)
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        history.commit(SearchDecision.Bool(1), 1)
        history.beginNode(1)

        history.infeasible()
        history.finishNode()

        assertEquals(listOf("INFEASIBLE"), observations)
    }

    @Test
    fun `all zero gains keep the fallback even with reliable history`() {
        val history = LpDirectionalHistory({})
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        repeat(2) {
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(10.0)
            }
        }

        assertTrue(history.score(VarRef.Bool(0), 0.5).isNaN())
        assertTrue(history.score(VarRef.Bool(1), 0.5).isNaN())
    }

    @Test
    fun `a class prior needs two observations in each direction across variables`() {
        val history = LpDirectionalHistory({})
        for (variable in 0..1) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.IntVar(variable), 2.25)
            history.commit(SearchDecision.IntAtMost(variable, 2), 1)
            history.beginNode(1)
            history.record(11.0)
            assertTrue(history.score(VarRef.IntVar(2), 2.25).isNaN())
            assertFalse(history.mayHaveScores)
            history.commit(SearchDecision.IntAtLeast(variable, 3), 1)
            history.beginNode(1)
            history.record(13.0)
        }

        assertEquals(1.5, history.score(VarRef.IntVar(2), 2.25), 1e-9)
        assertTrue(history.mayHaveScores)
        assertTrue(history.usesPrior(VarRef.IntVar(2)))
    }

    @Test
    fun `reliable variable observations take precedence over the class prior`() {
        val history = LpDirectionalHistory({})
        for (variable in 0..1) {
            repeat(2) {
                history.beginNode(0)
                history.record(10.0)
                history.branch(VarRef.IntVar(variable), 2.25)
                history.commit(SearchDecision.IntAtMost(variable, 2), 1)
                history.beginNode(1)
                history.record(if (variable == 0) 11.0 else 20.0)
                history.commit(SearchDecision.IntAtLeast(variable, 3), 1)
                history.beginNode(1)
                history.record(if (variable == 0) 13.0 else 40.0)
            }
        }

        assertEquals(1.5, history.score(VarRef.IntVar(0), 2.25), 1e-9)
        assertFalse(history.usesPrior(VarRef.IntVar(0)))
        assertTrue(history.score(VarRef.IntVar(2), 2.25) > 1.5)
    }

    @Test
    fun `class priors do not cross Boolean and integer coordinates`() {
        for (boolean in listOf(false, true)) {
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(10.0)
            history.branch(if (boolean) VarRef.Bool(0) else VarRef.IntVar(0), 0.5)
            repeat(2) {
                val decisions = if (boolean) listOf(SearchDecision.Bool(1), SearchDecision.Bool(0))
                    else listOf(SearchDecision.IntAtMost(0, 0), SearchDecision.IntAtLeast(0, 1))
                for (decision in decisions) {
                    history.commit(decision, 1)
                    history.beginNode(1)
                    history.record(11.0)
                }
            }
            val sameClass = if (boolean) VarRef.Bool(1) else VarRef.IntVar(1)
            val otherClass = if (boolean) VarRef.IntVar(1) else VarRef.Bool(1)

            assertTrue(history.score(sameClass, 0.5).isFinite())
            assertTrue(history.score(otherClass, 0.5).isNaN())
        }
    }

    @Test
    fun `aged class observations cannot initialize an unobserved variable`() {
        val history = LpDirectionalHistory({}, maxAge = 2L)
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        repeat(2) {
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(11.0)
            }
        }
        assertTrue(history.score(VarRef.Bool(1), 0.5).isFinite())

        repeat(3) { history.beginNode(0) }

        assertTrue(history.score(VarRef.Bool(1), 0.5).isNaN())
        assertFalse(history.mayHaveScores)
    }

    @Test
    fun `reset discards the class prior`() {
        val history = LpDirectionalHistory({})
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        repeat(2) {
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(11.0)
            }
        }
        assertTrue(history.score(VarRef.Bool(1), 0.5).isFinite())

        history.clear()

        assertTrue(history.score(VarRef.Bool(1), 0.5).isNaN())
        assertFalse(history.mayHaveScores)
    }
}
