package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpDirectionalHistoryTest {
    @Test
    fun `calibration discounts geometric predictions in their existing objective units`() {
        for (scale in listOf(1.0, 10.0)) {
            val events = ArrayList<String>()
            var work = 0L
            val history = LpDirectionalHistory(events::add, calibratedNomination = true, charge = { work += it })
            val variable = VarRef.IntVar(0)
            repeat(2) {
                history.beginNode(0)
                history.record(10.0 * scale)
                history.branch(variable, 0.5)
                for (up in listOf(false, true)) {
                    history.commit(if (up) SearchDecision.IntAtLeast(0, 1) else SearchDecision.IntAtMost(0, 0), 1)
                    history.beginNode(1)
                    history.record((if (up) 19.0 else 14.0) * scale)
                }
            }
            val learned = history.balancedScore(variable, 0.5)
            val passive = history.score(variable, 0.5)
            val samples = events.count { it == "UP" || it == "DOWN" }

            val updated = history.calibrateNomination(variable, learned, 3.0 * scale)
            val discounted = history.balancedScore(variable, 0.5)

            assertTrue(updated)
            assertEquals(6.0 * scale, learned, 1e-9)
            assertEquals(3.0 * scale, discounted, 1e-9)
            assertEquals(passive, history.score(variable, 0.5))
            assertEquals(samples, events.count { it == "UP" || it == "DOWN" })
            assertEquals(2L, work)
        }
    }

    @Test
    fun `successive calibration applies to the same unscaled prediction`() {
        val history = LpDirectionalHistory({}, calibratedNomination = true)
        val variable = VarRef.IntVar(0)
        repeat(2) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            for (up in listOf(false, true)) {
                history.commit(if (up) SearchDecision.IntAtLeast(0, 1) else SearchDecision.IntAtMost(0, 0), 1)
                history.beginNode(1)
                history.record(if (up) 19.0 else 14.0)
            }
        }
        assertTrue(history.calibrateNomination(variable, history.balancedScore(variable, 0.5), 3.0))
        val current = history.balancedScore(variable, 0.5)

        val updated = history.calibrateNomination(variable, current, 1.5)

        assertTrue(updated)
        assertEquals(1.5, history.balancedScore(variable, 0.5), 1e-9)
        assertEquals(5.5, history.score(variable, 0.5), 1e-9)
    }

    @Test
    fun `zero calibration withholds a nominee and invalid feedback leaves it available`() {
        for (condition in listOf("zero", "negative", "nonfinite", "nan", "equal", "larger", "missing", "disabled")) {
            val history = LpDirectionalHistory({}, calibratedNomination = condition != "disabled")
            val variable = VarRef.IntVar(0)
            repeat(2) {
                history.beginNode(0)
                history.record(10.0)
                history.branch(variable, 0.5)
                for (up in listOf(false, true)) {
                    history.commit(if (up) SearchDecision.IntAtLeast(0, 1) else SearchDecision.IntAtMost(0, 0), 1)
                    history.beginNode(1)
                    history.record(if (up) 19.0 else 14.0)
                }
            }
            val measured = when (condition) {
                "zero" -> 0.0
                "negative" -> -1.0
                "nonfinite" -> Double.POSITIVE_INFINITY
                "nan" -> Double.NaN
                "equal" -> 6.0
                "larger" -> 12.0
                else -> 3.0
            }

            val updated = history.calibrateNomination(
                if (condition == "missing") VarRef.IntVar(1) else variable, 6.0, measured)

            assertEquals(condition == "zero", updated, condition)
            assertEquals(condition != "zero", history.balancedScore(variable, 0.5).isFinite(), condition)
            assertEquals(5.5, history.score(variable, 0.5), 1e-9)
        }
    }

    @Test
    fun `new passive samples do not renew calibration age`() {
        val events = ArrayList<String>()
        val history = LpDirectionalHistory(events::add, maxAge = 12L, calibratedNomination = true)
        val variable = VarRef.IntVar(0)
        repeat(2) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            for (up in listOf(false, true)) {
                history.commit(if (up) SearchDecision.IntAtLeast(0, 1) else SearchDecision.IntAtMost(0, 0), 1)
                history.beginNode(1)
                history.record(if (up) 19.0 else 14.0)
            }
        }
        assertTrue(history.calibrateNomination(variable, 6.0, 3.0))
        repeat(8) { history.beginNode(0) }
        repeat(2) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            for (up in listOf(false, true)) {
                history.commit(if (up) SearchDecision.IntAtLeast(0, 1) else SearchDecision.IntAtMost(0, 0), 1)
                history.beginNode(1)
                history.record(if (up) 19.0 else 14.0)
            }
        }

        val score = history.balancedScore(variable, 0.5)

        assertEquals(6.0, score, 1e-9)
        assertEquals(1, events.count { it == "CURRENT_CALIBRATION_EXPIRED" })
    }

    @Test
    fun `discarding pending observations preserves calibration and a full clear releases it`() {
        val history = LpDirectionalHistory({}, calibratedNomination = true)
        val variable = VarRef.IntVar(0)
        repeat(2) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            for (up in listOf(false, true)) {
                history.commit(if (up) SearchDecision.IntAtLeast(0, 1) else SearchDecision.IntAtMost(0, 0), 1)
                history.beginNode(1)
                history.record(if (up) 19.0 else 14.0)
            }
        }
        assertTrue(history.calibrateNomination(variable, 6.0, 3.0))

        history.discardObservations()

        assertEquals(3.0, history.balancedScore(variable, 0.5), 1e-9)
        history.clear()
        repeat(2) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            for (up in listOf(false, true)) {
                history.commit(if (up) SearchDecision.IntAtLeast(0, 1) else SearchDecision.IntAtMost(0, 0), 1)
                history.beginNode(1)
                history.record(if (up) 19.0 else 14.0)
            }
        }
        assertEquals(6.0, history.balancedScore(variable, 0.5), 1e-9)
    }

    @Test
    fun `geometric nomination uses only the latest pair in each direction`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.IntVar(0)
        repeat(3) { round ->
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            for (up in listOf(false, true)) {
                history.commit(if (up) SearchDecision.IntAtLeast(0, 1) else SearchDecision.IntAtMost(0, 0), 1)
                history.beginNode(1)
                history.record(if (round == 0) 1000.0 else if (up) 19.0 else 14.0)
            }
        }

        val score = history.balancedScore(variable, 0.5)

        assertEquals(6.0, score, 1e-9)
        assertTrue(history.score(variable, 0.5) > score)
    }

    @Test
    fun `a new sample cannot renew an aged predecessor for geometric nomination`() {
        for (visits in listOf(6, 9)) {
            val history = LpDirectionalHistory({}, maxAge = 12L)
            val variable = VarRef.IntVar(0)
            repeat(2) {
                history.beginNode(0)
                history.record(10.0)
                history.branch(variable, 0.5)
                for (up in listOf(false, true)) {
                    history.commit(if (up) SearchDecision.IntAtLeast(0, 1) else SearchDecision.IntAtMost(0, 0), 1)
                    history.beginNode(1)
                    history.record(if (up) 19.0 else 14.0)
                }
            }
            repeat(visits) { history.beginNode(0) }
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            history.commit(SearchDecision.IntAtLeast(0, 1), 1)
            history.beginNode(1)
            history.record(19.0)

            val score = history.balancedScore(variable, 0.5)

            assertEquals(visits == 6, score.isFinite())
            assertTrue(history.score(variable, 0.5).isFinite())
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
