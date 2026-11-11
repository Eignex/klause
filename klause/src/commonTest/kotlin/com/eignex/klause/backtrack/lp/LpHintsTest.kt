package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpDirectionalGain
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.CutColumnSource
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpHintsTest {
    @Test
    fun `both current advice modes preserve passive selection before an incumbent`() {
        for (apply in listOf(false, true)) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 10, cost = 1L) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                booleanArrayOf(false, false), 0L, intArrayOf(0, 1), IntArray(0))
            val events = ArrayList<String>()
            var queries = 0
            val hints = LpHints(2, 0, directionalGains = true, applyAdvice = apply, measuredCalibration = true,
                observe = events::add, currentVariableGains = { _, _, _, _ -> queries++; null })
            val proposal = VarRef.IntVar(1)
            val baseline = VarRef.IntVar(0)
            repeat(2) {
                hints.beginNode(0, 100.0, currentComparisonsEnabled = false)
                hints.record(relaxation, doubleArrayOf(2.25, 3.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(proposal)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(1, 4) else SearchDecision.IntAtMost(1, 3), 1)
                    hints.beginNode(1, 100.0, currentComparisonsEnabled = false)
                    hints.record(relaxation, doubleArrayOf(2.25, if (up) 4.0 else 3.0), DoubleArray(0))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                }
            }
            hints.beginNode(0, 100.0, currentComparisonsEnabled = false)
            hints.record(relaxation, doubleArrayOf(2.25, 3.25), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)

            val selected = assertNotNull(hints.selectVariable(proposal, baseline))
            hints.branch(selected)
            hints.commit(SearchDecision.IntAtMost(1, 3), 1)

            assertEquals(proposal, selected)
            assertEquals(0, queries)
            assertEquals(1, events.count { it == "PASSIVE_VARIABLE_COMMIT_CHANGED" })
            assertTrue(events.none { it.startsWith("CURRENT_") })
        }
    }

    @Test
    fun `entering current measurement mode discards cold passive confidence`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
        val hints = LpHints(1, 0, directionalGains = true, measuredCalibration = true,
            currentVariableGains = { _, _, _, _ -> null })
        val variable = VarRef.IntVar(0)
        repeat(2) {
            hints.beginNode(0, 100.0, currentComparisonsEnabled = false)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.branch(variable)
            for (up in listOf(false, true)) {
                hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                hints.beginNode(1, 100.0, currentComparisonsEnabled = false)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
            }
        }
        assertTrue(hints.directionalScore(variable).isFinite())

        hints.beginNode(0, 100.0, currentComparisonsEnabled = true)
        hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
        hints.recordObjective(relaxation, 10.0)

        assertTrue(hints.directionalScore(variable).isNaN())
    }

    @Test
    fun `rejected measurements calibrate both advice modes before matching variable commitments`() {
        val costs = ArrayList<Long>()
        for (apply in listOf(false, true)) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 10, cost = 1L) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                booleanArrayOf(false, false), 0L, intArrayOf(0, 1), IntArray(0))
            val point = doubleArrayOf(2.25, 3.25)
            val baseline = VarRef.IntVar(0)
            val proposal = VarRef.IntVar(1)
            val events = ArrayList<String>()
            var queries = 0
            var afterQuery = false
            var followup = 0L
            val hints = LpHints(2, 0, directionalGains = true, applyAdvice = apply, measuredCalibration = true,
                observe = events::add, charge = { if (afterQuery) followup += it },
                currentVariableGains = { _, _, _, _ ->
                    afterQuery = true
                    queries++
                    if (queries == 1) listOf(LpDirectionalGain(0.1, 0.2), LpDirectionalGain(8.0, 16.0))
                    else listOf(LpDirectionalGain(8.0, 16.0), LpDirectionalGain(1.0, 2.0))
                })
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, point, DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(proposal)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(1, 4) else SearchDecision.IntAtMost(1, 3), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(2.25, if (up) 4.0 else 3.0), DoubleArray(0))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, point, DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)

            val rejected = hints.selectVariable(proposal, baseline)
            val paidRejection = followup
            val calibrated = hints.directionalScore(proposal)
            afterQuery = false
            followup = 0L
            hints.record(relaxation, doubleArrayOf(2.5, 3.5), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            val selected = assertNotNull(hints.selectVariable(proposal, baseline))
            hints.branch(selected)
            hints.commit(if (apply) SearchDecision.IntAtMost(1, 3) else SearchDecision.IntAtMost(0, 2), 1)

            assertEquals(baseline, rejected)
            assertEquals(16L, paidRejection)
            assertEquals(kotlin.math.sqrt(0.1) * kotlin.math.sqrt(0.2), calibrated, 1e-12)
            assertEquals(if (apply) proposal else baseline, selected)
            assertEquals(2, queries)
            assertEquals(1, events.count { it == "CURRENT_CALIBRATION_UPDATED" })
            assertEquals(2, events.count { it == "UP" })
            assertEquals(2, events.count { it == "DOWN" })
            val committed = if (apply) "CURRENT_VARIABLE_COMMIT_CHANGED" else "CURRENT_VARIABLE_COMMIT_WITHHELD_CHANGED"
            assertEquals(1, events.count { it == committed })
            costs.add(followup)
        }
        assertEquals(costs.first(), costs.last())
    }

    @Test
    fun `cancellation and point replacement while calibration is charged preserve its prediction`() {
        for (condition in listOf("cancelled", "replaced")) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 10, cost = 1L) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                booleanArrayOf(false, false), 0L, intArrayOf(0, 1), IntArray(0))
            val point = doubleArrayOf(2.25, 3.25)
            val proposal = VarRef.IntVar(1)
            val baseline = VarRef.IntVar(0)
            val events = ArrayList<String>()
            var calibrating = false
            var cancelled = false
            lateinit var hints: LpHints
            hints = LpHints(2, 0, directionalGains = true, measuredCalibration = true,
                observe = {
                    events.add(it)
                    if (it == "CURRENT_CALIBRATION_CHECK") calibrating = true
                }, charge = {
                    if (calibrating && it == 8L) {
                        if (condition == "cancelled") cancelled = true
                        else hints.record(relaxation, doubleArrayOf(2.5, 3.5), DoubleArray(0))
                    }
                }, currentVariableGains = { _, _, _, _ ->
                    listOf(LpDirectionalGain(0.1, 0.2), LpDirectionalGain(8.0, 16.0))
                })
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, point, DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(proposal)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(1, 4) else SearchDecision.IntAtMost(1, 3), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(2.25, if (up) 4.0 else 3.0), DoubleArray(0))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, point, DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            val before = hints.directionalScore(proposal)

            val selected = hints.selectVariable(proposal, baseline) { cancelled }
            calibrating = false
            cancelled = false
            hints.record(relaxation, point, DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)

            assertEquals(baseline, selected)
            assertEquals(before, hints.directionalScore(proposal), 1e-12)
            assertEquals(1, events.count { it == "CURRENT_CALIBRATION_STALE" })
            assertFalse(events.contains("CURRENT_CALIBRATION_UPDATED"))
        }
    }

    @Test
    fun `unadmitted current history preserves ordinary LP guidance without bookkeeping`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
        val events = ArrayList<String>()
        var work = 0L
        var queries = 0
        val hints = LpHints(1, 0, directionalGains = true, measuredCalibration = true,
            observe = events::add, charge = { work += it }, currentVariableGains = { _, _, _, _ -> queries++; null })
        val variable = VarRef.IntVar(0)

        hints.beginNode(0, Double.POSITIVE_INFINITY, historyEnabled = false)
        hints.record(relaxation, doubleArrayOf(0.5), DoubleArray(0))
        hints.recordObjective(relaxation, 10.0)
        hints.beginPassiveSelection()
        hints.branch(variable)
        hints.commit(SearchDecision.IntAtMost(0, 0), 1)

        assertTrue(hints.directionalScore(variable).isNaN())
        assertFalse(hints.canCollectVariable)
        assertTrue(hints.branchScore(variable) > 0.0)
        assertEquals(0L, work)
        assertEquals(0, queries)
        assertTrue(events.isEmpty())
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
            val hints = LpHints(1, 0, directionalGains = true,
            currentVariableGains = { _, _, _, _ -> null })
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
    fun `compatible source row edits retain completed gains and discard the pending measurement`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val map = CutSourceMap(Any(), 0L, listOf(CutColumnSource(CutSource(CutSourceKind.INTEGER, 0))))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0), sourceMap = map)
        val hints = LpHints(1, 0, directionalGains = true,
            currentVariableGains = { _, _, _, _ -> null })
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
        val hints = LpHints(1, 0, directionalGains = true,
            currentVariableGains = { _, _, _, _ -> null })
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

    @Test
    fun `reliable passive history reaches matching applied and withheld commitments`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 10, cost = 1L) }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
            booleanArrayOf(false, false), 0L, intArrayOf(0, 1), IntArray(0))
        val baseline = VarRef.IntVar(0)
        val proposal = VarRef.IntVar(1)
        val costs = ArrayList<Long>()
        for (apply in listOf(false, true)) {
            var work = 0L
            val events = ArrayList<String>()
            val hints = LpHints(2, 0, directionalGains = true, applyAdvice = apply,
                observe = events::add, charge = { work += it })
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25, 3.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(proposal)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(1, 4L) else SearchDecision.IntAtMost(1, 3L), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(2.25, if (up) 4.0 else 3.0), DoubleArray(0))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25, 3.25), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.finishNode(trail.state)
            assertEquals(1.5, hints.directionalScore(proposal), 1e-9)
            val before = work
            hints.beginPassiveSelection()

            val selected = assertNotNull(hints.selectVariable(proposal, baseline))
            hints.branch(selected)
            hints.commit(SearchDecision.IntAtMost(selected.varId, if (apply) 3L else 2L), 1)

            assertEquals(if (apply) proposal else baseline, selected)
            val event = if (apply) "PASSIVE_VARIABLE_COMMIT_CHANGED" else "PASSIVE_VARIABLE_COMMIT_WITHHELD_CHANGED"
            assertEquals(1, events.count { it == event })
            assertEquals(2, events.count { it == "UP" })
            assertEquals(2, events.count { it == "DOWN" })
            costs.add(work - before)
        }
        assertEquals(costs.first(), costs.last())
    }

    @Test
    fun `passive selection withholds advice from an unmatched current point`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 10, cost = 1L) }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(trail.state.ownerWorkingModel())
        val relaxation = LpRelaxation(model, intArrayOf(0, 1), booleanArrayOf(false, false),
            0L, intArrayOf(0, 1), IntArray(0))
        val events = ArrayList<String>()
        val hints = LpHints(2, 0, directionalGains = true, observe = events::add)
        val proposal = VarRef.IntVar(1)
        repeat(2) {
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25, 3.25), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.branch(proposal)
            for (up in listOf(false, true)) {
                hints.commit(if (up) SearchDecision.IntAtLeast(1, 4L) else SearchDecision.IntAtMost(1, 3L), 1)
                hints.beginNode(1, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25, if (up) 4.0 else 3.0), DoubleArray(0))
                hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
            }
        }
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(2.25, 3.25), DoubleArray(0))
        hints.recordObjective(LpRelaxation(model, intArrayOf(0, 1), booleanArrayOf(false, false),
            0L, intArrayOf(0, 1), IntArray(0)), 10.0)

        val selected = hints.selectVariable(proposal, VarRef.IntVar(0))
        hints.branch(VarRef.IntVar(0))
        hints.commit(SearchDecision.IntAtMost(0, 2L), 1)

        assertEquals(VarRef.IntVar(0), selected)
        assertTrue(hints.directionalScore(proposal).isNaN())
        assertFalse("PASSIVE_VARIABLE_COMPARED" in events)
        assertFalse("PASSIVE_VARIABLE_COMMITTED" in events)
    }

    @Test
    fun `directional scoring skips lookup work until both directions have enough samples`() {
        for (prefix in listOf(listOf(false, false), listOf(false, true))) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
            var work = 0L
            val hints = LpHints(1, 0, directionalGains = true, charge = { work += it })
            val variable = VarRef.IntVar(0)
            for ((phase, directions) in listOf(prefix, listOf(false, false, true, true)).withIndex()) {
                for (up in directions) {
                    hints.beginNode(0, 100.0)
                    hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                    hints.recordObjective(relaxation, 10.0)
                    hints.branch(variable)
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(if (up) 3.0 else 2.0), DoubleArray(0))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                }
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                val before = work

                val score = hints.directionalScore(variable)

                if (phase == 0) {
                    assertTrue(score.isNaN())
                    assertEquals(before, work)
                } else {
                    assertEquals(1.5, score, 1e-9)
                    assertEquals(before + 1L, work)
                }
            }
        }
    }


    private fun recordedHints(lpValue: Double): LpHints {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, 10L)),
        )
        val rel = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(1))).build(PropagationSession(p))
        val hints = LpHints(numIntVars = 1, numBoolVars = 0)
        val primal = DoubleArray(rel.colVarId.size)
        for (col in rel.colVarId.indices) {
            if (rel.colVarId[col] == 0 && !rel.colIsBool[col]) primal[col] = lpValue
        }
        hints.record(rel, primal, DoubleArray(rel.model.m))
        return hints
    }

    @Test
    fun `a fractional lp value should put the floor split first`() {
        val ordered = recordedHints(3.7).order(VarRef.IntVar(0), (0L..10L).asSequence())
        assertEquals(3L, ordered.first())
    }

    @Test
    fun `an integral lp value should put the nearest value first`() {
        val ordered = recordedHints(4.0).order(VarRef.IntVar(0), (0L..10L).asSequence())
        assertEquals(4L, ordered.first())
    }

    @Test
    fun `values should stay unordered without a recorded solve`() {
        val ordered = LpHints(numIntVars = 1, numBoolVars = 0).order(VarRef.IntVar(0), (5L..9L).asSequence())
        assertEquals(5L, ordered.first())
    }

    @Test
    fun `clearing hints should restore the configured value order`() {
        val hints = recordedHints(3.7)

        hints.clear()

        assertEquals(listOf(5L, 6L, 7L), hints.order(VarRef.IntVar(0), sequenceOf(5L, 6L, 7L)).toList())
    }

    @Test
    fun `the branch score should weigh a real row's share of the reduced cost`() {
        // x in [0,10] sits only in the real row 0.5x <= 3; a dual of -4 on it gives x a reduced cost of 2.
        val b = LpBuilder()
        val x = b.addVar(0, 10, cost = 0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.LE, 3.0)
        val relaxation = LpRelaxation(
            model = b.build(Sense.MINIMIZE),
            colVarId = intArrayOf(0),
            colIsBool = booleanArrayOf(false),
            objectiveConstant = 0L,
            intColOf = intArrayOf(x),
            boolColOf = IntArray(0),
        )
        val hints = LpHints(numIntVars = 1, numBoolVars = 0)

        hints.record(relaxation, doubleArrayOf(3.5), doubleArrayOf(-4.0))

        assertEquals(1.0, hints.branchScore(VarRef.IntVar(0)), 1e-6)
    }

    @Test
    fun `disabled directional gains skip history bookkeeping`() {
        val b = LpBuilder()
        b.addVar(0, 10, cost = 1)
        val trail = LpBoundTrail(assertNotNull(b.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
        val observations = ArrayList<String>()
        var work = 0L
        val hints = LpHints(1, 0, directionalGains = false, observe = observations::add, charge = { work += it })

        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
        hints.recordObjective(relaxation, 10.0)
        hints.branch(VarRef.IntVar(0))
        hints.commit(SearchDecision.IntAtMost(0, 2), 1)
        hints.beginNode(1, 100.0)
        hints.record(relaxation, doubleArrayOf(2.0), DoubleArray(0))
        hints.recordObjective(relaxation, 11.0)
        hints.finishNode()
        hints.resetHistory()

        assertEquals(0L, work)
        assertTrue(observations.isEmpty())
    }

    @Test
    fun `cutoff row and objective changes reject pending directional gains`() {
        for (change in listOf("cutoff", "cut", "scale", "objective", "constant", "mapping")) {
            val b = LpBuilder()
            val x = b.addVar(0, 10, cost = 1)
            b.addRow(intArrayOf(x), longArrayOf(1), Relation.LE, 10L)
            val base = b.build(Sense.MINIMIZE)
            val trail = LpBoundTrail(assertNotNull(base.authoritativeModel()))
            val observations = ArrayList<String>()
            val hints = LpHints(1, 1, directionalGains = true, observe = observations::add)
            fun relaxation() = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(change == "mapping" && trail.state.depth > 0),
                if (change == "constant" && trail.state.depth > 0) 1L else 0L,
                intArrayOf(0), IntArray(0))
            hints.beginNode(0, 100.0)
            val parent = relaxation()
            hints.record(parent, doubleArrayOf(2.25), DoubleArray(parent.model.m))
            hints.recordObjective(parent, 10.0)
            hints.branch(VarRef.IntVar(0))
            hints.commit(SearchDecision.IntAtMost(0, 2), 1)
            assertTrue(trail.push())
            when (change) {
                "cut" -> assertTrue(trail.append(
                    LpScopedRow(1L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(2L),
                        ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))),
                    scoped = true,
                ))
                "scale", "objective" -> {
                    val old = trail.state.model.objective
                    assertTrue(trail.replaceObjective(ExactLpObjective(
                        List(old.size) { old.cost(it) },
                        scale = ExactLpNumber.of(if (change == "scale") 2L else 1L),
                        externalConstant = ExactLpNumber.of(if (change == "objective") 1L else 0L),
                    )))
                }
            }
            hints.beginNode(1, if (change == "cutoff") 99.0 else 100.0)
            val child = relaxation()
            hints.record(child, doubleArrayOf(2.0), DoubleArray(child.model.m))

            hints.recordObjective(child, 11.0)

            assertEquals(0, observations.count { it == "DOWN" }, change)
        }
    }

    @Test
    fun `compatible bound edits and sibling retraction retain the immediate parent gain`() {
        val b = LpBuilder()
        b.addVar(0, 10, cost = 1)
        val trail = LpBoundTrail(assertNotNull(b.build(Sense.MINIMIZE).authoritativeModel()))
        val observations = ArrayList<String>()
        val hints = LpHints(1, 0, directionalGains = true, observe = observations::add)
        fun relaxation() = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
        hints.beginNode(0, 100.0)
        hints.record(relaxation(), doubleArrayOf(2.25), DoubleArray(0))
        hints.recordObjective(relaxation(), 10.0)
        hints.branch(VarRef.IntVar(0))
        for (up in listOf(false, true)) {
            assertTrue(trail.push())
            assertTrue(trail.assertBound(0, !up, ExactLpSide(ExactLpNumber.of(if (up) 3L else 2L)), 1L))
            hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
            hints.beginNode(1, 100.0)
            hints.record(relaxation(), doubleArrayOf(if (up) 3.0 else 2.0), DoubleArray(0))
            hints.recordObjective(relaxation(), if (up) 13.0 else 11.0)
            assertTrue(trail.pop(0))
        }

        assertEquals(1, observations.count { it == "DOWN" })
        assertEquals(1, observations.count { it == "UP" })
    }

    @Test
    fun `missing or cut changed current optima keep the existing score`() {
        for (change in listOf("missing", "cut")) {
            val b = LpBuilder()
            b.addVar(0, 10, cost = 1)
            val trail = LpBoundTrail(assertNotNull(b.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
            val hints = LpHints(1, 0, directionalGains = true)
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(VarRef.IntVar(0))
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                }
            }
            assertTrue(hints.directionalScore(VarRef.IntVar(0)).isFinite())

            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))

            if (change == "cut") {
                hints.recordObjective(relaxation, 10.0)
                assertTrue(trail.append(
                    LpScopedRow(0L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(8L),
                        ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))),
                    scoped = false,
                ))
                hints.finishNode(trail.state)
            } else hints.finishNode()

            assertTrue(hints.directionalScore(VarRef.IntVar(0)).isNaN())
            assertTrue(hints.branchScore(VarRef.IntVar(0)).isFinite())
        }
    }

    @Test
    fun `stable objective constants preserve small directional gains`() {
        for (constant in listOf(0L, 1L shl 60, -(1L shl 60))) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), constant, intArrayOf(0), IntArray(0))
            val hints = LpHints(1, 0, directionalGains = true)
            val variable = VarRef.IntVar(0)
            repeat(2) {
                hints.beginNode(0, Double.POSITIVE_INFINITY)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(variable)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, Double.POSITIVE_INFINITY)
                    hints.record(relaxation, doubleArrayOf(if (up) 3.0 else 2.0), DoubleArray(0))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                }
            }
            hints.beginNode(0, Double.POSITIVE_INFINITY)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)

            assertEquals(1.5, hints.directionalScore(variable), 1e-9, "constant=$constant")
        }
    }
}
