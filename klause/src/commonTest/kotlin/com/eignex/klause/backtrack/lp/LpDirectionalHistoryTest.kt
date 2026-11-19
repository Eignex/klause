package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpDirectionalHistoryTest {
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
    fun `aging the opposite direction withholds reliable scoring`() {
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

        assertFalse(history.hasReliableHistory)
        assertTrue(history.score(variable, 0.5).isNaN())
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
        assertTrue(history.hasReliableHistory)

        history.clear()

        assertFalse(history.hasReliableHistory)
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

        assertEquals(1.0, history.score(variable, 2.25), 1e-9)
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
        val history = LpDirectionalHistory(observations::add, maxAge = 4L)
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
        assertTrue(history.hasReliableHistory)
        repeat(5) { history.beginNode(0) }

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
        repeat(2) {
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(10.0)
            }
        }

        assertFalse(history.hasReliableHistory)
        assertTrue(history.score(VarRef.Bool(0), 0.5).isNaN())
    }

    @Test
    fun `a measured zero direction keeps the minimum estimate at zero`() {
        for (improvingUp in listOf(false, true)) {
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(0), 0.5)
            repeat(2) {
                for (up in listOf(false, true)) {
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(if (up == improvingUp) 110.0 else 10.0)
                }
            }

            assertFalse(history.hasReliableHistory)
            assertEquals(0.0, history.estimate(VarRef.Bool(0), 0.5))
            assertTrue(history.score(VarRef.Bool(0), 0.5).isNaN())
        }
    }

    @Test
    fun `minimum gain favors balanced children over one large direction`() {
        val history = LpDirectionalHistory({})
        for (variable in listOf(VarRef.Bool(0), VarRef.Bool(1))) {
            repeat(2) {
                history.beginNode(0)
                history.record(0.0)
                history.branch(variable, 0.5)
                for (up in listOf(false, true)) {
                    history.commit(SearchDecision.Bool((variable.varId shl 1) or if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(if (variable.varId == 1) 4.0 else if (up) 1000.0 else 1.0)
                }
            }
        }

        assertTrue(history.score(VarRef.Bool(1), 0.5) > history.score(VarRef.Bool(0), 0.5))
    }

    @Test
    fun `refreshing one direction cannot renew the scope scoring gate`() {
        val history = LpDirectionalHistory({}, maxAge = 4L)
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        repeat(2) {
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(12.0)
            }
        }
        assertTrue(history.hasReliableHistory)

        repeat(5) {
            history.commit(SearchDecision.Bool(0), 1)
            history.beginNode(1)
            history.record(12.0)
        }

        assertFalse(history.hasReliableHistory)
    }

    @Test
    fun `aging an older observation removes two sample reliability`() {
        for (firstUp in listOf(false, true)) {
            val history = LpDirectionalHistory({}, maxAge = 4L)
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(0), 0.5)
            repeat(2) {
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
    fun `an outlier withholds advice until the recent window stabilizes`() {
        val history = LpDirectionalHistory({})
        val observations = listOf(4.0 to Double.NaN, 8.0 to 4.0, 100.0 to Double.NaN) +
            List(7) { 6.0 to Double.NaN } + listOf(6.0 to 6.0)
        for ((gain, expected) in observations) {
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

    @Test
    fun `a recent window can estimate recurring zero and positive gains`() {
        val history = LpDirectionalHistory({})
        repeat(8) { sample ->
            history.beginNode(0)
            history.record(0.0)
            history.branch(VarRef.Bool(0), 0.5)
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(if (sample % 2 == 0) 0.0 else 6.0)
            }
        }

        assertTrue(history.hasReliableHistory)
        assertEquals(3.0 - 3.0 / sqrt(7.0), history.score(VarRef.Bool(0), 0.5), 1e-9)
    }

    @Test
    fun `window estimates remain finite across objective scales`() {
        for (scale in listOf(1e-200, 1.0, 1e200)) {
            val history = LpDirectionalHistory({})
            for (gain in listOf(4.0, 8.0)) {
                history.beginNode(0)
                history.record(0.0)
                history.branch(VarRef.Bool(0), 0.5)
                for (literal in listOf(0, 1)) {
                    history.commit(SearchDecision.Bool(literal), 1)
                    history.beginNode(1)
                    history.record(gain * scale)
                }
            }

            val score = history.score(VarRef.Bool(0), 0.5)

            assertTrue(score.isFinite())
            assertEquals(4.0, score / scale, 1e-9)
        }
    }

    @Test
    fun `aging older window entries preserves a pair of fresh samples`() {
        val history = LpDirectionalHistory({}, maxAge = 8L)
        repeat(4) {
            history.beginNode(0)
            history.record(0.0)
            history.branch(VarRef.Bool(0), 0.5)
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(1.0)
            }
        }
        repeat(3) { history.beginNode(0) }

        assertTrue(history.hasReliableHistory)
        assertEquals(1.0, history.score(VarRef.Bool(0), 0.5), 1e-9)
    }
}
