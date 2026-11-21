package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.splitIntAlternatives
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
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
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.ExecutionControl
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpHintsTest {
    @Test
    fun `unmeasured baseline priority preserves current fractionality before committing advice`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 10, cost = 1L) }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(trail.state.ownerWorkingModel())
        for (booleanBaseline in listOf(false, true)) {
            val baseline = if (booleanBaseline) VarRef.Bool(0) else VarRef.IntVar(0)
            val proposal = VarRef.IntVar(if (booleanBaseline) 0 else 1)
            val session = PropagationSession(Problem(if (booleanBaseline) 1 else 0,
                if (booleanBaseline) 1 else 2,
                Array(if (booleanBaseline) 1 else 2) { IntDomain(0, 10) }, emptyArray()))
            for (reference in listOf("unmeasured", "known-zero", "known-positive")) {
                for ((baselinePoint, proposedPoint, context) in listOf(Triple(0.5, 3.25, "lower"),
                    Triple(0.5, 3.75, "lower"), Triple(0.5, 3.5, "equal"), Triple(0.25, 3.5, "higher"),
                    Triple(0.75, 3.5, "higher"), Triple(0.5, 3.5 - 1e-12, "lower"),
                    Triple(0.0, 3.25, "integral"), Triple(1.0, 3.75, "integral"),
                    Triple(0.5, Double.NaN, "invalid"), Triple(0.5, 3.00000001, "invalid"),
                    Triple(0.5, 3.0, "invalid"))) {
                    for (constant in listOf(0L, 1L shl 60, -(1L shl 60))) {
                        val costs = ArrayList<Long>()
                        for (apply in listOf(false, true)) {
                            val events = ArrayList<String>()
                            var work = 0L
                            val hints = LpHints(if (booleanBaseline) 1 else 2, if (booleanBaseline) 1 else 0,
                                directionalGains = true, applyAdvice = apply,
                                observe = events::add, charge = { work += it })
                            val relaxation = LpRelaxation(model, intArrayOf(0, proposal.varId),
                                booleanArrayOf(booleanBaseline, false), constant,
                                if (booleanBaseline) intArrayOf(1) else intArrayOf(0, 1),
                                if (booleanBaseline) intArrayOf(0) else IntArray(0))
                            val cutoff = constant.toDouble() + 1024.0
                            for (variable in listOf(baseline, proposal)) {
                                if (variable == baseline && reference == "unmeasured") continue
                                hints.beginNode(0, cutoff)
                                hints.record(relaxation, doubleArrayOf(0.5, 3.5), DoubleArray(0))
                                hints.recordObjective(relaxation, 0.0)
                                hints.branch(variable)
                                repeat(2) {
                                    for (up in listOf(false, true)) {
                                        val decision = if (variable is VarRef.Bool) {
                                            SearchDecision.Bool(if (up) 0 else 1)
                                        } else {
                                            val lower = if (variable == baseline) 0L else 3L
                                            if (up) SearchDecision.IntAtLeast(variable.varId, lower + 1L)
                                            else SearchDecision.IntAtMost(variable.varId, lower)
                                        }
                                        hints.commit(decision, 1)
                                        hints.beginNode(1, cutoff)
                                        hints.record(relaxation, doubleArrayOf(0.5, 3.5), DoubleArray(0))
                                        hints.recordObjective(relaxation, if (variable == proposal) 2.0
                                        else if (reference == "known-zero") 0.0 else 0.5)
                                        hints.recordOutcome(false, relaxation)
                                        hints.finishNode(trail.state)
                                    }
                                }
                            }
                            hints.beginNode(0, cutoff)
                            hints.record(relaxation, doubleArrayOf(baselinePoint, proposedPoint), DoubleArray(0))
                            hints.recordObjective(relaxation, 0.0)
                            events.clear()
                            val before = work

                            val selected = hints.pickVariable(baseline, { true }, ExecutionControl.Never)
                            hints.branch(selected)
                            val alternatives = if (selected is VarRef.Bool) {
                                listOf(SearchDecision.Bool(0), SearchDecision.Bool(1))
                            } else splitIntAlternatives(session, selected as VarRef.IntVar,
                                floor(if (selected == proposal) proposedPoint else baselinePoint).toLong())
                            val ordered = hints.orderAlternatives(selected, alternatives, ExecutionControl.Never)
                            hints.commit(ordered.first(), 1)

                            val decline = reference == "unmeasured" && context == "lower"
                            val eligible = context != "invalid" && !decline
                            val diagnostic = "$booleanBaseline/$reference/$baselinePoint/$proposedPoint/$constant"
                            assertEquals(if (apply && eligible) proposal else baseline, selected, diagnostic)
                            val event = if (apply) "PASSIVE_VARIABLE_COMMIT_CHANGED"
                            else "PASSIVE_VARIABLE_COMMIT_WITHHELD_CHANGED"
                            assertEquals(if (eligible) 1 else 0, events.count { it == event }, diagnostic)
                            assertEquals(if (decline) 1 else 0,
                                events.count { it == "PASSIVE_VARIABLE_FRACTIONALITY_DECLINED" }, diagnostic)
                            costs.add(work - before)
                        }
                        assertTrue(costs.first() > 0L)
                        assertEquals(costs.first(), costs.last())
                    }
                }
            }
        }
    }

    @Test
    fun `evidence priority preserves numeric fallbacks and requires a positive live candidate`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 10, cost = 1L) }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
            booleanArrayOf(false, false), 0L, intArrayOf(0, 1), IntArray(0))
        val session = PropagationSession(Problem(0, 2, Array(2) { IntDomain(0, 10) }, emptyArray()))
        for (context in listOf("unknown", "single-baseline", "unstable", "expired", "zero-baseline",
            "single-proposal", "fixed", "missing", "near", "underflow", "gap", "zero-candidate",
            "candidate-context", "baseline-context")) {
            val costs = ArrayList<Long>()
            for (apply in listOf(false, true)) {
                var work = 0L
                val events = ArrayList<String>()
                val hints = LpHints(2, 0, directionalGains = true, applyAdvice = apply,
                    observe = events::add, charge = { work += it })
                for (id in 0..1) {
                    if (id == 1 && context == "expired") repeat(257) { hints.beginNode(0, 1000.0) }
                    val samples = if (id == 0) when (context) {
                        "single-baseline" -> 1
                        "unstable", "expired", "zero-baseline", "underflow", "baseline-context" -> 2
                        else -> 0
                    } else if (context == "single-proposal") 1 else if (context == "zero-candidate") 4 else 2
                    val parent = if ((id == 0 && context == "baseline-context") ||
                        (id == 1 && context == "candidate-context")) -3000.0 else 0.0
                    hints.beginNode(0, 1000.0)
                    hints.record(relaxation, doubleArrayOf(0.5, 3.5), DoubleArray(0))
                    hints.recordObjective(relaxation, parent)
                    hints.recordOutcome(false, relaxation)
                    hints.finishNode(trail.state)
                    hints.branch(VarRef.IntVar(id))
                    repeat(samples) { sample ->
                        for (up in listOf(false, true)) {
                            val lower = if (id == 0) 0L else 3L
                            hints.commit(if (up) SearchDecision.IntAtLeast(id, lower + 1L)
                            else SearchDecision.IntAtMost(id, lower), 1)
                            hints.beginNode(1, 1000.0)
                            hints.record(relaxation, doubleArrayOf(0.5, 3.5), DoubleArray(0))
                            val objective = when {
                                id == 0 && context == "underflow" -> Double.MIN_VALUE
                                id == 0 && context == "zero-baseline" -> 0.0
                                id == 0 && context == "baseline-context" -> 1.0
                                id == 0 && context == "unstable" -> if (sample == 0) 1.0 else 4.0
                                id == 1 && context == "zero-candidate" && sample >= 2 -> 0.0
                                else -> 2.0
                            }
                            hints.recordObjective(relaxation, parent + objective)
                            hints.recordOutcome(false, relaxation)
                            hints.finishNode(trail.state)
                        }
                    }
                }
                val point = when (context) {
                    "missing" -> Double.NaN
                    "near" -> 0.00000001
                    "underflow" -> 0.000002
                    else -> 0.5
                }
                hints.beginNode(0, 1000.0)
                hints.record(relaxation, doubleArrayOf(point, 3.5), DoubleArray(0))
                hints.recordObjective(relaxation, if (context == "gap") 1000.0 else 0.0)
                hints.recordOutcome(false, relaxation)
                hints.finishNode(trail.state)
                events.clear()
                val before = work

                val selected = hints.pickVariable(VarRef.IntVar(0), { it.varId == 0 || context != "fixed" },
                    ExecutionControl.Never)
                hints.branch(selected)
                val alternatives = splitIntAlternatives(session, selected as VarRef.IntVar,
                    if (selected.varId == 1) 3L else point.toLong())
                val ordered = hints.orderAlternatives(selected, alternatives, ExecutionControl.Never)
                hints.commit(ordered.first(), 1)

                val eligible = context in listOf("unknown", "single-baseline", "unstable", "expired", "zero-baseline")
                assertEquals(VarRef.IntVar(if (apply && eligible) 1 else 0), selected, context)
                val event = if (apply) "PASSIVE_VARIABLE_COMMIT_CHANGED"
                else "PASSIVE_VARIABLE_COMMIT_WITHHELD_CHANGED"
                assertEquals(if (eligible) 1 else 0, events.count { it == event }, context)
                if (context == "zero-baseline" || context == "baseline-context") {
                    assertFalse("PASSIVE_VARIABLE_UNMEASURED_BASELINE" in events)
                }
                costs.add(work - before)
            }
            assertEquals(costs.first(), costs.last(), context)
        }
    }

    @Test
    fun `reliable fractional variables can replace integral or unmeasured current LP baselines`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 10, cost = 1L) }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(trail.state.ownerWorkingModel())
        for (booleanBaseline in listOf(false, true)) {
            val baseline = if (booleanBaseline) VarRef.Bool(0) else VarRef.IntVar(0)
            val proposal = VarRef.IntVar(if (booleanBaseline) 0 else 1)
            val domains = if (booleanBaseline) arrayOf(IntDomain(0, 10))
            else arrayOf(IntDomain(0, 2), IntDomain(0, 10))
            val session =
                PropagationSession(Problem(if (booleanBaseline) 1 else 0, domains.size, domains, emptyArray()))
            for ((baselinePoint, objective, samples) in listOf(Triple(0.0, 0.0, 2), Triple(1.0, 0.0, 2),
                Triple(2.0, 0.0, 2), Triple(0.5, 0.0, 2), Triple(1e-8, 0.0, 2),
                Triple(Double.NaN, 0.0, 2), Triple(Double.POSITIVE_INFINITY, 0.0, 2),
                Triple(Long.MAX_VALUE.toDouble(), 0.0, 2), Triple(0.0, 1024.0, 2),
                Triple(0.0, 1025.0, 2), Triple(0.0, 0.0, 1))) {
                val costs = ArrayList<Long>()
                for (constant in listOf(0L, 1L shl 60, -(1L shl 60))) {
                    val relaxation = LpRelaxation(model, intArrayOf(0, proposal.varId),
                        booleanArrayOf(booleanBaseline, false), constant,
                        if (booleanBaseline) intArrayOf(1) else intArrayOf(0, 1),
                        if (booleanBaseline) intArrayOf(0) else IntArray(0))
                    val cutoff = constant.toDouble() + 1024.0
                    for (apply in listOf(false, true)) {
                        var work = 0L
                        val events = ArrayList<String>()
                        val hints = LpHints(if (booleanBaseline) 1 else 2, if (booleanBaseline) 1 else 0,
                            directionalGains = true, applyAdvice = apply, observe = events::add,
                            charge = { work += it })
                        hints.beginNode(0, cutoff)
                        hints.record(relaxation, doubleArrayOf(baselinePoint, 3.5), DoubleArray(0))
                        hints.recordObjective(relaxation, 0.0)
                        hints.recordOutcome(false, relaxation)
                        hints.finishNode(trail.state)
                        hints.branch(proposal)
                        repeat(samples) {
                            for (up in listOf(false, true)) {
                                val decision = if (up) SearchDecision.IntAtLeast(proposal.varId, 4L)
                                else SearchDecision.IntAtMost(proposal.varId, 3L)
                                hints.commit(decision, 1)
                                hints.beginNode(1, cutoff)
                                hints.record(relaxation, doubleArrayOf(baselinePoint, 3.5), DoubleArray(0))
                                hints.recordObjective(relaxation, 2.0)
                                hints.recordOutcome(false, relaxation)
                                hints.finishNode(trail.state)
                            }
                        }
                        hints.beginNode(0, cutoff)
                        hints.record(relaxation, doubleArrayOf(baselinePoint, 3.5), DoubleArray(0))
                        hints.recordObjective(relaxation, objective)
                        hints.recordOutcome(false, relaxation)
                        hints.finishNode(trail.state)
                        events.clear()
                        val before = work

                        val selected = hints.pickVariable(baseline, { true }, ExecutionControl.Never)
                        hints.branch(selected)
                        val alternatives = if (selected is VarRef.Bool) {
                            listOf(SearchDecision.Bool(0), SearchDecision.Bool(1))
                        } else splitIntAlternatives(session, selected as VarRef.IntVar,
                            if (selected == proposal) 3L else baselinePoint.toLong())
                        val ordered = hints.orderAlternatives(selected, alternatives, ExecutionControl.Never)
                        hints.commit(ordered.first(), 1)

                        val integral = baselinePoint == 0.0 || baselinePoint == 1.0 ||
                            (!booleanBaseline && baselinePoint == 2.0)
                        val eligible = (integral || baselinePoint == 0.5) && objective < 1024.0 && samples == 2
                        assertEquals(if (apply && eligible) proposal else baseline, selected)
                        val event = if (apply) "PASSIVE_VARIABLE_COMMIT_CHANGED"
                        else "PASSIVE_VARIABLE_COMMIT_WITHHELD_CHANGED"
                        assertEquals(if (eligible) 1 else 0, events.count { it == event })
                        costs.add(work - before)
                    }
                }
                assertTrue(costs.all { it == costs.first() })
            }
        }
    }

    @Test
    fun `separated gain ranges reach matched applied and withheld variable commitments`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 10, cost = 1L) }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(trail.state.ownerWorkingModel())
        for ((baselineDown, baselineUp, proposedGain) in listOf(Triple(0.0, 0.0, 2048.0),
            Triple(256.0, 256.0, 2048.0), Triple(256.0, 256.0, 512.0), Triple(2048.0, 2048.0, 4096.0),
            Triple(256.0, 256.0, 256.0), Triple(512.0, 512.0, 256.0), Triple(2.0, 100.0, 9.0))) {
            for (baselineScale in listOf(1.0, 2.0)) {
                for (proposedScale in listOf(1.0, 2.0)) {
                    val costs = ArrayList<Long>()
                    for (constant in listOf(0L, 1L shl 60, -(1L shl 60))) {
                        val relaxation = LpRelaxation(model, intArrayOf(0, 1), booleanArrayOf(false, false),
                            constant, intArrayOf(0, 1), IntArray(0))
                        val cutoff = constant.toDouble() + 1024.0
                        for (apply in listOf(false, true)) {
                            var work = 0L
                            val events = ArrayList<String>()
                            val hints = LpHints(2, 0, directionalGains = true, applyAdvice = apply,
                                observe = events::add, charge = { work += it })
                            for (variable in listOf(VarRef.IntVar(0), VarRef.IntVar(1))) {
                                hints.beginNode(0, cutoff)
                                hints.record(relaxation, doubleArrayOf(2.5, 3.5), DoubleArray(0))
                                hints.recordObjective(relaxation, 0.0)
                                hints.recordOutcome(false, relaxation)
                                hints.finishNode(trail.state)
                                hints.branch(variable)
                                repeat(2) { sample ->
                                    for (up in listOf(false, true)) {
                                        val lower = if (variable.varId == 0) 2L else 3L
                                        val decision = if (up) SearchDecision.IntAtLeast(variable.varId, lower + 1L)
                                        else SearchDecision.IntAtMost(variable.varId, lower)
                                        hints.commit(decision, 1)
                                        hints.beginNode(1, cutoff)
                                        hints.record(relaxation, doubleArrayOf(2.5, 3.5), DoubleArray(0))
                                        val objective = if (variable.varId != 0) proposedGain else if (up) baselineUp
                                        else baselineDown
                                        val scale = if (sample > 0) 1.0 else if (variable.varId == 0) baselineScale
                                        else proposedScale
                                        hints.recordObjective(relaxation, objective * scale)
                                        hints.recordOutcome(false, relaxation)
                                        hints.finishNode(trail.state)
                                    }
                                }
                            }
                            hints.beginNode(0, cutoff)
                            hints.record(relaxation, doubleArrayOf(2.5, 3.5), DoubleArray(0))
                            hints.recordObjective(relaxation, 0.0)
                            hints.recordOutcome(false, relaxation)
                            hints.finishNode(trail.state)
                            events.clear()
                            val before = work

                            val selected = hints.pickVariable(VarRef.IntVar(0), { true }, ExecutionControl.Never)
                            hints.branch(selected)
                            val lower = if (selected.varId == 0) 2L else 3L
                            val alternatives = listOf(SearchDecision.IntAtMost(selected.varId, lower),
                                SearchDecision.IntAtLeast(selected.varId, lower + 1L))
                            val ordered = hints.orderAlternatives(selected, alternatives, ExecutionControl.Never)
                            hints.commit(ordered.first(), 1)

                            val changed = proposedGain > minOf(baselineDown, baselineUp) * baselineScale
                            assertEquals(VarRef.IntVar(if (apply && changed) 1 else 0), selected)
                            val event = if (apply) "PASSIVE_VARIABLE_COMMIT_CHANGED"
                            else "PASSIVE_VARIABLE_COMMIT_WITHHELD_CHANGED"
                            assertEquals(if (changed) 1 else 0, events.count { it == event })
                            costs.add(work - before)
                        }
                    }
                    assertTrue(costs.first() > 0L)
                    assertTrue(costs.all { it == costs.first() })
                }
            }
        }
    }

    @Test
    fun `reset or cancellation at charged variable selection stages cannot attribute advice`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 10, cost = 1L) }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
            booleanArrayOf(false, false), 0L, intArrayOf(0, 1), IntArray(0))
        for (reset in listOf(false, true)) {
            for ((reference, stopAt) in listOf("integral", "measured", "unmeasured").flatMap { reference ->
                (1..if (reference == "integral") 16 else 22)
                    .map { reference to it }
            }) {
                val events = ArrayList<String>()
                var reading = false
                var reads = 0
                var fresh = true
                lateinit var hints: LpHints
                hints = LpHints(2, 0, directionalGains = true, observe = events::add, charge = {
                    if (reading && ++reads == stopAt) {
                        reading = false
                        if (reset) hints.resetHistory() else fresh = false
                    }
                })
                for (variable in listOf(VarRef.IntVar(0), VarRef.IntVar(1))) {
                    if (reference == "unmeasured" && variable.varId == 0) continue
                    hints.beginNode(0, 3.0)
                    hints.record(relaxation, doubleArrayOf(2.5, 3.5), DoubleArray(0))
                    hints.recordObjective(relaxation, 0.0)
                    hints.recordOutcome(false, relaxation)
                    hints.finishNode(trail.state)
                    hints.branch(variable)
                    repeat(2) {
                        for (up in listOf(false, true)) {
                            val lower = if (variable.varId == 0) 2L else 3L
                            val decision = if (up) SearchDecision.IntAtLeast(variable.varId, lower + 1L)
                            else SearchDecision.IntAtMost(variable.varId, lower)
                            hints.commit(decision, 1)
                            hints.beginNode(1, 3.0)
                            hints.record(relaxation, doubleArrayOf(2.5, 3.5), DoubleArray(0))
                            hints.recordObjective(relaxation, if (variable.varId == 0) 0.0 else 5.0)
                            hints.recordOutcome(false, relaxation)
                            hints.finishNode(trail.state)
                        }
                    }
                }
                hints.beginNode(0, 3.0)
                hints.record(relaxation, doubleArrayOf(if (reference == "integral") 2.0 else 2.5, 3.5), DoubleArray(0))
                hints.recordObjective(relaxation, 0.0)
                hints.recordOutcome(false, relaxation)
                hints.finishNode(trail.state)
                events.clear()
                reading = true
                val control = ExecutionControl { !fresh }

                val selected = hints.pickVariable(VarRef.IntVar(0), { true }, control)
                hints.branch(selected)
                val lower = if (selected.varId == 0) 2L else 3L
                val alternatives = listOf(SearchDecision.IntAtMost(selected.varId, lower),
                    SearchDecision.IntAtLeast(selected.varId, lower + 1L))
                val ordered = hints.orderAlternatives(selected, alternatives, control)
                hints.commit(ordered.first(), 1, control)

                assertFalse("PASSIVE_VARIABLE_COMMIT_CHANGED" in events, "$reset/$reference/$stopAt: $events")
                assertTrue(reads >= stopAt, "$reset/$reference/$stopAt")
            }
        }
    }

    @Test
    fun `child gain ranges reach matched applied and withheld commitments`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(trail.state.ownerWorkingModel())
        val variable = VarRef.IntVar(0)
        val down = SearchDecision.IntAtMost(0, 2L)
        val up = SearchDecision.IntAtLeast(0, 3L)
        val costs = HashMap<Triple<Double, Double, Double>, MutableList<Long>>()
        for (constant in listOf(0L, 1L shl 60, -(1L shl 60))) {
            val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false),
                constant, intArrayOf(0), IntArray(0))
            val cutoff = constant.toDouble() + 1024.0
            for ((apply, currentObjective) in listOf(false to 10.0, true to 10.0, false to 1023.0, true to 1023.0)) {
                for ((scale, gapScale, equalRates) in listOf(1.0 to 1.0, 1.5 to 1.0, 2.0 to 1.0,
                    1.0 to 0.5, 1.0 to 2.0, 1.0 to 4.0, 1.0 to 0.25).flatMap { (scale, gapScale) ->
                    listOf(Triple(scale, gapScale, false), Triple(scale, gapScale, true))
                }) {
                    var work = 0L
                    val events = ArrayList<String>()
                    val hints = LpHints(1, 0, directionalGains = true, applyAdvice = apply,
                        observe = events::add, charge = { work += it })
                    val parentObjective = 1024.0 - (1024.0 - currentObjective) * gapScale
                    repeat(2) { sample ->
                        hints.beginNode(0, cutoff)
                        hints.record(relaxation, doubleArrayOf(2.75), DoubleArray(0))
                        hints.recordObjective(relaxation, parentObjective)
                        hints.recordOutcome(false, relaxation)
                        hints.finishNode(trail.state)
                        hints.branch(variable)
                        val upObjective = parentObjective + if (sample == 0) scale else 1.0
                        for ((decision, point, objective) in listOf(Triple(down, 2.0, parentObjective + 1.5),
                            Triple(up, 3.0, upObjective))) {
                            hints.commit(decision, 1)
                            hints.beginNode(1, cutoff)
                            hints.record(relaxation, doubleArrayOf(point), DoubleArray(0))
                            hints.recordObjective(relaxation, objective)
                            hints.recordOutcome(equalRates || (decision == down && sample == 0), relaxation)
                            hints.finishNode(trail.state)
                        }
                    }
                    hints.beginNode(0, cutoff)
                    hints.record(relaxation, doubleArrayOf(2.75), DoubleArray(0))
                    hints.recordObjective(relaxation, currentObjective)
                    hints.recordOutcome(false, relaxation)
                    hints.finishNode(trail.state)
                    hints.branch(variable)
                    val before = work

                    val ordinary = if (currentObjective == 1023.0) listOf(up, down) else listOf(down, up)
                    val ordered = hints.orderAlternatives(variable, ordinary, ExecutionControl.Never)
                    hints.commit(ordered.first(), 1)

                    val changed = gapScale in 0.5..2.0 && (currentObjective == 1023.0 || scale < 1.5)
                    assertEquals(if (apply && changed) ordinary.reversed() else ordinary, ordered)
                    val event = if (apply) "PASSIVE_VALUE_COMMIT_CHANGED" else "PASSIVE_VALUE_COMMIT_WITHHELD_CHANGED"
                    assertEquals(if (changed) 1 else 0, events.count { it == event })
                    assertFalse("PASSIVE_VARIABLE_SCORE" in events)
                    assertFalse("PASSIVE_VARIABLE_COMMIT_CHANGED" in events)
                    costs.getOrPut(Triple(currentObjective, scale, gapScale)) { ArrayList() }.add(work - before)
                }
            }
        }
        for (caseCosts in costs.values) {
            assertTrue(caseCosts.first() > 0L)
            assertTrue(caseCosts.all { it == caseCosts.first() })
        }
    }

    @Test
    fun `cancelled final publication discards a completed outcome`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
        val events = ArrayList<String>()
        val hints = LpHints(1, 0, directionalGains = true, observe = events::add)
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(2.5), DoubleArray(0))
        hints.recordObjective(relaxation, 10.0)
        hints.recordOutcome(false, relaxation)
        hints.finishNode(trail.state)
        hints.branch(VarRef.IntVar(0))
        hints.commit(SearchDecision.IntAtMost(0, 2L), 1)
        hints.beginNode(1, 100.0)
        hints.recordOutcome(true, relaxation)

        hints.finishNode(trail.state, ExecutionControl { true })
        hints.finishNode(trail.state)

        assertFalse("OUTCOME_PRUNED" in events)
    }

    @Test
    fun `completed outcomes with missing or changed scope discard retained history`() {
        for (change in listOf("missing", "mapping", "constant", "cutoff")) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1L)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val model = assertNotNull(trail.state.ownerWorkingModel())
            val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false),
                0L, intArrayOf(0), IntArray(0))
            val variable = VarRef.IntVar(0)
            val events = ArrayList<String>()
            val hints = LpHints(1, 0, directionalGains = true, observe = events::add)
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.5), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.recordOutcome(false, relaxation)
            hints.finishNode(relaxation.model.exactState)
            hints.branch(variable)
            repeat(2) {
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3L) else SearchDecision.IntAtMost(0, 2L), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(if (up) 3.0 else 2.0), DoubleArray(0))
                    hints.recordObjective(relaxation, 12.0)
                    hints.recordOutcome(false, relaxation)
                    hints.finishNode(relaxation.model.exactState)
                }
            }
            hints.commit(SearchDecision.IntAtMost(0, 2L), 1)
            hints.beginNode(1, if (change == "cutoff") 99.0 else 100.0)
            val changed = when (change) {
                "missing" -> null
                "mapping" -> LpRelaxation(model, intArrayOf(1), booleanArrayOf(false),
                    0L, intArrayOf(0), IntArray(0))
                "constant" -> LpRelaxation(model, intArrayOf(0), booleanArrayOf(false),
                    1L, intArrayOf(0), IntArray(0))
                else -> relaxation
            }

            hints.recordOutcome(true, changed)
            hints.finishNode(relaxation.model.exactState)
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.5), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.recordOutcome(false, relaxation)
            hints.finishNode(relaxation.model.exactState)

            assertTrue(hints.directionalScore(variable).isNaN())
            assertEquals(1, events.count { it == "RESET" })
            assertFalse("OUTCOME_PRUNED" in events)
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
                hints.recordOutcome(false, relaxation)
                hints.finishNode(relaxation.model.exactState)
                hints.branch(variable)
                for ((decision, point, objective) in listOf(Triple(down, 2.0, 11.0), Triple(up, 3.0, 13.0))) {
                    hints.commit(decision, 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(point), doubleArrayOf(0.0))
                    hints.recordObjective(relaxation, objective)
                    hints.recordOutcome(false, relaxation)
                    hints.finishNode(relaxation.model.exactState)
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), doubleArrayOf(0.0))
            hints.recordObjective(relaxation, 10.0)
            hints.recordOutcome(false, relaxation)
            hints.finishNode(relaxation.model.exactState)
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
            hints.recordOutcome(false, current)
            hints.finishNode(current.model.exactState)

            if (change == "none") assertEquals(sqrt(3.0), hints.directionalScore(variable), 1e-9)
            else assertTrue(hints.directionalScore(variable).isNaN())
            assertFalse("PASSIVE_VARIABLE_COMMITTED" in events)
            assertEquals(2, events.count { it == "DOWN" })
            assertEquals(2, events.count { it == "UP" })
        }
    }

    @Test
    fun `cancellation discards a reliable child commitment`() {
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
                hints.recordOutcome(false, relaxation)
                hints.finishNode(relaxation.model.exactState)
                hints.branch(variable)
                for ((decision, point, objective) in listOf(Triple(down, 2.0, 13.0), Triple(up, 3.0, 11.0))) {
                    hints.commit(decision, 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(point), DoubleArray(0))
                    hints.recordObjective(relaxation, objective)
                    hints.recordOutcome(decision == down, relaxation)
                    hints.finishNode(relaxation.model.exactState)
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.75), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.recordOutcome(false, relaxation)
            hints.finishNode(relaxation.model.exactState)
            hints.finishNode(trail.state)
            hints.branch(variable)

            val ordered = hints.orderAlternatives(variable, listOf(down, up), ExecutionControl.Never)
            hints.commit(ordered.first(), 1, ExecutionControl { true })

            assertTrue("PASSIVE_VALUE_COMPARED" in events)
            assertFalse("PASSIVE_VALUE_COMMITTED" in events)
            assertFalse("PASSIVE_VALUE_COMMIT_WITHHELD" in events)
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
            hints.recordOutcome(false, relaxation)
            hints.finishNode(relaxation.model.exactState)
            val rebound = relaxation.withModel(assertNotNull(trail.state.ownerWorkingModel()))
            val before = work

            hints.recordObjective(rebound, 10.0)
            hints.recordOutcome(false, rebound)
            hints.finishNode(rebound.model.exactState)

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
            hints.recordOutcome(false, relaxation)
            hints.finishNode(relaxation.model.exactState)
            val replacement = LpRelaxation(model, relaxation.colVarId.copyOf(), relaxation.colIsBool.copyOf(),
                0L, relaxation.intColOf, relaxation.boolColOf)
            val before = work

            hints.recordObjective(replacement, 10.0)
            hints.recordOutcome(false, replacement)
            hints.finishNode(replacement.model.exactState)

            costs.add(work - before)
        }
        assertTrue(costs.last() - costs.first() >= 2L * (256 - 1))
    }

    @Test
    fun `reliable child history leaves variable advice isolated`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
        val variable = VarRef.IntVar(0)
        val down = SearchDecision.IntAtMost(0, 2)
        val up = SearchDecision.IntAtLeast(0, 3)
        val costs = ArrayList<Long>()
        for (apply in listOf(false, true)) {
            var work = 0L
            val events = ArrayList<String>()
            val hints = LpHints(1, 0, directionalGains = true, applyAdvice = apply,
                observe = events::add, charge = { work += it })
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.75), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.recordOutcome(false, relaxation)
                hints.finishNode(relaxation.model.exactState)
                hints.branch(variable)
                for ((decision, point, objective) in listOf(Triple(down, 2.0, 13.0), Triple(up, 3.0, 10.0))) {
                    hints.commit(decision, 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(point), DoubleArray(0))
                    hints.recordObjective(relaxation, objective)
                    hints.recordOutcome(false, relaxation)
                    hints.finishNode(relaxation.model.exactState)
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.75), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.recordOutcome(false, relaxation)
            hints.finishNode(relaxation.model.exactState)
            hints.finishNode(trail.state)
            val before = work
            hints.branch(variable)

            val selected = hints.orderAlternatives(variable, listOf(down, up), ExecutionControl.Never)
            hints.commit(selected.first(), 1)

            assertEquals(listOf(down, up), selected)
            assertTrue(events.none { it.startsWith("PASSIVE_VALUE_") })
            assertFalse("PASSIVE_COLLECTION_COMMIT_CHANGED" in events)
            costs.add(work - before)
        }
        assertEquals(costs.first(), costs.last())
    }

    @Test
    fun `child selection withholds advice from an unmatched current point`() {
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
            hints.recordOutcome(false, relaxation)
            hints.finishNode(relaxation.model.exactState)
            hints.branch(proposal)
            for (up in listOf(false, true)) {
                hints.commit(if (up) SearchDecision.IntAtLeast(1, 4L) else SearchDecision.IntAtMost(1, 3L), 1)
                hints.beginNode(1, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25, if (up) 4.0 else 3.0), DoubleArray(0))
                hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                hints.recordOutcome(up, relaxation)
                hints.finishNode(relaxation.model.exactState)
            }
        }
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(2.25, 3.25), DoubleArray(0))
        hints.recordObjective(relaxation, 10.0)
        val ordinary = listOf(SearchDecision.IntAtLeast(1, 4L), SearchDecision.IntAtMost(1, 3L))
        assertEquals(ordinary.reversed(), hints.orderAlternatives(proposal, ordinary, ExecutionControl.Never))
        events.clear()
        hints.recordObjective(LpRelaxation(model, intArrayOf(0, 1), booleanArrayOf(false, false),
            0L, intArrayOf(0, 1), IntArray(0)), 10.0)

        val selected = hints.orderAlternatives(proposal, ordinary, ExecutionControl.Never)
        hints.branch(proposal)
        hints.commit(selected.first(), 1)

        assertEquals(ordinary, selected)
        assertTrue(hints.directionalScore(proposal).isNaN())
        assertFalse("PASSIVE_VALUE_COMPARED" in events)
        assertFalse("PASSIVE_VALUE_COMMITTED" in events)
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
                    hints.recordOutcome(false, relaxation)
                    hints.finishNode(relaxation.model.exactState)
                    hints.branch(variable)
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(if (up) 3.0 else 2.0), DoubleArray(0))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                    hints.recordOutcome(false, relaxation)
                    hints.finishNode(relaxation.model.exactState)
                }
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.recordOutcome(false, relaxation)
                hints.finishNode(relaxation.model.exactState)
                val before = work

                val score = if (primitive) hints.directionalScore(variable.varId, isBool = false)
                else hints.directionalScore(variable)

                if (phase == 0) {
                    assertTrue(score.isNaN())
                    assertEquals(before, work)
                } else {
                    assertEquals(sqrt(3.0), score, 1e-9)
                    assertEquals(before + 3L, work)
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
        hints.recordOutcome(false, relaxation)
        hints.finishNode(relaxation.model.exactState)
        hints.branch(VarRef.IntVar(0))
        hints.commit(SearchDecision.IntAtMost(0, 2), 1)
        hints.beginNode(1, 100.0)
        hints.record(relaxation, doubleArrayOf(2.0), DoubleArray(0))
        hints.recordObjective(relaxation, 11.0)
        hints.recordOutcome(false, relaxation)
        hints.finishNode(relaxation.model.exactState)
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
            hints.recordOutcome(false, parent)
            hints.finishNode(parent.model.exactState)
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
            hints.recordOutcome(false, child)
            hints.finishNode(child.model.exactState)

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
                hints.recordOutcome(false, relaxation)
                hints.finishNode(relaxation.model.exactState)
                hints.branch(VarRef.IntVar(0))
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(relaxation.model.m))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                    hints.recordOutcome(false, relaxation)
                    hints.finishNode(relaxation.model.exactState)
                }
            }
            assertTrue(hints.directionalScore(VarRef.IntVar(0)).isFinite())

            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(relaxation.model.m))

            if (change != "missing") {
                hints.recordObjective(relaxation, 10.0)
                hints.recordOutcome(false, relaxation)
                hints.finishNode(relaxation.model.exactState)
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
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordObjective(relaxation, 10.0)
                hints.recordOutcome(false, relaxation)
                hints.finishNode(relaxation.model.exactState)
                hints.branch(variable)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(if (up) 3.0 else 2.0), DoubleArray(0))
                    hints.recordObjective(relaxation, if (up) 13.0 else 11.0)
                    hints.recordOutcome(false, relaxation)
                    hints.finishNode(relaxation.model.exactState)
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            hints.recordOutcome(false, relaxation)
            hints.finishNode(relaxation.model.exactState)

            assertEquals(sqrt(3.0), hints.directionalScore(variable), 1e-9, "constant=$constant")
        }
    }
}
