package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpHintsTest {
    @Test
    fun `pruning outcome bookkeeping requires a finite cutoff and matching sample`() {
        for (bound in listOf(Double.POSITIVE_INFINITY, 100.0)) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1L)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
            var work = 0L
            val events = ArrayList<String>()
            val hints = LpHints(1, 0, directionalGains = true, observe = events::add, charge = { work += it })
            hints.beginNode(0, bound)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            val unpaired = work
            hints.recordOutcome(true)
            hints.finishNode(trail.state)
            assertEquals(unpaired, work)
            hints.branch(VarRef.IntVar(0))
            hints.commit(SearchDecision.IntAtMost(0, 2), 1)
            hints.beginNode(1, bound)
            hints.record(relaxation, doubleArrayOf(2.0), DoubleArray(0))
            hints.recordObjective(relaxation, 11.0)
            val sampled = work

            hints.recordOutcome(true)
            hints.finishNode(trail.state)

            assertEquals(if (bound.isFinite()) 9L else 0L, work - sampled)
            assertEquals(if (bound.isFinite()) 1 else 0, events.count { it == "PRUNING_SAMPLE_PRUNED" })
            assertEquals(1, events.count { it == "DOWN" })
        }
    }

    @Test
    fun `restart advice requires a fresh optimum with matching retained scope`() {
        for (change in listOf("none", "cutoff", "rows")) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1L)
            builder.addRow(intArrayOf(0), longArrayOf(1L), Relation.LE, 10L)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
            val events = ArrayList<String>()
            val hints = LpHints(1, 0, directionalGains = true, observe = events::add)
            val variable = VarRef.IntVar(0)
            val down = SearchDecision.IntAtMost(0, 2)
            val up = SearchDecision.IntAtLeast(0, 3)
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), doubleArrayOf(0.0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(variable)
                for ((decision, point, objective) in listOf(Triple(down, 2.0, 11.0), Triple(up, 3.0, 13.0))) {
                    hints.commit(decision, 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(point), doubleArrayOf(0.0))
                    hints.recordObjective(relaxation, objective)
                    hints.recordOutcome(true)
                    hints.finishNode(trail.state)
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), doubleArrayOf(0.0))
            hints.recordObjective(relaxation, 10.0)
            assertEquals(variable, hints.selectVariable(variable, variable))
            hints.branch(variable)

            hints.restart()
            hints.commit(down, 1)
            assertTrue(hints.directionalScore(variable).isNaN())
            if (change == "rows") {
                assertTrue(trail.push())
                assertTrue(trail.deactivate(setOf(0L)))
            }
            val current = relaxation.withModel(assertNotNull(trail.state.ownerWorkingModel()))
            hints.beginNode(0, if (change == "cutoff") 99.0 else 100.0)
            hints.record(current, doubleArrayOf(2.25), DoubleArray(current.model.m))
            hints.recordObjective(current, 10.0)

            if (change == "none") assertEquals(2.0, hints.directionalScore(variable), 1e-9)
            else assertTrue(hints.directionalScore(variable).isNaN())
            assertFalse("PASSIVE_VARIABLE_COMMITTED" in events)
            assertEquals(2, events.count { it == "DOWN" })
            assertEquals(2, events.count { it == "UP" })
        }
    }

    @Test
    fun `cancellation discards a reliable variable commitment`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
        val variable = VarRef.IntVar(0)
        val down = SearchDecision.IntAtMost(0, 2)
        val up = SearchDecision.IntAtLeast(0, 3)
        for (apply in listOf(false, true)) {
            val events = ArrayList<String>()
            val hints = LpHints(1, 0, directionalGains = true, applyAdvice = apply, observe = events::add)
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.75), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(variable)
                for ((decision, point, objective) in listOf(Triple(down, 2.0, 13.0), Triple(up, 3.0, 11.0))) {
                    hints.commit(decision, 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(point), DoubleArray(0))
                    hints.recordObjective(relaxation, objective)
                    hints.recordOutcome(true)
                    hints.finishNode(trail.state)
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.75), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.finishNode(trail.state)
            assertEquals(variable, hints.selectVariable(variable, variable))
            hints.branch(variable)

            hints.commit(down, 1, ExecutionControl { true })

            assertTrue("PASSIVE_VARIABLE_COMPARED" in events)
            assertFalse("PASSIVE_VARIABLE_COMMITTED" in events)
            assertFalse("PASSIVE_VARIABLE_COMMIT_WITHHELD" in events)
        }
    }

    @Test
    fun `shared column layouts keep scope checks independent of width`() {
        val costs = ArrayList<Long>()
        for (width in listOf(1, 256)) {
            val builder = LpBuilder()
            repeat(width) { builder.addVar(0, 10, cost = 1L) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), IntArray(width) { it },
                BooleanArray(width), 0L, IntArray(width) { it }, IntArray(0))
            var work = 0L
            val hints = LpHints(width, 0, directionalGains = true, charge = { work += it })
            hints.beginNode(0, 100.0)
            hints.recordObjective(relaxation, 10.0)
            val rebound = relaxation.withModel(assertNotNull(trail.state.ownerWorkingModel()))
            val before = work

            hints.recordObjective(rebound, 10.0)

            costs.add(work - before)
        }
        assertTrue(costs.first() > 0L)
        assertEquals(costs.first(), costs.last())
    }

    @Test
    fun `replacement column arrays charge the entries compared`() {
        val costs = ArrayList<Long>()
        for (width in listOf(1, 256)) {
            val builder = LpBuilder()
            repeat(width) { builder.addVar(0, 10, cost = 1L) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val model = assertNotNull(trail.state.ownerWorkingModel())
            val relaxation = LpRelaxation(model, IntArray(width) { it }, BooleanArray(width),
                0L, IntArray(width) { it }, IntArray(0))
            var work = 0L
            val hints = LpHints(width, 0, directionalGains = true, charge = { work += it })
            hints.beginNode(0, 100.0)
            hints.recordObjective(relaxation, 10.0)
            val replacement = LpRelaxation(model, relaxation.colVarId.copyOf(), relaxation.colIsBool.copyOf(),
                0L, relaxation.intColOf, relaxation.boolColOf)
            val before = work

            hints.recordObjective(replacement, 10.0)

            costs.add(work - before)
        }
        assertTrue(costs.last() - costs.first() >= 2L * (256 - 1))
    }

    @Test
    fun `cutoff reaching pruning outcomes reach matching applied and withheld child commitments`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val variable = VarRef.IntVar(0)
        val down = SearchDecision.IntAtMost(0, 2)
        val up = SearchDecision.IntAtLeast(0, 3)
        val costs = ArrayList<Long>()
        for ((constant, apply) in listOf(0L, 100L, -100L).flatMap { c -> listOf(false, true).map { c to it } }) {
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), constant, intArrayOf(0), IntArray(0))
            var work = 0L
            val events = ArrayList<String>()
            val hints = LpHints(1, 0, directionalGains = true, applyAdvice = apply,
                observe = events::add, charge = { work += it })
            repeat(2) {
                hints.beginNode(0, constant.toDouble() + 12.0)
                hints.record(relaxation, doubleArrayOf(2.75), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(variable)
                for ((decision, point, objective) in listOf(Triple(down, 2.0, 11.0), Triple(up, 3.0, 13.0))) {
                    hints.commit(decision, 1)
                    hints.beginNode(1, constant.toDouble() + 12.0)
                    hints.record(relaxation, doubleArrayOf(point), DoubleArray(0))
                    hints.recordObjective(relaxation, objective)
                    hints.recordOutcome(decision == up)
                    hints.finishNode(trail.state)
                }
            }
            hints.beginNode(0, constant.toDouble() + 12.0)
            hints.record(relaxation, doubleArrayOf(2.75), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.finishNode(trail.state)
            val before = work
            hints.branch(variable)

            val selected = hints.orderAlternatives(variable, listOf(down, up), ExecutionControl.Never)
            hints.commit(selected.first(), 1)

            assertEquals(if (apply) listOf(up, down) else listOf(down, up), selected)
            val event = if (apply) "PASSIVE_VALUE_COMMIT_CHANGED" else "PASSIVE_VALUE_COMMIT_WITHHELD_CHANGED"
            assertEquals(1, events.count { it == event })
            assertFalse("PASSIVE_COLLECTION_COMMIT_CHANGED" in events)
            costs.add(work - before)
        }
        assertEquals(setOf(costs.first()), costs.toSet())
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
        for (referencePrunes in 0..2) {
            val costs = ArrayList<Long>()
            for (apply in listOf(false, true)) {
                var work = 0L
                val events = ArrayList<String>()
                val hints = LpHints(2, 0, directionalGains = true, applyAdvice = apply,
                    observe = events::add, charge = { work += it })
                repeat(2) {
                    for (variable in listOf(baseline, proposal)) {
                        hints.beginNode(0, 100.0)
                        hints.record(relaxation, doubleArrayOf(2.25, 3.25), DoubleArray(0))
                        hints.recordObjective(relaxation, 10.0)
                        hints.branch(variable)
                        val split = if (variable == baseline) 2L else 3L
                        for (up in listOf(false, true)) {
                            hints.commit(if (up) SearchDecision.IntAtLeast(variable.varId, split + 1L)
                            else SearchDecision.IntAtMost(variable.varId, split), 1)
                            hints.beginNode(1, 100.0)
                            val child = doubleArrayOf(2.25, 3.25)
                            child[variable.varId] = if (up) split + 1.0 else split.toDouble()
                            hints.record(relaxation, child, DoubleArray(0))
                            hints.recordObjective(relaxation, if (variable == baseline) {
                                if (referencePrunes == 0) 10.0 else 30.0
                            } else if (up) 13.0 else 11.0)
                            hints.recordOutcome(variable == proposal || referencePrunes == 2 || (referencePrunes == 1 && up))
                            hints.finishNode(trail.state)
                        }
                    }
                }
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25, 3.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.finishNode(trail.state)
                assertEquals(2.0, hints.directionalScore(proposal), 1e-9)
                val before = work
                hints.beginPassiveSelection()

                val selected = assertNotNull(hints.selectVariable(proposal, baseline))
                hints.branch(selected)
                val changed = false
                hints.commit(SearchDecision.IntAtMost(selected.varId, if (changed) 3L else 2L), 1)

                assertEquals(if (changed) proposal else baseline, selected)
                val event = "PASSIVE_VARIABLE_COMMIT_WITHHELD_CHANGED"
                assertEquals(if (referencePrunes < 2) 1 else 0, events.count { it == event })
                assertEquals(4, events.count { it == "UP" })
                assertEquals(4, events.count { it == "DOWN" })
                costs.add(work - before)
            }
            assertEquals(costs.first(), costs.last())
        }
    }

    @Test
    fun `missing reference measurements keep ordinary selection`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 10, cost = 1L) }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
            booleanArrayOf(false, false), 0L, intArrayOf(0, 1), IntArray(0))
        val baseline = VarRef.IntVar(0)
        val proposal = VarRef.IntVar(1)
        val events = ArrayList<String>()
        val hints = LpHints(2, 0, directionalGains = true, observe = events::add)
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
                hints.recordOutcome(true)
                hints.finishNode(trail.state)
            }
        }
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(2.25, 3.25), DoubleArray(0))
        hints.recordObjective(relaxation, 10.0)

        for (reference in listOf(null, baseline)) {
            val selected = hints.selectVariable(proposal, reference)

            assertEquals(reference, selected)
        }
        assertTrue("PASSIVE_VARIABLE_BASELINE_UNRELIABLE" in events)
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
                hints.recordOutcome(true)
                hints.finishNode(trail.state)
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
        for ((prefix, primitive) in listOf(
            listOf(false, false) to false,
            listOf(false, false) to true,
            listOf(false, true) to false,
            listOf(false, true) to true,
        )) {
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
                    hints.recordOutcome(true)
                    hints.finishNode(trail.state)
                }
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                val before = work

                val score = if (primitive) hints.directionalScore(variable.varId, isBool = false)
                else hints.directionalScore(variable)

                if (phase == 0) {
                    assertTrue(score.isNaN())
                    assertEquals(before, work)
                } else {
                    assertEquals(2.0, score, 1e-9)
                    assertEquals(before + 4L, work)
                }
            }
        }
    }

    private fun recordedHints(lpValue: Double): LpHints {
        val builder = LpBuilder()
        val column = builder.addVar(0, 10, cost = 1L)
        builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.LE, 10L)
        val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0), booleanArrayOf(false),
            0L, intArrayOf(column), IntArray(0))
        val hints = LpHints(numIntVars = 1, numBoolVars = 0)
        hints.record(relaxation, doubleArrayOf(lpValue), DoubleArray(relaxation.model.m))
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
    fun `primitive branch scores keep boolean and integer coordinates distinct`() {
        val builder = LpBuilder()
        builder.addVar(0, 1, cost = 1L)
        builder.addVar(0, 10, cost = 4L)
        val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0, 0),
            booleanArrayOf(true, false), 0L, intArrayOf(1), intArrayOf(0))
        val hints = LpHints(numIntVars = 1, numBoolVars = 1)

        hints.record(relaxation, doubleArrayOf(0.25, 3.5), DoubleArray(0))

        assertEquals(0.25, hints.branchScore(0, isBool = true), 1e-6)
        assertEquals(2.0, hints.branchScore(0, isBool = false), 1e-6)
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
        for ((change, reason) in listOf(
            "cutoff" to "INCOMPATIBLE_CUTOFF",
            "cutoff-mapping" to "INCOMPATIBLE_CUTOFF",
            "cut" to "INCOMPATIBLE_MODEL",
            "retire" to "INCOMPATIBLE_ROWS",
            "scale" to "INCOMPATIBLE_MODEL",
            "objective" to "INCOMPATIBLE_MODEL",
            "constant" to "INCOMPATIBLE_OBJECTIVE_CONSTANT",
            "variable" to "INCOMPATIBLE_VARIABLE_MAPPING",
            "mapping" to "INCOMPATIBLE_VARIABLE_KIND",
        )) {
            val b = LpBuilder()
            val x = b.addVar(0, 10, cost = 1)
            b.addRow(intArrayOf(x), longArrayOf(1), Relation.LE, 10L)
            val base = b.build(Sense.MINIMIZE)
            val trail = LpBoundTrail(assertNotNull(base.authoritativeModel()))
            val observations = ArrayList<String>()
            val hints = LpHints(2, 1, directionalGains = true, observe = observations::add)
            fun relaxation(): LpRelaxation {
                val boolean = change == "mapping" && trail.state.depth > 0
                val variable = if (change in listOf("variable", "cutoff-mapping") && trail.state.depth > 0) 1 else 0
                return LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(variable),
                    booleanArrayOf(boolean), if (change == "constant" && trail.state.depth > 0) 1L else 0L,
                    IntArray(2) { if (!boolean && it == variable) 0 else -1 },
                    intArrayOf(if (boolean) 0 else -1))
            }
            hints.beginNode(0, 100.0)
            val parent = relaxation()
            hints.record(parent, doubleArrayOf(2.25), DoubleArray(parent.model.m))
            hints.recordObjective(parent, 10.0)
            hints.branch(VarRef.IntVar(0))
            hints.commit(SearchDecision.IntAtMost(0, 2), 1)
            assertTrue(trail.push())
            when (change) {
                "retire" -> assertTrue(trail.deactivate(setOf(0L)))
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
            hints.beginNode(1, if (change.startsWith("cutoff")) 99.0 else 100.0)
            val child = relaxation()
            hints.record(child, doubleArrayOf(2.0), DoubleArray(child.model.m))

            hints.recordObjective(child, 11.0)

            assertEquals(0, observations.count { it == "DOWN" }, change)
            assertEquals(listOf(reason), observations.filter { it.startsWith("INCOMPATIBLE_") }, change)
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
        for (change in listOf("missing", "cut", "model", "retire")) {
            val b = LpBuilder()
            val x = b.addVar(0, 10, cost = 1)
            if (change == "retire") b.addRow(intArrayOf(x), longArrayOf(1), Relation.LE, 10L)
            val trail = LpBoundTrail(assertNotNull(b.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
            val observations = ArrayList<String>()
            val hints = LpHints(1, 0, directionalGains = true, observe = observations::add)
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(relaxation.model.m))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(VarRef.IntVar(0))
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(relaxation.model.m))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                    hints.recordOutcome(true)
                    hints.finishNode(trail.state)
                }
            }
            assertTrue(hints.directionalScore(VarRef.IntVar(0)).isFinite())

            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(relaxation.model.m))

            if (change != "missing") {
                hints.recordObjective(relaxation, 10.0)
                if (change == "retire") {
                    assertTrue(trail.deactivate(setOf(0L)))
                } else if (change == "cut") {
                    assertTrue(trail.append(
                        LpScopedRow(0L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(8L),
                            ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))),
                        scoped = false,
                    ))
                } else {
                    val old = trail.state.model.objective
                    assertTrue(trail.replaceObjective(ExactLpObjective(
                        List(old.size) { old.cost(it) }, externalConstant = ExactLpNumber.of(1L),
                    )))
                }
                hints.finishNode(trail.state)
            } else hints.finishNode()

            assertTrue(hints.directionalScore(VarRef.IntVar(0)).isNaN())
            assertTrue(hints.branchScore(VarRef.IntVar(0)).isFinite())
            val reasons = observations.filter { it.startsWith("INCOMPATIBLE_") }
            assertEquals(when (change) {
                "retire" -> listOf("INCOMPATIBLE_ROWS")
                "cut", "model" -> listOf("INCOMPATIBLE_MODEL")
                else -> emptyList()
            }, reasons)
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
                hints.beginNode(0, constant.toDouble() + 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.branch(variable)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, constant.toDouble() + 100.0)
                    hints.record(relaxation, doubleArrayOf(if (up) 3.0 else 2.0), DoubleArray(0))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                    hints.recordOutcome(true)
                    hints.finishNode(trail.state)
                }
            }
            hints.beginNode(0, constant.toDouble() + 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)

            assertEquals(2.0, hints.directionalScore(variable), 1e-9, "constant=$constant")
        }
    }
}
