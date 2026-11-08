package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LpPassiveDirectionChoiceTest {
    @Test
    fun `invalid fractional points keep ordinary order`() {
        for (point in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 1.0, 2.25)) {
            val alternatives = listOf(SearchDecision.Bool(1), SearchDecision.Bool(0))
            val events = ArrayList<String>()
            val choice = LpPassiveDirectionChoice(true, events::add, {})

            val ordered = choice.select(VarRef.Bool(0), point, true, alternatives, 1) { true }

            assertEquals(alternatives, ordered)
            assertFalse("PASSIVE_DIRECTION_COMPARED" in events)
        }
    }

    @Test
    fun `direction ordering preserves the exhaustive split`() {
        for ((variable, point, alternatives) in listOf(
            Triple(VarRef.Bool(0), 0.25, listOf(SearchDecision.Bool(1), SearchDecision.Bool(0))),
            Triple(VarRef.IntVar(0), 2.25,
                listOf(SearchDecision.IntAtMost(0, 2), SearchDecision.IntAtLeast(0, 3))),
        )) {
            val choice = LpPassiveDirectionChoice(true, {}, {})

            val ordered = choice.select(variable, point, true, alternatives, 1) { true }

            assertEquals(alternatives.reversed(), ordered)
        }
    }

    @Test
    fun `withholding retains the ordinary first alternative at equal cost`() {
        val alternatives = listOf(SearchDecision.IntAtMost(0, 2), SearchDecision.IntAtLeast(0, 3))
        val costs = ArrayList<Long>()
        for (apply in listOf(false, true)) {
            var work = 0L
            val events = ArrayList<String>()
            val choice = LpPassiveDirectionChoice(apply, events::add, { work += it })

            val ordered = choice.select(VarRef.IntVar(0), 2.25, true, alternatives, 1) { true }
            choice.commit(ordered.first(), 1) { true }

            assertEquals(if (apply) alternatives.reversed() else alternatives, ordered)
            assertEquals(1, events.count { it == if (apply) "PASSIVE_DIRECTION_COMMIT_CHANGED"
                else "PASSIVE_DIRECTION_COMMIT_WITHHELD_CHANGED" })
            costs.add(work)
        }
        assertEquals(costs.first(), costs.last())
    }

    @Test
    fun `a matching first commitment is attributed once`() {
        val events = ArrayList<String>()
        val choice = LpPassiveDirectionChoice(true, events::add, {})
        val alternatives = listOf(SearchDecision.Bool(1), SearchDecision.Bool(0))
        choice.select(VarRef.Bool(0), 0.25, true, alternatives, 1) { true }

        choice.commit(SearchDecision.Bool(0), 1) { true }
        choice.commit(SearchDecision.Bool(0), 1) { true }

        assertEquals(1, events.count { it == "PASSIVE_DIRECTION_COMMITTED" })
    }

    @Test
    fun `a sibling commitment cannot consume the nominated first alternative`() {
        val events = ArrayList<String>()
        val choice = LpPassiveDirectionChoice(true, events::add, {})
        choice.select(VarRef.Bool(0), 0.25, true,
            listOf(SearchDecision.Bool(1), SearchDecision.Bool(0)), 1) { true }

        choice.commit(SearchDecision.Bool(1), 1) { true }

        assertFalse("PASSIVE_DIRECTION_COMMITTED" in events)
    }

    @Test
    fun `stale and cleared nominations supply no commitment`() {
        for (failure in listOf("stamp", "freshness", "clear")) {
            val events = ArrayList<String>()
            val choice = LpPassiveDirectionChoice(true, events::add, {})
            choice.select(VarRef.Bool(0), 0.25, true,
                listOf(SearchDecision.Bool(1), SearchDecision.Bool(0)), 1) { true }
            if (failure == "clear") choice.clear()

            choice.commit(SearchDecision.Bool(0), if (failure == "stamp") 2 else 1) { failure != "freshness" }

            assertFalse("PASSIVE_DIRECTION_COMMITTED" in events)
        }
    }

    @Test
    fun `invalidation during selection keeps ordinary order`() {
        for (duringObservation in listOf(false, true)) {
            var fresh = true
            val events = ArrayList<String>()
            val choice = LpPassiveDirectionChoice(true, {
                events.add(it)
                if (duringObservation && it == "PASSIVE_DIRECTION_COMPARED") fresh = false
            }, { if (!duringObservation) fresh = false })
            val alternatives = listOf(SearchDecision.Bool(1), SearchDecision.Bool(0))

            val ordered = choice.select(VarRef.Bool(0), 0.25, true, alternatives, 1) { fresh }
            choice.commit(SearchDecision.Bool(0), 1) { true }

            assertEquals(alternatives, ordered)
            assertFalse("PASSIVE_DIRECTION_COMMITTED" in events)
        }
    }

    @Test
    fun `nonadjacent and foreign alternatives keep ordinary order`() {
        for (alternatives in listOf(
            listOf(SearchDecision.IntAtMost(0, 1), SearchDecision.IntAtLeast(0, 2)),
            listOf(SearchDecision.IntAtMost(0, 2), SearchDecision.IntAtLeast(0, 4)),
            listOf(SearchDecision.IntAtMost(1, 2), SearchDecision.IntAtLeast(1, 3)),
            listOf(SearchDecision.IntAtMost(0, 2)),
        )) {
            val events = ArrayList<String>()
            val choice = LpPassiveDirectionChoice(true, events::add, {})

            val ordered = choice.select(VarRef.IntVar(0), 2.25, true, alternatives, 1) { true }

            assertEquals(alternatives, ordered)
            assertFalse("PASSIVE_DIRECTION_COMPARED" in events)
        }
    }
}
