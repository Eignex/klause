package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpPassiveDirectionExplorationTest {
    @Test
    fun `collection visits the less sampled child without changing the exhaustive split`() {
        for (boolean in listOf(false, true)) {
            val variable = if (boolean) VarRef.Bool(0) else VarRef.IntVar(0)
            val point = if (boolean) 0.5 else 2.25
            val down = if (boolean) SearchDecision.Bool(1) else SearchDecision.IntAtMost(0, 2)
            val up = if (boolean) SearchDecision.Bool(0) else SearchDecision.IntAtLeast(0, 3)
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, point)
            history.commit(down, 1)
            history.beginNode(1)
            history.record(11.0)
            val events = ArrayList<String>()
            var work = 0L
            val exploration = LpPassiveDirectionExploration(events::add, { work += it })

            val selected = exploration.select(variable, point, listOf(down, up), history, 1) { true }
            exploration.commit(up, 1) { true }
            exploration.commit(up, 1) { true }

            assertEquals(listOf(up, down), selected)
            assertFalse(history.hasTwoSidedSamples)
            assertEquals(1, events.count { it == "PASSIVE_EXPLORATION_COMMIT_CHANGED" })
            assertTrue(events.none { it.startsWith("PASSIVE_DIRECTION_") })
            assertEquals(36L, work)
        }
    }

    @Test
    fun `unavailable history points and splits retain ordinary order`() {
        for (condition in listOf("missing", "equal", "reliable", "integral", "foreign", "cancelled")) {
            val history = LpDirectionalHistory({})
            val variable = VarRef.IntVar(0)
            if (condition != "missing") repeat(if (condition == "reliable") 2 else 1) {
                history.beginNode(0)
                history.record(10.0)
                history.branch(variable, 2.25)
                val directions = if (condition in listOf("equal", "reliable")) listOf(false, true) else listOf(false)
                for (up in directions) {
                    history.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    history.beginNode(1)
                    history.record(11.0)
                }
            }
            val events = ArrayList<String>()
            val exploration = LpPassiveDirectionExploration(events::add, {})
            val alternatives = if (condition == "foreign") {
                listOf(SearchDecision.IntAtMost(0, 1), SearchDecision.IntAtLeast(0, 2))
            } else listOf(SearchDecision.IntAtMost(0, 2), SearchDecision.IntAtLeast(0, 3))

            val selected = exploration.select(variable, if (condition == "integral") 2.0 else 2.25,
                alternatives, history, 1) { condition != "cancelled" }
            exploration.commit(alternatives.first(), 1) { true }

            assertEquals(alternatives, selected)
            assertTrue(events.none { it == "PASSIVE_EXPLORATION_COMMITTED" })
        }
    }

    @Test
    fun `clearing nominations and history cannot renew collection limits`() {
        for (limit in listOf("checks", "selections")) {
            val events = ArrayList<String>()
            var work = 0L
            val exploration = LpPassiveDirectionExploration(events::add, { work += it },
                maxChecks = if (limit == "checks") 1 else 2, maxSelections = if (limit == "selections") 1 else 2)
            val history = LpDirectionalHistory({})
            val variable = VarRef.IntVar(0)
            val alternatives = listOf(SearchDecision.IntAtMost(0, 2), SearchDecision.IntAtLeast(0, 3))
            if (limit == "selections") {
                history.beginNode(0)
                history.record(10.0)
                history.branch(variable, 2.25)
                history.commit(alternatives.first(), 1)
                history.beginNode(1)
                history.record(11.0)
            }
            exploration.select(variable, 2.25, alternatives, history, 1) { true }
            exploration.clear()
            history.clear()
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 2.25)
            history.commit(alternatives.first(), 1)
            history.beginNode(1)
            history.record(11.0)
            val before = work

            val selected = exploration.select(variable, 2.25, alternatives, history, 2) { true }

            assertFalse(exploration.available)
            assertFalse(exploration.hasNomination)
            assertEquals(alternatives, selected)
            assertEquals(before, work)
        }
    }

    @Test
    fun `stale cleared and sibling commitments cannot consume collection nominations`() {
        for (condition in listOf("cleared", "changed-stamp", "sibling", "cancelled", "selection-cancelled")) {
            val history = LpDirectionalHistory({})
            val variable = VarRef.IntVar(0)
            val alternatives = listOf(SearchDecision.IntAtMost(0, 2), SearchDecision.IntAtLeast(0, 3))
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 2.25)
            history.commit(alternatives.first(), 1)
            history.beginNode(1)
            history.record(11.0)
            val events = ArrayList<String>()
            val exploration = LpPassiveDirectionExploration(events::add, {})
            var consultations = 0
            val selected = exploration.select(variable, 2.25, alternatives, history, 1) {
                consultations++
                condition != "selection-cancelled" || consultations == 1
            }
            if (condition == "cleared") exploration.clear()

            exploration.commit(if (condition == "sibling") alternatives.first() else selected.first(),
                if (condition == "changed-stamp") 2 else 1) { condition != "cancelled" }
            exploration.commit(selected.first(), 1) { true }

            assertFalse(exploration.hasNomination)
            assertTrue(events.none { it == "PASSIVE_EXPLORATION_COMMITTED" })
        }
    }
}
