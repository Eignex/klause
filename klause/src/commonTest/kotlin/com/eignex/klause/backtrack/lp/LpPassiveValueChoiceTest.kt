package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
                    history.finishNode(isUp != preferUp)
                }
            }
            val ordinary = if (preferUp) listOf(down, up) else listOf(up, down)
            val costs = ArrayList<Long>()
            for (apply in listOf(false, true)) {
                val events = ArrayList<String>()
                var work = 0L
                val choice = LpPassiveValueChoice(apply, events::add, { work += it })

                val selected = choice.select(variable, point, ordinary, history, 7) { true }
                choice.commit(selected.first(), 7) { true }
                choice.commit(selected.first(), 7) { true }

                assertEquals(if (apply) ordinary.reversed() else ordinary, selected)
                val event = if (apply) "PASSIVE_VALUE_COMMIT_CHANGED" else "PASSIVE_VALUE_COMMIT_WITHHELD_CHANGED"
                assertEquals(1, events.count { it == event })
                costs.add(work)
            }
            assertEquals(24L, costs.first())
            assertEquals(costs.first(), costs.last())
        }
    }

    @Test
    fun `unavailable or invalidated value advice cannot count a commitment`() {
        for (condition in listOf("missing", "aged", "selection", "charge", "stamp", "decision", "commit", "clear")) {
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
                    history.finishNode(decision == down)
                }
            }
            if (condition == "aged") repeat(5) { history.beginNode(0) }
            var fresh = condition != "selection"
            val events = ArrayList<String>()
            val choice = LpPassiveValueChoice(true, events::add, { if (condition == "charge") fresh = false })
            val alternatives = listOf(down, up)
            val selected = choice.select(variable, 2.25, alternatives, history, 7) { fresh }
            if (condition == "commit") fresh = false
            if (condition == "clear") choice.clear()

            choice.commit(if (condition == "decision") SearchDecision.IntAtLeast(0, 4) else selected.first(),
                if (condition == "stamp") 8 else 7) { fresh }

            assertFalse("PASSIVE_VALUE_COMMITTED" in events, condition)
            assertFalse(choice.hasNomination, condition)
        }
    }
}
