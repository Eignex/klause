package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpHintsCollectionTest {
    @Test
    fun `collection completes a missing direction before an incumbent exists in both advice modes`() {
        val costs = ArrayList<Long>()
        for (apply in listOf(false, true)) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1L)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
            val events = ArrayList<String>()
            var work = 0L
            val hints = LpHints(1, 0, directionalGains = true, applyAdvice = apply,
                observe = events::add, charge = { work += it })
            val variable = VarRef.IntVar(0)
            val down = SearchDecision.IntAtMost(0, 2)
            val up = SearchDecision.IntAtLeast(0, 3)
            repeat(2) {
                hints.beginNode(0, Double.POSITIVE_INFINITY)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(variable)
                hints.commit(down, 1)
                hints.beginNode(1, Double.POSITIVE_INFINITY)
                hints.record(relaxation, doubleArrayOf(2.0), DoubleArray(0))
                hints.recordObjective(relaxation, 11.0)
                hints.recordOutcome(false)
                hints.finishNode(trail.state)
            }

            repeat(2) {
                hints.beginNode(0, Double.POSITIVE_INFINITY)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.finishNode(trail.state)
                hints.branch(variable)
                val selected = hints.orderAlternatives(variable, listOf(down, up), ExecutionControl.Never)
                assertEquals(listOf(up, down), selected)
                hints.commit(selected.first(), 1)
                hints.beginNode(1, Double.POSITIVE_INFINITY)
                hints.record(relaxation, doubleArrayOf(3.0), DoubleArray(0))
                hints.recordObjective(relaxation, 13.0)
                hints.recordOutcome(true)
                hints.finishNode(trail.state)
            }
            hints.beginNode(0, Double.POSITIVE_INFINITY)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)

            assertTrue(hints.directionalScore(variable).isNaN())
            assertEquals(2, events.count { it == "PASSIVE_COLLECTION_COMMIT_CHANGED" })
            assertEquals(2, events.count { it == "DOWN" })
            assertEquals(2, events.count { it == "UP" })
            assertTrue("PASSIVE_VARIABLE_COMMITTED" !in events)
            costs.add(work)
        }
        assertTrue(costs.first() > 0L)
        assertEquals(costs.first(), costs.last())
    }

    @Test
    fun `advice withholding preserves collection and its completed child observation`() {
        val costs = ArrayList<Long>()
        for (apply in listOf(false, true)) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1L)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
            val events = ArrayList<String>()
            var work = 0L
            val hints = LpHints(1, 0, directionalGains = true, applyAdvice = apply,
                observe = events::add, charge = { work += it })
            val variable = VarRef.IntVar(0)
            val down = SearchDecision.IntAtMost(0, 2)
            val up = SearchDecision.IntAtLeast(0, 3)
            for ((decision, point, objective) in listOf(
                Triple(down, 2.0, 11.0), Triple(up, 3.0, 13.0), Triple(down, 2.0, 11.0),
            )) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(variable)
                hints.commit(decision, 1)
                hints.beginNode(1, 100.0)
                hints.record(relaxation, doubleArrayOf(point), DoubleArray(0))
                hints.recordObjective(relaxation, objective)
                hints.finishNode(trail.state)
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.finishNode(trail.state)
            hints.branch(variable)

            val selected = hints.orderAlternatives(variable, listOf(down, up), ExecutionControl.Never)
            hints.commit(selected.first(), 1)
            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(3.0), DoubleArray(0))
            hints.recordObjective(relaxation, 13.0)

            assertEquals(listOf(up, down), selected)
            assertEquals(1, events.count { it == "PASSIVE_COLLECTION_COMMIT_CHANGED" })
            assertEquals(2, events.count { it == "UP" })
            assertEquals(2, events.count { it == "DOWN" })
            costs.add(work)
        }
        assertTrue(costs.first() > 0L)
        assertEquals(costs.first(), costs.last())
    }

    @Test
    fun `unavailable collection preserves ordinary alternatives`() {
        for (condition in listOf("disabled", "invalid-cutoff", "negative-infinity", "missing", "reset", "cancelled")) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1L)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
            val events = ArrayList<String>()
            val hints = LpHints(1, 0, directionalGains = condition != "disabled", observe = events::add)
            val variable = VarRef.IntVar(0)
            val alternatives = listOf(SearchDecision.IntAtMost(0, 2), SearchDecision.IntAtLeast(0, 3))
            val bound = when (condition) {
                "invalid-cutoff" -> Double.NaN
                "negative-infinity" -> Double.NEGATIVE_INFINITY
                else -> 100.0
            }
            for ((decision, point, objective) in listOf(
                Triple(alternatives.first(), 2.0, 11.0), Triple(alternatives.last(), 3.0, 13.0),
                Triple(alternatives.first(), 2.0, 11.0),
            )) {
                hints.beginNode(0, bound)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(variable)
                hints.commit(decision, 1)
                hints.beginNode(1, bound)
                hints.record(relaxation, doubleArrayOf(point), DoubleArray(0))
                hints.recordObjective(relaxation, objective)
            }
            hints.beginNode(0, bound)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            if (condition != "missing") hints.recordObjective(relaxation, 10.0)
            if (condition == "reset") hints.resetHistory()
            hints.branch(variable)

            val cancellation = ExecutionControl { condition == "cancelled" }
            val selected = hints.orderAlternatives(variable, alternatives, cancellation)
            hints.commit(selected.first(), 1)

            assertEquals(alternatives, selected, condition)
            assertTrue("PASSIVE_COLLECTION_COMMITTED" !in events, condition)
        }
    }
}
