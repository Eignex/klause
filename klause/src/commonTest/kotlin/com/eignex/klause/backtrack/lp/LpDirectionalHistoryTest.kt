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
    fun `partial history needs two class observations in each direction`() {
        val history = LpDirectionalHistory({})
        for (variable in 0..1) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.IntVar(variable), 2.25)
            history.commit(SearchDecision.IntAtMost(variable, 2), 1)
            history.beginNode(1)
            history.record(11.0)
            assertTrue(history.score(VarRef.IntVar(0), 2.25).isNaN())
            assertFalse(history.mayHaveScores)
            history.commit(SearchDecision.IntAtLeast(variable, 3), 1)
            history.beginNode(1)
            history.record(13.0)
        }

        assertEquals(1.5, history.score(VarRef.IntVar(1), 2.25), 1e-9)
        assertTrue(history.mayHaveScores)
        assertTrue(history.usesPrior(VarRef.IntVar(1)))
    }

    @Test
    fun `a baseline class reference leaves an unobserved candidate ineligible`() {
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

        assertEquals(7.0 / 6.0, history.referenceScore(VarRef.Bool(1), 0.5), 1e-9)
        assertTrue(history.score(VarRef.Bool(1), 0.5).isNaN())
    }

    @Test
    fun `each direction blends local observations until its reliability threshold`() {
        for (samples in listOf(1, 2)) {
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.IntVar(0), 2.25)
            repeat(2) {
                history.commit(SearchDecision.IntAtMost(0, 2), 1)
                history.beginNode(1)
                history.record(18.0)
                history.commit(SearchDecision.IntAtLeast(0, 3), 1)
                history.beginNode(1)
                history.record(34.0)
            }
            repeat(samples) {
                history.beginNode(0)
                history.record(10.0)
                history.branch(VarRef.IntVar(1), 2.25)
                history.commit(SearchDecision.IntAtMost(1, 2), 1)
                history.beginNode(1)
                history.record(11.0)
            }

            val score = history.score(VarRef.IntVar(1), 2.25)

            assertEquals(if (samples == 1) 22.0 / 3.0 else 5.0, score, 1e-9)
        }
    }

    @Test
    fun `reliable variable observations take precedence over the class prior`() {
        for (gain in listOf(0.0, 1.0)) {
            val history = LpDirectionalHistory({})
            for (variable in 0..1) {
                repeat(2) {
                    history.beginNode(0)
                    history.record(10.0)
                    history.branch(VarRef.IntVar(variable), 2.25)
                    history.commit(SearchDecision.IntAtMost(variable, 2), 1)
                    history.beginNode(1)
                    history.record(if (variable == 0) 10.0 + gain else 20.0)
                    history.commit(SearchDecision.IntAtLeast(variable, 3), 1)
                    history.beginNode(1)
                    history.record(if (variable == 0) 10.0 + 3.0 * gain else 40.0)
                }
            }

            assertEquals(1.5 * gain, history.referenceScore(VarRef.IntVar(0), 2.25), 1e-9)
            if (gain == 0.0) assertTrue(history.score(VarRef.IntVar(0), 2.25).isNaN())
            else assertEquals(1.5, history.score(VarRef.IntVar(0), 2.25), 1e-9)
            assertFalse(history.usesPrior(VarRef.IntVar(0)))
        }
    }

    @Test
    fun `class priors do not cross Boolean and integer coordinates`() {
        for (boolean in listOf(false, true)) {
            val history = LpDirectionalHistory({})
            for (coordinate in listOf(boolean, !boolean)) {
                val variables = if (coordinate == boolean) 0..1 else 0..0
                for (variable in variables) {
                    history.beginNode(0)
                    history.record(10.0)
                    history.branch(if (coordinate) VarRef.Bool(variable) else VarRef.IntVar(variable), 0.5)
                    val decisions = if (coordinate) {
                        listOf(SearchDecision.Bool(variable * 2 + 1), SearchDecision.Bool(variable * 2))
                    } else {
                        listOf(SearchDecision.IntAtMost(variable, 0), SearchDecision.IntAtLeast(variable, 1))
                    }
                    for (decision in decisions) {
                        history.commit(decision, 1)
                        history.beginNode(1)
                        history.record(11.0)
                    }
                }
            }
            val sameClass = if (boolean) VarRef.Bool(1) else VarRef.IntVar(1)
            val otherClass = if (boolean) VarRef.IntVar(0) else VarRef.Bool(0)

            assertTrue(history.score(sameClass, 0.5).isFinite())
            assertTrue(history.score(otherClass, 0.5).isNaN())
            assertTrue(history.referenceScore(otherClass, 0.5).isNaN())
        }
    }

    @Test
    fun `aged class observations cannot initialize partial history`() {
        val history = LpDirectionalHistory({}, maxAge = 8L)
        for (variable in 0..1) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(variable), 0.5)
            for (literal in listOf(variable * 2, variable * 2 + 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(11.0)
            }
        }
        assertTrue(history.score(VarRef.Bool(1), 0.5).isFinite())

        repeat(9) { history.beginNode(0) }

        assertTrue(history.referenceScore(VarRef.Bool(2), 0.5).isNaN())
        assertTrue(history.score(VarRef.Bool(1), 0.5).isNaN())
        assertFalse(history.mayHaveScores)
    }

    @Test
    fun `expired local observations cannot be replaced by a fresh class prior`() {
        val history = LpDirectionalHistory({}, maxAge = 4L)
        for (variable in 0..2) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(variable), 0.5)
            for (literal in listOf(variable * 2, variable * 2 + 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(11.0)
            }
        }
        assertTrue(history.mayHaveScores)

        assertTrue(history.score(VarRef.Bool(0), 0.5).isNaN())
    }

    @Test
    fun `reset discards the class prior`() {
        val history = LpDirectionalHistory({})
        for (variable in 0..1) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(variable), 0.5)
            for (literal in listOf(variable * 2, variable * 2 + 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(11.0)
            }
        }
        assertTrue(history.score(VarRef.Bool(1), 0.5).isFinite())

        history.clear()

        assertTrue(history.referenceScore(VarRef.Bool(2), 0.5).isNaN())
        assertTrue(history.score(VarRef.Bool(1), 0.5).isNaN())
        assertFalse(history.mayHaveScores)
    }
}
