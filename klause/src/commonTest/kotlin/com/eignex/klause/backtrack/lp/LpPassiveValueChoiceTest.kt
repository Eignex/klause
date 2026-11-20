package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpPassiveValueChoiceTest {
    @Test
    fun `withheld value advice preserves comparison and commitment cost`() {
        for ((variable, point, preferUp) in listOf(
            Triple(VarRef.Bool(0), 0.75, false),
            Triple(VarRef.IntVar(0), 2.75, true),
        )) {
            val down = if (variable is VarRef.Bool) SearchDecision.Bool(1) else SearchDecision.IntAtMost(0, 2)
            val up = if (variable is VarRef.Bool) SearchDecision.Bool(0) else SearchDecision.IntAtLeast(0, 3)
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, point)
            repeat(2) {
                for ((decision, isUp) in listOf(down to false, up to true)) {
                    history.commit(decision, 1)
                    history.beginNode(1)
                    history.record(if (isUp == preferUp) 13.0 else 11.0)
                }
            }
            history.beginNode(0)
            history.record(10.0)
            val ordinary = if (preferUp) listOf(down, up) else listOf(up, down)
            for (reachesCutoff in listOf(false, true)) {
                val costs = ArrayList<Long>()
                for (apply in listOf(false, true)) {
                    val events = ArrayList<String>()
                    var work = 0L
                    val choice = LpPassiveValueChoice(apply, events::add, { work += it })

                    val selected = choice.select(variable, point, ordinary, history, 7,
                        cutoff = if (reachesCutoff) 12.0 else 14.0) { true }
                    choice.commit(selected.first(), 7) { true }
                    choice.commit(selected.first(), 7) { true }

                    assertEquals(if (apply) ordinary.reversed() else ordinary, selected)
                    val event = if (apply) "PASSIVE_VALUE_COMMIT_CHANGED" else "PASSIVE_VALUE_COMMIT_WITHHELD_CHANGED"
                    assertEquals(1, events.count { it == event })
                    val role = if (apply) "COMMIT" else "COMMIT_WITHHELD"
                    val mode = if (reachesCutoff) "REACHABLE" else "OPEN"
                    assertEquals(1, events.count { it == "PASSIVE_VALUE_${role}_${mode}_CHANGED" })
                    costs.add(work)
                }
                assertEquals(26L, costs.first())
                assertEquals(costs.first(), costs.last())
            }
        }
    }

    @Test
    fun `agreed child ordering does not count a changed gap mode`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 0.5)
        repeat(2) {
            for ((literal, objective) in listOf(1 to 13.0, 0 to 11.0)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(objective)
            }
        }
        history.beginNode(0)
        history.record(10.0)
        val events = ArrayList<String>()
        val choice = LpPassiveValueChoice(true, events::add, {})
        val alternatives = listOf(SearchDecision.Bool(1), SearchDecision.Bool(0))

        val selected = choice.select(variable, 0.5, alternatives, history, 7, cutoff = 12.0) { true }
        choice.commit(selected.first(), 7) { true }

        assertTrue("PASSIVE_VALUE_COMMITTED" in events)
        assertFalse("PASSIVE_VALUE_COMMIT_CHANGED" in events)
        assertFalse(events.any { it.endsWith("_OPEN_CHANGED") || it.endsWith("_REACHABLE_CHANGED") })
    }

    @Test
    fun `unavailable or invalidated value advice cannot count a commitment`() {
        for (condition in listOf("missing", "aged", "selection", "charge", "stamp", "decision", "commit", "commit-charge", "clear")) {
            val history = LpDirectionalHistory({}, maxAge = 4L)
            val variable = VarRef.IntVar(0)
            val down = SearchDecision.IntAtMost(0, 2)
            val up = SearchDecision.IntAtLeast(0, 3)
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 2.25)
            repeat(if (condition == "missing") 0 else 2) {
                for ((decision, objective) in listOf(down to 13.0, up to 11.0)) {
                    history.commit(decision, 1)
                    history.beginNode(1)
                    history.record(objective)
                }
            }
            if (condition == "aged") repeat(5) { history.beginNode(0) }
            var fresh = condition != "selection"
            var committing = false
            val events = ArrayList<String>()
            val choice = LpPassiveValueChoice(true, events::add, {
                if (condition == "charge" || (condition == "commit-charge" && committing)) fresh = false
            })
            val alternatives = listOf(up, down)
            val selected = choice.select(variable, 2.25, alternatives, history, 7) { fresh }
            if (condition == "commit") fresh = false
            if (condition == "clear") choice.clear()
            committing = true

            choice.commit(if (condition == "decision") SearchDecision.IntAtLeast(0, 4) else selected.first(),
                if (condition == "stamp") 8 else 7) { fresh }

            assertFalse("PASSIVE_VALUE_COMMITTED" in events, condition)
            assertFalse(events.any { it.endsWith("_OPEN_CHANGED") || it.endsWith("_REACHABLE_CHANGED") }, condition)
            assertFalse(choice.hasNomination, condition)
        }
    }
}
