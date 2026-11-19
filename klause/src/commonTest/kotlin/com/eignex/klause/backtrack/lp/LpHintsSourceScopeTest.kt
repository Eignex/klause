package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.relaxation.CutColumnSource
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpHintsSourceScopeTest {
    @Test
    fun `retained estimates reach matched applied and withheld branch commitments`() {
        val costs = ArrayList<Long>()
        for (apply in listOf(false, true)) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 10, cost = 1L) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val map = CutSourceMap(Any(), 0L,
                List(2) { CutColumnSource(CutSource(CutSourceKind.INTEGER, it)) })
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                booleanArrayOf(false, false), 0L, intArrayOf(0, 1), IntArray(0), sourceMap = map)
            val events = ArrayList<String>()
            var work = 0L
            val hints = LpHints(2, 0, directionalGains = true, applyAdvice = apply,
                observe = events::add, charge = { work += it })
            val proposal = VarRef.IntVar(1)
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25, 3.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(proposal)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(1, 4) else SearchDecision.IntAtMost(1, 3), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(2.25, if (up) 4.0 else 3.0), DoubleArray(0))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                }
            }
            assertTrue(trail.append(
                LpScopedRow(0L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(8L),
                    ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))), false,
            ))
            hints.finishNode(trail.state)
            val next = relaxation.withModel(assertNotNull(trail.state.ownerWorkingModel()), map)
            hints.beginNode(0, 100.0)
            hints.record(next, doubleArrayOf(2.25, 3.25), DoubleArray(next.model.m))
            hints.recordObjective(next, 10.0)
            hints.beginPassiveSelection()

            val selected = assertNotNull(hints.selectVariable(proposal, VarRef.IntVar(0)))
            hints.branch(selected)
            hints.commit(SearchDecision.IntAtMost(selected.varId, if (apply) 3L else 2L), 1)

            assertEquals(if (apply) proposal else VarRef.IntVar(0), selected)
            val event = if (apply) "PASSIVE_VARIABLE_COMMIT_CHANGED" else "PASSIVE_VARIABLE_COMMIT_WITHHELD_CHANGED"
            assertEquals(1, events.count { it == event })
            costs.add(work)
        }
        assertTrue(costs.first() > 0L)
        assertEquals(costs.first(), costs.last())
    }

    @Test
    fun `foreign source cutoff constant and lifecycle changes clear completed gains`() {
        for (change in listOf("source", "cutoff", "constant", "assumptions", "reset", "clear")) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1L)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val source = Any()
            val coordinates = listOf(CutColumnSource(CutSource(CutSourceKind.INTEGER, 0)))
            val map = CutSourceMap(source, 0L, coordinates)
            val model = assertNotNull(trail.state.ownerWorkingModel())
            val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false),
                0L, intArrayOf(0), IntArray(0), sourceMap = map)
            val hints = LpHints(1, 0, directionalGains = true)
            val variable = VarRef.IntVar(0)
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(variable)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                }
            }
            assertTrue(hints.directionalScore(variable).isFinite())
            if (change == "reset") hints.resetHistory()
            if (change == "clear") hints.clear()
            val nextMap = CutSourceMap(if (change == "source") Any() else source, 0L, coordinates,
                assumptions = if (change == "assumptions") setOf("condition") else emptySet())
            val next = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false),
                if (change == "constant") 1L else 0L, intArrayOf(0), IntArray(0), sourceMap = nextMap)

            hints.beginNode(1, if (change == "cutoff") 50.0 else 100.0)
            hints.record(next, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordObjective(next, 10.0)

            assertTrue(hints.directionalScore(variable).isNaN(), change)
        }
    }

    @Test
    fun `disabled gains skip source scope capture and validation work`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val map = CutSourceMap(Any(), 0L, listOf(CutColumnSource(CutSource(CutSourceKind.INTEGER, 0))))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0), sourceMap = map)
        val events = ArrayList<String>()
        var work = 0L
        val hints = LpHints(1, 0, directionalGains = false, observe = events::add, charge = { work += it })

        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
        hints.recordObjective(relaxation, 10.0)
        hints.finishNode(trail.state)

        assertEquals(0L, work)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `compatible source row edits retain completed gains and discard the pending measurement`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val map = CutSourceMap(Any(), 0L, listOf(CutColumnSource(CutSource(CutSourceKind.INTEGER, 0))))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0), sourceMap = map)
        val hints = LpHints(1, 0, directionalGains = true)
        val variable = VarRef.IntVar(0)
        repeat(2) {
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.branch(variable)
            for (up in listOf(false, true)) {
                hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                hints.beginNode(1, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
            }
        }
        val score = hints.directionalScore(variable)
        assertTrue(score.isFinite())
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
        hints.recordObjective(relaxation, 10.0)
        hints.branch(variable)
        hints.commit(SearchDecision.IntAtMost(0, 2), 1)
        hints.beginNode(1, 100.0)
        assertTrue(trail.append(
            LpScopedRow(0L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(8L),
                ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))), true,
        ))
        val next = relaxation.withModel(assertNotNull(trail.state.ownerWorkingModel()), map)

        hints.record(next, doubleArrayOf(2.25), DoubleArray(next.model.m))
        hints.recordObjective(next, 10000.0)

        assertEquals(score, hints.directionalScore(variable))
    }

    @Test
    fun `row edits after a node suspend retained gains until a fresh compatible optimum`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val map = CutSourceMap(Any(), 0L, listOf(CutColumnSource(CutSource(CutSourceKind.INTEGER, 0))))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0), sourceMap = map)
        val hints = LpHints(1, 0, directionalGains = true)
        val variable = VarRef.IntVar(0)
        repeat(2) {
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.branch(variable)
            for (up in listOf(false, true)) {
                hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                hints.beginNode(1, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
            }
        }
        val score = hints.directionalScore(variable)
        assertTrue(score.isFinite())
        assertTrue(trail.append(
            LpScopedRow(0L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(8L),
                ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))), false,
        ))

        hints.finishNode(trail.state)
        assertTrue(hints.directionalScore(variable).isNaN())
        val next = relaxation.withModel(assertNotNull(trail.state.ownerWorkingModel()), map)
        hints.beginNode(1, 100.0)
        hints.record(next, doubleArrayOf(2.25), DoubleArray(next.model.m))
        hints.recordObjective(next, 10.0)

        assertEquals(score, hints.directionalScore(variable))
    }

}
