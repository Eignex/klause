package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpDirectionalHistoryTest {
    @Test
    fun `balanced ranking favors gains on both sides over a large asymmetric gain`() {
        val history = LpDirectionalHistory({})
        for ((id, down, up) in listOf(Triple(0, 0.25, 36.0), Triple(1, 4.0, 4.0))) repeat(2) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.IntVar(id), 0.5)
            for (upper in listOf(false, true)) {
                history.commit(if (upper) SearchDecision.IntAtLeast(id, 1) else SearchDecision.IntAtMost(id, 0), 1)
                history.beginNode(1)
                history.record(10.0 + if (upper) up else down)
            }
        }

        assertTrue(history.score(VarRef.IntVar(0), 0.5) > history.score(VarRef.IntVar(1), 0.5))
        assertTrue(history.balancedScore(VarRef.IntVar(1), 0.5) > history.balancedScore(VarRef.IntVar(0), 0.5))
    }

    @Test
    fun `balanced scores preserve objective units across large and small scales`() {
        for (scale in listOf(1.0, 8.0, 1e150, 1e-150)) {
            val history = LpDirectionalHistory({})
            repeat(2) {
                history.beginNode(0)
                history.record(10.0 * scale)
                history.branch(VarRef.IntVar(0), 0.5)
                for (up in listOf(false, true)) {
                    history.commit(if (up) SearchDecision.IntAtLeast(0, 1) else SearchDecision.IntAtMost(0, 0), 1)
                    history.beginNode(1)
                    history.record((if (up) 18.0 else 12.0) * scale)
                }
            }

            assertEquals(4.0 * scale, history.balancedScore(VarRef.IntVar(0), 0.5), scale * 1e-12)
        }
    }

    @Test
    fun `a zero predicted gain on either side keeps the balanced fallback`() {
        for ((down, up) in listOf(0.0 to 1.0, 1.0 to 0.0, 0.0 to 0.0)) {
            val history = LpDirectionalHistory({})
            repeat(2) {
                history.beginNode(0)
                history.record(10.0)
                history.branch(VarRef.IntVar(0), 0.5)
                for (upper in listOf(false, true)) {
                    history.commit(if (upper) SearchDecision.IntAtLeast(0, 1) else SearchDecision.IntAtMost(0, 0), 1)
                    history.beginNode(1)
                    history.record(10.0 + if (upper) up else down)
                }
            }

            assertTrue(history.balancedScore(VarRef.IntVar(0), 0.5).isNaN())
        }
    }

    @Test
    fun `balanced ranking requires recent two sided fractional observations`() {
        val history = LpDirectionalHistory({}, maxAge = 2)
        val variable = VarRef.IntVar(0)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 0.5)
        repeat(2) { pass ->
            history.commit(SearchDecision.IntAtMost(0, 0), 1)
            history.beginNode(1)
            history.record(11.0)
            history.commit(SearchDecision.IntAtLeast(0, 1), 1)
            history.beginNode(1)
            history.record(12.0)
            if (pass == 0) assertTrue(history.balancedScore(variable, 0.5).isNaN())
        }
        assertTrue(history.balancedScore(variable, 0.5).isFinite())
        for (point in listOf(Double.NaN, Double.POSITIVE_INFINITY, 1.0)) {
            assertTrue(history.balancedScore(variable, point).isNaN())
        }
        repeat(3) { history.beginNode(0) }

        assertTrue(history.balancedScore(variable, 0.5).isNaN())
    }

    @Test
    fun `unusable observations do not satisfy two sided sample availability`() {
        for (value in listOf(9.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val history = LpDirectionalHistory({})
            for (up in listOf(false, false, true)) {
                history.beginNode(0)
                history.record(10.0)
                history.branch(VarRef.IntVar(0), 2.25)
                history.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                history.beginNode(1)
                history.record(if (up) 13.0 else 11.0)
            }
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.IntVar(0), 2.25)
            history.commit(SearchDecision.IntAtLeast(0, 3), 1)
            history.beginNode(1)

            history.record(value)

            assertFalse(history.hasTwoSidedSamples)
        }
    }

    @Test
    fun `two sided availability does not establish variable reliability`() {
        val history = LpDirectionalHistory({})
        for (up in listOf(false, true)) repeat(2) {
            val variable = if (up) 1 else 0
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.IntVar(variable), 2.25)
            history.commit(if (up) SearchDecision.IntAtLeast(variable, 3)
                else SearchDecision.IntAtMost(variable, 2), 1)
            history.beginNode(1)
            history.record(if (up) 13.0 else 11.0)
        }

        assertTrue(history.hasTwoSidedSamples)
        assertTrue(history.score(VarRef.IntVar(0), 2.25).isNaN())
        assertTrue(history.score(VarRef.IntVar(1), 2.25).isNaN())
    }

    @Test
    fun `clearing history removes two sided availability`() {
        val history = LpDirectionalHistory({})
        repeat(2) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.IntVar(0), 2.25)
            for (up in listOf(false, true)) {
                history.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                history.beginNode(1)
                history.record(if (up) 13.0 else 11.0)
            }
        }
        assertTrue(history.hasTwoSidedSamples)

        history.clear()

        assertFalse(history.hasTwoSidedSamples)
    }

    @Test
    fun `both directions need two observations before scoring`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.IntVar(0)
        repeat(2) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 2.25)
            history.commit(SearchDecision.IntAtMost(0, 2), 1)
            history.beginNode(1)
            history.record(11.0)
            assertTrue(history.score(variable, 2.25).isNaN())
            history.commit(SearchDecision.IntAtLeast(0, 3), 1)
            history.beginNode(1)
            history.record(13.0)
        }

        assertEquals(1.5, history.score(variable, 2.25), 1e-9)
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
    }
}
