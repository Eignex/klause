package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpDirectionalHistoryTest {
    @Test
    fun `overlapping child gain support keeps ordinary order`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 0.5)
        for (downGain in listOf(1.0, 2.0)) {
            for ((literal, gain) in listOf(1 to downGain, 0 to 1.5)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(10.0 + gain)
            }
        }
        history.beginNode(0)
        history.record(10.0)

        assertNull(history.preferGainDirection(variable, 0.5))
    }

    @Test
    fun `child support must agree on reaching the objective gap`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 0.5)
        for ((downGain, upGain) in listOf(1.0 to 3.0, 2.0 to 4.0)) {
            for ((literal, gain) in listOf(1 to downGain, 0 to upGain)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(10.0 + gain)
            }
        }
        history.beginNode(0)
        history.record(10.0)

        for ((cutoff, preferred) in listOf(13.0 to true, 13.5 to null, 14.1 to false)) {
            assertEquals(preferred, history.preferGainDirection(variable, 0.5, cutoff))
        }
    }

    @Test
    fun `upper estimate covers the larger retained gain in each direction`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 0.5)
        for ((downGain, upGain) in listOf(1.0 to 3.0, 2.0 to 4.0, 1.5 to 3.5)) {
            for ((literal, gain) in listOf(1 to downGain, 0 to upGain)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(10.0 + gain)
            }
        }

        assertEquals(sqrt(5.25), history.estimate(variable, 0.5), 1e-9)
        assertEquals(sqrt(8.0), history.upperEstimate(variable, 0.5), 1e-9)
    }

    @Test
    fun `rejection ages independently of the stabilized numeric pair`() {
        val events = ArrayList<String>()
        val history = LpDirectionalHistory(events::add, maxAge = 8L)
        val variable = VarRef.IntVar(0)
        val down = SearchDecision.IntAtMost(0, 2)
        val up = SearchDecision.IntAtLeast(0, 3)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 2.25)
        repeat(3) {
            for ((decision, objective) in listOf(down to 11.0, up to 13.0)) {
                history.commit(decision, 1)
                history.beginNode(1)
                history.record(objective)
            }
        }
        for ((decision, objective) in listOf(up to 30.0, up to 13.0, up to 13.0, down to 11.0, down to 11.0)) {
            history.commit(decision, 1)
            history.beginNode(1)
            history.record(objective)
        }
        repeat(4) { history.beginNode(0) }
        history.record(10.0)
        assertNull(history.preferGainDirection(variable, 2.25))
        history.beginNode(0)
        history.record(10.0)

        assertEquals(false, history.preferGainDirection(variable, 2.25))
        assertEquals(1, events.count { it == "REJECTION_EXPIRED" })
        assertTrue(history.hasValueHistory)
        assertTrue(history.estimate(variable, 2.25).isNaN())
    }

    @Test
    fun `stable pairs permit child ordering before cross variable validation`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.IntVar(0)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 2.25)
        repeat(2) {
            for ((decision, objective) in listOf(SearchDecision.IntAtMost(0, 2) to 11.0,
                SearchDecision.IntAtLeast(0, 3) to 13.0)) {
                history.commit(decision, 1)
                history.beginNode(1)
                history.record(objective)
            }
        }
        history.beginNode(0)
        history.record(10.0)

        assertEquals(false, history.preferGainDirection(variable, 2.25))
        assertTrue(history.estimate(variable, 2.25).isNaN())
        assertTrue(history.hasValueHistory)
        assertFalse(history.hasReliableHistory)
    }

    @Test
    fun `unusable child feedback revokes a validated estimate without adding a sample`() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val history = LpDirectionalHistory({})
            val variable = VarRef.Bool(0)
            history.beginNode(0)
            history.record(0.0)
            history.branch(variable, 0.5)
            repeat(3) {
                for (literal in listOf(0, 1)) {
                    history.commit(SearchDecision.Bool(literal), 1)
                    history.beginNode(1)
                    history.record(4.0)
                }
            }
            assertEquals(4.0, history.estimate(variable, 0.5))
            history.commit(SearchDecision.Bool(1), 1)
            history.beginNode(1)

            history.record(value)
            history.beginNode(0)
            history.record(0.0)

            assertTrue(history.estimate(variable, 0.5).isNaN())
            history.branch(variable, 0.5)
            history.commit(SearchDecision.Bool(1), 1)
            history.beginNode(1)
            history.record(4.0)
            assertEquals(4.0, history.estimate(variable, 0.5))
        }
    }

    @Test
    fun `a rejected forecast withholds advice until fresh predictions validate`() {
        for (badGain in listOf(0.0, 1000.0)) {
            val history = LpDirectionalHistory({})
            val variable = VarRef.Bool(0)
            history.beginNode(0)
            history.record(0.0)
            history.branch(variable, 0.5)
            repeat(3) {
                for (literal in listOf(0, 1)) {
                    history.commit(SearchDecision.Bool(literal), 1)
                    history.beginNode(1)
                    history.record(4.0)
                }
            }
            assertTrue(history.score(variable, 0.5).isFinite())
            history.commit(SearchDecision.Bool(1), 1)
            history.beginNode(1)
            history.record(badGain)
            assertTrue(history.score(variable, 0.5).isNaN())

            repeat(3) { round ->
                history.commit(SearchDecision.Bool(1), 1)
                history.beginNode(1)
                history.record(6.0)

                assertEquals(round == 2, history.score(variable, 0.5).isFinite())
            }
        }
    }

    @Test
    fun `forecast validation preserves extreme scales and rejects a zero gain surprise`() {
        for (gain in listOf(Double.MIN_VALUE, 1.0, Double.MAX_VALUE / 8.0)) {
            val history = LpDirectionalHistory({})
            val variable = VarRef.Bool(0)
            history.beginNode(0)
            history.record(0.0)
            history.branch(variable, 0.25)
            repeat(3) {
                for (literal in listOf(0, 1)) {
                    history.commit(SearchDecision.Bool(literal), 1)
                    history.beginNode(1)
                    history.record(gain)
                }
            }
            assertTrue(history.estimate(variable, 0.25) > 0.0)

            history.commit(SearchDecision.Bool(0), 1)
            history.beginNode(1)
            history.record(0.0)

            assertTrue(history.estimate(variable, 0.25).isNaN())
        }
    }

    @Test
    fun `parent bookkeeping pays allocation without repeatedly charging unused capacity`() {
        var work = 0L
        val history = LpDirectionalHistory({}, charge = { work += it })
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        assertTrue(work >= 128L)
        val before = work

        history.commit(SearchDecision.Bool(0), 1)
        history.beginNode(1)
        history.record(12.0)
        history.branch(VarRef.Bool(1), 0.5)

        assertTrue(work - before in 1L until 128L)
    }

    @Test
    fun `clearing sparse parents pays the ancestor slots it visits`() {
        var work = 0L
        val history = LpDirectionalHistory({}, charge = { work += it })
        for (level in listOf(0, 100)) {
            history.beginNode(level)
            history.record(10.0)
            history.branch(VarRef.Bool(level), 0.5)
        }
        val before = work

        history.clear()

        assertTrue(work - before >= 101L)
        assertEquals(0, history.retainedEntries)
    }

    @Test
    fun `reliable child preference seeks lower gains unless a child can reach the cutoff`() {
        for (cheaperUp in listOf(false, true)) {
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(0), 0.75)
            repeat(3) {
                for (up in listOf(false, true)) {
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(if (up == cheaperUp) 10.0 else 13.0)
                }
            }

            history.beginNode(0)
            history.record(10.0)

            assertEquals(cheaperUp, history.preferGainDirection(VarRef.Bool(0), 0.75))
            assertEquals(cheaperUp, history.preferGainDirection(VarRef.Bool(0), 0.75, cutoff = 14.0))
            assertEquals(!cheaperUp, history.preferGainDirection(VarRef.Bool(0), 0.75, cutoff = 12.0))
            assertNull(history.preferGainDirection(VarRef.Bool(0), 0.75, cutoff = 10.0))
        }
    }

    @Test
    fun `collection prefers only a recent under observed direction`() {
        for (firstUp in listOf(false, true)) {
            val history = LpDirectionalHistory({}, maxAge = 4L)
            val variable = VarRef.IntVar(0)
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 2.25)
            assertNull(history.preferUnderObservedDirection(variable, 2.25))
            repeat(2) {
                history.commit(if (firstUp) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                history.beginNode(1)
                history.record(11.0)
            }

            assertFalse(history.hasCollectionHistory)
            assertNull(history.preferUnderObservedDirection(variable, 2.25))
            history.commit(if (firstUp) SearchDecision.IntAtMost(0, 2) else SearchDecision.IntAtLeast(0, 3), 1)
            history.beginNode(1)
            history.record(11.0)

            assertTrue(history.hasCollectionHistory)
            assertEquals(!firstUp, history.preferUnderObservedDirection(variable, 2.25))
            assertTrue(history.score(variable, 2.25).isNaN())
            repeat(5) { history.beginNode(0) }
            assertFalse(history.hasCollectionHistory)
            assertNull(history.preferUnderObservedDirection(variable, 2.25))
        }
    }

    @Test
    fun `aging the opposite direction refreshes collection availability`() {
        val history = LpDirectionalHistory({}, maxAge = 4L)
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 0.5)
        history.commit(SearchDecision.Bool(1), 1)
        history.beginNode(1)
        history.record(11.0)
        repeat(2) { history.beginNode(1) }
        for (decision in listOf(SearchDecision.Bool(1), SearchDecision.Bool(0), SearchDecision.Bool(0))) {
            history.commit(decision, 1)
            history.beginNode(1)
            history.record(11.0)
        }

        assertTrue(history.hasCollectionHistory)
        assertFalse(history.hasReliableHistory)
        assertEquals(false, history.preferUnderObservedDirection(variable, 0.5))
    }

    @Test
    fun `collection requires some observed gain`() {
        for ((up, positive) in listOf(false to false, true to false, false to true, true to true)) {
            val history = LpDirectionalHistory({})
            val variable = VarRef.Bool(0)
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            repeat(2) {
                history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                history.beginNode(1)
                history.record(10.0)
            }
            history.commit(SearchDecision.Bool(if (up) 1 else 0), 1)
            history.beginNode(1)
            history.record(if (positive) 13.0 else 10.0)

            assertEquals(if (positive) !up else null, history.preferUnderObservedDirection(variable, 0.5))
        }
    }

    @Test
    fun `sparse ancestor records retain exact counts across depth trimming`() {
        for (operation in listOf("branch", "commit", "clear")) {
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(1.0)
            history.branch(VarRef.Bool(4), 0.5)
            history.commit(SearchDecision.Bool(8), 1)
            history.beginNode(1)
            history.record(2.0)
            for ((level, variable) in listOf(0 to 0, 3 to 1, 5 to 2)) {
                history.beginNode(level)
                history.record(10.0 + level)
                history.branch(VarRef.Bool(variable), 0.5)
            }

            when (operation) {
                "branch" -> {
                    history.beginNode(2)
                    history.record(12.0)
                    history.branch(VarRef.Bool(3), 0.5)
                }
                "commit" -> history.commit(SearchDecision.Bool(2), 4)
                else -> history.clear()
            }

            val retained = if (operation in listOf("branch", "commit")) 2 else 0
            assertEquals(retained + if (operation == "clear") 0 else 1, history.retainedEntries, operation)
        }
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

            assertFalse(history.hasReliableHistory)
        }
    }

    @Test
    fun `samples from different variables do not admit reliable scoring`() {
        val history = LpDirectionalHistory({})
        for (up in listOf(false, true)) {
            repeat(2) {
                val variable = if (up) 1 else 0
                history.beginNode(0)
                history.record(10.0)
                history.branch(VarRef.IntVar(variable), 2.25)
                history.commit(if (up) SearchDecision.IntAtLeast(variable, 3)
                else SearchDecision.IntAtMost(variable, 2), 1)
                history.beginNode(1)
                history.record(if (up) 13.0 else 11.0)
            }
        }

        assertFalse(history.hasReliableHistory)
        assertTrue(history.score(VarRef.IntVar(0), 2.25).isNaN())
        assertTrue(history.score(VarRef.IntVar(1), 2.25).isNaN())
    }

    @Test
    fun `clearing history removes two sided availability`() {
        val history = LpDirectionalHistory({})
        repeat(3) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.IntVar(0), 2.25)
            for (up in listOf(false, true)) {
                history.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                history.beginNode(1)
                history.record(if (up) 13.0 else 11.0)
            }
        }
        assertTrue(history.hasReliableHistory)

        history.clear()

        assertFalse(history.hasReliableHistory)
    }

    @Test
    fun `both directions need a validated forecast before scoring`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.IntVar(0)
        repeat(3) { round ->
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
            if (round < 2) assertTrue(history.score(variable, 2.25).isNaN())
        }

        assertEquals(sqrt(3.0), history.score(variable, 2.25), 1e-9)
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
        val history = LpDirectionalHistory(observations::add, maxAge = 6L)
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        repeat(3) {
            history.commit(SearchDecision.Bool(1), 1)
            history.beginNode(1)
            history.record(11.0)
            history.commit(SearchDecision.Bool(0), 1)
            history.beginNode(1)
            history.record(12.0)
        }
        assertTrue(history.score(VarRef.Bool(0), 0.5).isFinite())
        assertTrue(history.hasReliableHistory)
        repeat(7) { history.beginNode(0) }

        assertFalse(history.hasReliableHistory)
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
        repeat(3) {
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(10.0)
            }
        }

        assertTrue(history.score(VarRef.Bool(0), 0.5).isNaN())
        assertEquals(0.0, history.estimate(VarRef.Bool(0), 0.5))
    }

    @Test
    fun `a gain in only one child keeps ordinary scoring`() {
        for (improvingUp in listOf(false, true)) {
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(0), 0.5)
            repeat(3) {
                for (up in listOf(false, true)) {
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(if (up == improvingUp) 110.0 else 10.0)
                }
            }

            assertTrue(history.score(VarRef.Bool(0), 0.5).isNaN())
        }
    }

    @Test
    fun `refreshing one direction cannot renew the scope scoring gate`() {
        val history = LpDirectionalHistory({}, maxAge = 6L)
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        repeat(3) {
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(12.0)
            }
        }
        assertTrue(history.hasReliableHistory)

        repeat(7) {
            history.commit(SearchDecision.Bool(0), 1)
            history.beginNode(1)
            history.record(12.0)
        }

        assertFalse(history.hasReliableHistory)
    }

    @Test
    fun `aging forecast support removes reliability before the numeric pair expires`() {
        for (firstUp in listOf(false, true)) {
            val history = LpDirectionalHistory({}, maxAge = 6L)
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(0), 0.5)
            repeat(3) {
                for (up in listOf(firstUp, !firstUp)) {
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(12.0)
                }
            }
            assertTrue(history.hasReliableHistory)

            repeat(2) { history.beginNode(1) }

            assertFalse(history.hasReliableHistory)
            assertTrue(history.score(VarRef.Bool(0), 0.5).isNaN())
        }
    }

    @Test
    fun `disagreeing gains withhold advice until recent observations stabilize`() {
        val history = LpDirectionalHistory({})
        for ((gain, expected) in listOf(
            4.0 to Double.NaN, 8.0 to Double.NaN, 8.0 to 8.0, 100.0 to Double.NaN,
            6.0 to Double.NaN, 6.0 to Double.NaN, 6.0 to 6.0,
        )) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(0), 0.5)
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(10.0 + gain)
            }

            val score = history.score(VarRef.Bool(0), 0.5)

            if (expected.isNaN()) assertTrue(score.isNaN()) else assertEquals(expected, score, 1e-9)
        }
    }
}
