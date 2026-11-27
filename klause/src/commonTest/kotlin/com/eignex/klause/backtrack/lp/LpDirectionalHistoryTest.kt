package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.ExecutionControl
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpDirectionalHistoryTest {
    @Test
    fun `depth retention recovers complete own evidence after another depth overwrites it`() {
        for (boolean in listOf(false, true)) {
            for (context in listOf("complete", "missing-numeric", "missing-outcome", "unstable", "aged",
                "numeric-gap", "outcome-gap", "fraction", "cutoff")) {
                val history = LpDirectionalHistory({}, depthRetention = true)
                val variable = if (boolean) VarRef.Bool(0) else VarRef.IntVar(0)
                val offset = if (boolean) 0.0 else 2.0
                for (level in 0..1) {
                    for (category in 0..1) {
                        repeat(2) { sample ->
                            for (up in listOf(false, true)) {
                                val missing = level == 0 && sample == 0 && up
                                val parent = if (level == 0 && sample == 0 && !up &&
                                    ((category == 0 && context == "numeric-gap") ||
                                        (category == 1 && context == "outcome-gap"))) -40.0 else 0.0
                                history.beginNode(level)
                                history.record(parent)
                                history.branch(variable, offset + 0.5)
                                val decision = if (boolean) SearchDecision.Bool(if (up) 0 else 1)
                                else if (up) SearchDecision.IntAtLeast(0, 3L) else SearchDecision.IntAtMost(0, 2L)
                                history.commit(decision, level + 1)
                                history.beginNode(level + 1)
                                val gain = if (level == 0 && up && sample == 0 && context == "unstable") 9.0
                                else if (up) 3.0 else 1.0
                                history.record(if (category == 0 && !(missing && context == "missing-numeric"))
                                    parent + gain else Double.NaN)
                                history.finishNode(if (category == 1 && !(missing && context == "missing-outcome"))
                                    true else null)
                            }
                        }
                    }
                }
                if (context == "aged") repeat(257) { history.beginNode(0) }
                history.beginNode(0)
                history.record(0.0)
                val value = offset + if (context == "fraction") 0.0 else 0.5
                val cutoff = if (context == "cutoff") Double.POSITIVE_INFINITY else 10.0

                val global = history.preferDirection(variable, value, cutoff)
                val retained = history.depthPreference(variable, value, cutoff) { true }

                assertNull(global, "$boolean/$context")
                assertEquals(if (context == "complete") false else null, retained, "$boolean/$context")
            }
        }
        var work = 0L
        val disabled = LpDirectionalHistory({}, charge = { work += it })
        assertNull(disabled.depthPreference(VarRef.Bool(0), 0.5, 10.0) { true })
        assertEquals(0L, work)
    }

    @Test
    fun `depth retention keeps existing contexts after its lifetime allocation limit`() {
        val events = ArrayList<String>()
        val history = LpDirectionalHistory(events::add, depthRetention = true)
        for (index in 127 downTo 0) {
            val variable = if (index and 1 == 1) VarRef.Bool(index / 2) else VarRef.IntVar(index / 2)
            for (up in listOf(false, true)) {
                history.beginNode(0)
                history.record(0.0)
                history.branch(variable, 0.5)
                val decision = if (variable is VarRef.Bool) {
                    SearchDecision.Bool((variable.varId shl 1) or if (up) 0 else 1)
                } else if (up) SearchDecision.IntAtLeast(variable.varId, 1L)
                else SearchDecision.IntAtMost(variable.varId, 0L)
                history.commit(decision, 1)
                history.beginNode(1)
                history.record(if (up) 3.0 else 1.0)
                history.finishNode(true)
            }
        }
        for (variable in listOf(VarRef.Bool(0), VarRef.IntVar(0), VarRef.Bool(64))) {
            repeat(2) {
                for (up in listOf(false, true)) {
                    history.beginNode(0)
                    history.record(0.0)
                    history.branch(variable, 0.5)
                    val decision = if (variable is VarRef.Bool)
                        SearchDecision.Bool((variable.varId shl 1) or if (up) 0 else 1)
                    else if (up) SearchDecision.IntAtLeast(variable.varId, 1L)
                    else SearchDecision.IntAtMost(variable.varId, 0L)
                    history.commit(decision, 1)
                    history.beginNode(1)
                    history.record(if (up) 3.0 else 1.0)
                    history.finishNode(true)
                }
            }
        }
        history.restart()
        history.beginNode(0)
        history.record(0.0)

        assertEquals(false, history.depthPreference(VarRef.Bool(0), 0.5, 10.0) { true })
        assertEquals(false, history.depthPreference(VarRef.IntVar(0), 0.5, 10.0) { true })
        assertNull(history.depthPreference(VarRef.Bool(64), 0.5, 10.0) { true })
        assertEquals(128, events.count { it == "DEPTH_RETENTION_CONTEXT_RETAINED" })
        assertEquals(1, events.count { it == "DEPTH_RETENTION_CONTEXT_EXHAUSTED" })
        history.clear()
        repeat(2) {
            for (up in listOf(false, true)) {
                history.beginNode(0)
                history.record(0.0)
                history.branch(VarRef.Bool(0), 0.5)
                history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                history.beginNode(1)
                history.record(if (up) 3.0 else 1.0)
                history.finishNode(true)
            }
        }
        history.beginNode(0)
        history.record(0.0)
        assertNull(history.depthPreference(VarRef.Bool(0), 0.5, 10.0) { true })
        assertEquals(128, events.count { it == "DEPTH_RETENTION_CONTEXT_RETAINED" })
    }

    @Test
    fun `depth retention allocates only after fresh opposite numeric support at the same depth`() {
        for ((level, age, admits) in listOf(Triple(0, 0, true), Triple(1, 0, false), Triple(0, 257, false))) {
            val events = ArrayList<String>()
            val history = LpDirectionalHistory(events::add, depthRetention = true)
            val variable = VarRef.Bool(0)
            history.beginNode(0)
            history.record(0.0)
            history.branch(variable, 0.5)
            history.commit(SearchDecision.Bool(1), 1)
            history.beginNode(1)
            history.record(1.0)
            history.finishNode(true)
            assertFalse("DEPTH_RETENTION_CONTEXT_RETAINED" in events)
            repeat(age) { history.beginNode(0) }
            history.beginNode(level)
            history.record(0.0)
            history.branch(variable, 0.5)
            history.commit(SearchDecision.Bool(0), level + 1)
            history.beginNode(level + 1)

            history.record(3.0)

            assertEquals(admits, "DEPTH_RETENTION_CONTEXT_RETAINED" in events, "$level/$age")
        }
    }

    @Test
    fun `late depth admission uses earlier outcomes and needs distinct directional gains`() {
        val history = LpDirectionalHistory({}, depthRetention = true)
        val variable = VarRef.Bool(0)
        fun child(up: Boolean, objective: Double, outcome: Boolean?) {
            history.beginNode(0)
            history.record(0.0)
            history.branch(variable, 0.5)
            history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
            history.beginNode(1)
            history.record(objective)
            history.finishNode(outcome)
        }
        repeat(2) { child(up = false, objective = 1.0, outcome = true) }
        child(up = true, objective = 3.0, outcome = true)
        child(up = true, objective = Double.NaN, outcome = true)
        history.beginNode(0)
        history.record(0.0)

        assertNull(history.depthPreference(variable, 0.5, 10.0) { true })
        child(up = true, objective = 3.0, outcome = null)
        history.beginNode(0)
        history.record(0.0)

        assertEquals(false, history.depthPreference(variable, 0.5, 10.0) { true })
    }

    @Test
    fun `depth retention validates allocation publication and lookup after charged invalidation`() {
        for (action in listOf("numeric-reset", "numeric-cancel", "outcome-reset", "outcome-cancel",
            "lookup-reset", "lookup-cancel", "allocation-reset", "node-change")) {
            var armed = false
            var current = true
            lateinit var history: LpDirectionalHistory
            val cancellation = ExecutionControl { !current }
            history = LpDirectionalHistory({}, depthRetention = true, depthCancellation = cancellation, charge = { amount ->
                val target = when {
                    action.startsWith("lookup") -> 8L
                    action == "allocation-reset" -> 224L
                    else -> 12L
                }
                if (armed && amount == target) {
                    armed = false
                    when {
                        action.endsWith("cancel") -> current = false
                        action == "node-change" -> history.beginNode(3)
                        else -> history.clear()
                    }
                }
            })
            val variable = VarRef.Bool(0)
            repeat(2) {
                for (up in listOf(false, true)) {
                    history.beginNode(0)
                    history.record(0.0)
                    history.branch(variable, 0.5)
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(if (up) 3.0 else 1.0)
                    history.finishNode(true)
                }
            }
            if (action.startsWith("lookup")) {
                history.beginNode(0)
                history.record(0.0)
                armed = true
                assertNull(history.depthPreference(variable, 0.5, 10.0) { current })
            } else {
                val level = if (action == "allocation-reset") 1 else 0
                if (level == 1) {
                    history.beginNode(level)
                    history.record(0.0)
                    history.branch(variable, 0.5)
                    history.commit(SearchDecision.Bool(0), level + 1)
                    history.beginNode(level + 1)
                    history.record(3.0)
                    history.finishNode(true)
                }
                history.beginNode(level)
                history.record(0.0)
                history.branch(variable, 0.5)
                history.commit(SearchDecision.Bool(1), level + 1)
                history.beginNode(level + 1)
                armed = true
                history.record(if (action.startsWith("outcome")) Double.NaN else 1.0)
                history.finishNode(true) { current }
            }
            history.beginNode(0)
            history.record(0.0)

            val preferred = history.depthPreference(variable, 0.5, 10.0) { true }

            assertFalse(armed, action)
            assertEquals(if (action.endsWith("reset")) null else false, preferred, action)
        }
    }

    @Test
    fun `child advice requires the current depth for every numeric and outcome parent`() {
        for ((cutoff, preferred) in listOf(2.0 to true, 10.0 to false)) {
            for (boolean in listOf(false, true)) {
                val variable = if (boolean) VarRef.Bool(0) else VarRef.IntVar(0)
                val offset = if (boolean) 0.0 else 2.0
                for (slot in 0..7) {
                    for ((parentDepth, matched) in listOf(0 to false, 1 to false, 4 to true, 127 to false)) {
                        var work = 0L
                        val history = LpDirectionalHistory({}, charge = { work += it })
                        for (category in 0..1) {
                            repeat(2) { sample ->
                                for (up in listOf(false, true)) {
                                    val origin = category * 4 + sample * 2 + if (up) 1 else 0
                                    val level = if (origin == slot) parentDepth else 4
                                    history.beginNode(level)
                                    history.record(0.0)
                                    history.branch(variable, offset + 0.5)
                                    val decision = if (boolean) SearchDecision.Bool(if (up) 0 else 1)
                                    else if (up) SearchDecision.IntAtLeast(0, 3L)
                                    else SearchDecision.IntAtMost(0, 2L)
                                    history.commit(decision, level + 1)
                                    history.beginNode(level + 1)
                                    history.record(if (category == 0) (if (up) 3.0 else 1.0) else Double.NaN)
                                    history.finishNode(if (category == 1) false else null)
                                }
                            }
                        }
                        history.beginNode(4)
                        history.record(0.0)
                        val before = work

                        val actual = history.preferDirection(variable, offset + 0.5, cutoff)

                        assertEquals(if (matched) preferred else null, actual, "$boolean/$slot/$parentDepth/$cutoff")
                        assertEquals(if (preferred) 120L else 128L, work - before)
                        assertEquals(1.0, history.minimumPrediction(variable, offset + 0.5, cutoff))
                        history.beginNode(-1)
                        history.record(0.0)
                        assertNull(history.preferDirection(variable, offset + 0.5, cutoff))
                    }
                }
            }
        }
    }

    @Test
    fun `parent depth metadata cannot publish after reset or cancelled outcomes`() {
        for (action in listOf("numeric-reset", "outcome-reset", "outcome-cancel")) {
            var armed = false
            var current = true
            lateinit var history: LpDirectionalHistory
            history = LpDirectionalHistory({}, charge = { amount ->
                if (armed && amount == if (action == "numeric-reset") 6L else 15L) {
                    armed = false
                    if (action.endsWith("reset")) history.clear() else current = false
                }
            })
            val variable = VarRef.Bool(0)
            repeat(2) {
                for (up in listOf(false, true)) {
                    history.beginNode(0)
                    history.record(0.0)
                    history.branch(variable, 0.5)
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(if (up) 3.0 else 1.0)
                    history.finishNode(false)
                }
            }
            history.beginNode(1)
            history.record(0.0)
            history.branch(variable, 0.5)
            history.commit(SearchDecision.Bool(1), 2)
            history.beginNode(2)
            armed = true

            history.record(if (action == "numeric-reset") 1.0 else Double.NaN)
            history.finishNode(false) { current }
            history.beginNode(0)
            history.record(0.0)
            val preferred = history.preferDirection(variable, 0.5, 10.0)

            assertFalse(armed)
            assertEquals(if (action.endsWith("reset")) null else false, preferred, action)
        }
    }

    @Test
    fun `equal completed rates use numeric predictions with complete compatible context`() {
        for (rate in 0..2) {
            for (context in listOf("separate", "touch", "overlap", "prune", "tie", "numeric", "outcome",
                "unknown", "missing", "unequal", "zero", "underflow")) {
                val history = LpDirectionalHistory({})
                val variable = VarRef.Bool(0)
                repeat(2) { sample ->
                    for (up in listOf(false, true)) {
                        val parent = if (context == "numeric" && sample == 0 && !up) -30.0 else 0.0
                        val gain = when {
                            context == "zero" -> 0.0
                            context == "underflow" && !up -> Double.MIN_VALUE
                            context == "tie" -> 3.0
                            up && context == "touch" -> 2.0
                            up && context == "overlap" -> 1.5
                            up -> 3.0
                            sample == 0 && (context == "touch" || context == "overlap") -> 2.0
                            else -> 1.0
                        }
                        history.beginNode(0)
                        history.record(parent)
                        history.branch(variable, 0.5)
                        history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                        history.beginNode(1)
                        history.record(parent + gain)
                        history.finishNode()
                    }
                }
                repeat(if (context == "missing") 1 else 2) { sample ->
                    for (up in listOf(false, true)) {
                        val parent = if (context == "outcome" && sample == 0 && !up) -30.0 else 0.0
                        history.beginNode(0)
                        history.record(parent)
                        history.branch(variable, 0.5)
                        history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                        history.beginNode(1)
                        history.record(Double.NaN)
                        val pruned = if (context == "unequal" && up) rate == 0
                        else rate == 2 || (rate == 1 && sample == 1)
                        history.finishNode(pruned)
                    }
                }
                history.beginNode(0)
                history.record(0.0)
                val point = if (context == "underflow") 0.125 else 0.5
                val cutoff = when (context) {
                    "unknown" -> 0.0
                    "prune", "tie" -> 2.0
                    else -> 10.0
                }

                val preferred = history.preferDirection(variable, point, cutoff)

                val expected = when (context) {
                    "separate" -> false
                    "prune" -> true
                    "unequal" -> if (rate == 0) false else null
                    else -> null
                }
                assertEquals(expected, preferred, "$rate/$context")
            }
        }
    }

    @Test
    fun `minimum predictions require comparable gaps for every numeric parent`() {
        val contexts = listOf(1.0, 10.0).flatMap { cutoff ->
            listOf(-1.0, 0.0, 0.25, 0.5, 1.0, 2.0, 4.0).map { cutoff to it }
        } + (Double.MIN_VALUE to Double.POSITIVE_INFINITY)
        for ((cutoff, ratio) in contexts) {
            for (parentSlot in 0..3) {
                var work = 0L
                val history = LpDirectionalHistory({}, charge = { work += it })
                val variable = VarRef.Bool(0)
                repeat(2) { sample ->
                    for (up in listOf(false, true)) {
                        val slot = 2 * sample + if (up) 1 else 0
                        val parent = when {
                            slot != parentSlot -> 0.0
                            ratio.isInfinite() -> -1.0
                            else -> cutoff * (1.0 - ratio)
                        }
                        history.beginNode(0)
                        history.record(parent)
                        history.branch(variable, 0.5)
                        history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                        history.beginNode(1)
                        history.record(parent + 1.0)
                    }
                }
                history.beginNode(0)
                history.record(0.0)
                for (upper in listOf(false, true)) {
                    val before = work

                    val prediction = history.minimumPrediction(variable, 0.5, cutoff, upper)

                    if (ratio in 0.5..2.0) assertEquals(1.0, prediction) else assertTrue(prediction.isNaN())
                    assertEquals(48L + if (upper && ratio in 0.5..2.0) 8L else 0L, work - before)
                    assertTrue(history.hasReliablePair(variable, 0.5))
                }
            }
        }
    }

    @Test
    fun `child advice requires comparable gaps for every numeric parent independently of outcomes`() {
        val contexts = listOf(1.0, 10.0).flatMap { cutoff ->
            listOf(0.0, 0.25, 0.5, 1.0, 2.0, 4.0).map { cutoff to it }
        } + (Double.MIN_VALUE to Double.POSITIVE_INFINITY)
        for ((cutoff, ratio, equalRates) in contexts.flatMap { (cutoff, ratio) ->
            listOf(Triple(cutoff, ratio, false), Triple(cutoff, ratio, true))
        }) {
            for (parentSlot in 0..3) {
                var work = 0L
                val history = LpDirectionalHistory({}, charge = { work += it })
                val variable = VarRef.Bool(0)
                repeat(2) { sample ->
                    for (up in listOf(false, true)) {
                        val slot = 2 * sample + if (up) 1 else 0
                        val parent = when {
                            slot != parentSlot -> 0.0
                            ratio.isInfinite() -> -1.0
                            else -> cutoff * (1.0 - ratio)
                        }
                        history.beginNode(0)
                        history.record(parent)
                        history.branch(variable, 0.5)
                        history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                        history.beginNode(1)
                        history.record(parent + if (up) 2.0 else 0.5)
                        history.finishNode()
                    }
                }
                repeat(2) {
                    for (up in listOf(false, true)) {
                        history.beginNode(0)
                        history.record(0.0)
                        history.branch(variable, 0.5)
                        history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                        history.beginNode(1)
                        history.record(Double.NaN)
                        history.finishNode(if (equalRates) true else up)
                    }
                }
                history.beginNode(0)
                history.record(0.0)
                val before = work

                val preferred = history.preferDirection(variable, 0.5, cutoff)

                assertEquals(if (ratio in 0.5..2.0) cutoff <= 1.0 else null, preferred)
                assertEquals((if (cutoff <= 1.0) 88L else 96L) +
                    (if (ratio in 0.5..2.0) 32L else 0L), work - before)
            }
        }
    }

    @Test
    fun `reset before numeric parent metadata publication cannot restore observations`() {
        var recording = false
        val events = ArrayList<String>()
        lateinit var history: LpDirectionalHistory
        history = LpDirectionalHistory(events::add, charge = {
            if (recording) {
                recording = false
                history.clear()
            }
        })
        history.beginNode(0)
        history.record(0.0)
        history.branch(VarRef.Bool(0), 0.5)
        history.commit(SearchDecision.Bool(1), 1)
        history.beginNode(1)
        recording = true

        history.record(1.0)

        assertFalse(recording)
        assertFalse(history.hasObservations)
        assertEquals(0, history.retainedEntries)
        assertFalse("DOWN" in events)
    }

    @Test
    fun `child advice requires comparable gaps for every retained outcome parent`() {
        val contexts = listOf(1.0, 10.0).flatMap { cutoff ->
            listOf(0.0, 0.25, 0.5, 1.0, 2.0, 4.0).map { cutoff to it }
        } + (Double.MIN_VALUE to Double.POSITIVE_INFINITY)
        for ((cutoff, ratio, equalRates) in contexts.flatMap { (cutoff, ratio) ->
            listOf(Triple(cutoff, ratio, false), Triple(cutoff, ratio, true))
        }) {
            for (parentSlot in 0..3) {
                var work = 0L
                val history = LpDirectionalHistory({}, charge = { work += it })
                val variable = VarRef.Bool(0)
                repeat(2) { sample ->
                    for (up in listOf(false, true)) {
                        val slot = 2 * sample + if (up) 1 else 0
                        val parent = when {
                            slot != parentSlot -> 0.0
                            ratio.isInfinite() -> -1.0
                            else -> cutoff * (1.0 - ratio)
                        }
                        history.beginNode(0)
                        history.record(parent)
                        history.branch(variable, 0.5)
                        history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                        history.beginNode(1)
                        history.record(parent + if (up) 2.0 else 0.5)
                        history.finishNode(if (equalRates) true else up)
                    }
                }
                history.beginNode(0)
                history.record(0.0)
                val before = work

                val preferred = history.preferDirection(variable, 0.5, cutoff)

                assertEquals(if (ratio in 0.5..2.0) cutoff <= 1.0 else null, preferred)
                assertEquals((if (cutoff <= 1.0) 64L else 72L) + (if (preferred != null) 56L else 0L),
                    work - before)
            }
        }
    }

    @Test
    fun `open child advice requires separated gain ranges at the current fraction`() {
        for (preferredUp in listOf(false, true)) {
            for (point in listOf(0.25, 0.5, 0.75)) {
                for ((preferredGain, scale) in listOf(0.0 to 1.0, 1.0 to 1.0, 1.0 to 1.5, 1.0 to 2.0)) {
                    var work = 0L
                    val history = LpDirectionalHistory({}, charge = { work += it })
                    val variable = VarRef.Bool(0)
                    history.beginNode(0)
                    history.record(0.0)
                    history.branch(variable, 0.5)
                    repeat(2) { sample ->
                        for (up in listOf(false, true)) {
                            history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                            history.beginNode(1)
                            val movement = if (up) 1.0 - point else point
                            val gain = if (up == preferredUp) 0.5 * preferredGain else 0.75
                            val multiplier = if (up == preferredUp && sample == 0) scale else 1.0
                            history.record(gain / movement * multiplier)
                            history.finishNode(up != preferredUp)
                        }
                    }
                    history.beginNode(0)
                    history.record(0.0)
                    for (cutoff in listOf(0.75, 3.0)) {
                        val before = work

                        val preferred = history.preferDirection(variable, point, cutoff)

                        assertEquals(when {
                            cutoff < 1.0 -> !preferredUp
                            preferredGain == 0.0 || scale == 1.0 -> preferredUp
                            else -> null
                        }, preferred)
                        assertEquals((if (cutoff < 1.0) 40L else 48L) + (if (preferred != null) 80L else 0L),
                            work - before)
                    }
                }
            }
        }
    }

    @Test
    fun `upper minimum predictions retain independent directional ranges at the current fraction`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(0.0)
        history.branch(variable, 0.5)
        for ((downGain, upGain) in listOf(2.0 to 1.0, 1.0 to 2.0)) {
            for (up in listOf(false, true)) {
                history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                history.beginNode(1)
                history.record(if (up) upGain else downGain)
            }
        }
        history.record(0.0)

        for ((point, lower, upper) in listOf(Triple(0.25, 0.5, 1.0), Triple(0.5, 1.0, 2.0),
            Triple(0.75, 0.5, 1.0))) {
            assertEquals(lower, history.minimumPrediction(variable, point, 10.0))
            assertEquals(upper, history.minimumPrediction(variable, point, 10.0, upper = true))
        }
    }

    @Test
    fun `parent attribution survives deeper branches through the supported depth limit`() {
        val events = ArrayList<String>()
        val history = LpDirectionalHistory(events::add)
        var observations = 0
        for (depth in listOf(0, 15, 16, 31, 32, 63, 64, 127, 128)) {
            history.beginNode(depth)
            history.record(0.0)
            history.branch(VarRef.Bool(0), 0.5)
            history.commit(SearchDecision.Bool(1), depth + 1)
            history.beginNode(depth + 1)

            history.record(5.0)

            if (depth < 128) observations++
            assertEquals(observations, events.count { it == "DOWN" })
        }
    }

    @Test
    fun `ancestor attribution survives storage growth and sibling retraction`() {
        val events = ArrayList<String>()
        val history = LpDirectionalHistory(events::add)
        history.beginNode(0)
        history.record(0.0)
        history.branch(VarRef.Bool(0), 0.5)
        history.beginNode(16)
        history.record(10.0)
        history.branch(VarRef.Bool(1), 0.5)

        history.commit(SearchDecision.Bool(0), 1)
        history.beginNode(1)
        history.record(5.0)

        assertEquals(1, events.count { it == "UP" })
        assertFalse("DOWN" in events)
    }

    @Test
    fun `history reset during paid parent growth prevents child attribution`() {
        for (stopAt in 1..3) {
            val events = ArrayList<String>()
            var reading = false
            var reads = 0
            lateinit var history: LpDirectionalHistory
            history = LpDirectionalHistory(events::add, charge = {
                if (reading && ++reads == stopAt) {
                    reading = false
                    history.clear()
                }
            })
            history.beginNode(0)
            history.record(0.0)
            history.branch(VarRef.Bool(0), 0.5)
            history.beginNode(16)
            history.record(0.0)
            reading = true

            history.branch(VarRef.Bool(0), 0.5)
            history.commit(SearchDecision.Bool(1), 17)
            history.beginNode(17)
            history.record(5.0)

            assertTrue(reads >= stopAt)
            assertFalse("DOWN" in events)
            assertEquals(0, history.retainedEntries)
        }
    }

    @Test
    fun `minimum child predictions distinguish measured zero from missing evidence`() {
        for (samples in 0..2) {
            for (gain in listOf(0.0, 5.0)) {
                val history = LpDirectionalHistory({})
                val variable = VarRef.Bool(0)
                history.beginNode(0)
                history.record(0.0)
                history.branch(variable, 0.5)
                repeat(samples) {
                    for (up in listOf(false, true)) {
                        history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                        history.beginNode(1)
                        history.record(gain)
                    }
                }
                history.record(0.0)

                for (upper in listOf(false, true)) {
                    val prediction = history.minimumPrediction(variable, 0.5, 3.0, upper)

                    if (samples < 2) assertTrue(prediction.isNaN()) else assertEquals(gain, prediction)
                }
            }
        }
    }

    @Test
    fun `minimum child predictions require representable values and a current positive finite gap`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(0.0)
        history.branch(variable, 0.5)
        repeat(2) { sample ->
            for (up in listOf(false, true)) {
                history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                history.beginNode(1)
                history.record(if (up) 1.0 else Double.MIN_VALUE * if (sample == 0) 2.0 else 1.0)
            }
        }
        history.record(0.0)

        for (upper in listOf(false, true)) {
            assertTrue(history.minimumPrediction(variable, 0.125, 1.0, upper).isNaN())
            assertTrue(history.minimumPrediction(variable, 0.25, 1.0, upper).isNaN())
            assertEquals(Double.MIN_VALUE * if (upper) 2.0 else 1.0,
                history.minimumPrediction(variable, 0.5, Double.MIN_VALUE, upper))
            for (cutoff in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
                assertTrue(history.minimumPrediction(variable, 0.5, cutoff, upper).isNaN())
            }
        }
    }

    @Test
    fun `sparse candidates retain one entry per reliable variable and clear with the source`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.Bool(0)
        repeat(2) {
            history.beginNode(0)
            history.record(0.0)
            history.branch(variable, 0.5)
            repeat(2) {
                for (up in listOf(false, true)) {
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(5.0)
                }
            }
        }
        history.restart()
        val candidates = ArrayList<VarRef>()

        history.forEachCandidate({ true }, candidates::add)
        history.clear()
        history.forEachCandidate({ true }, candidates::add)

        assertEquals(listOf<VarRef>(variable), candidates)
    }

    @Test
    fun `reset while charging a minimum child prediction cannot return retained evidence`() {
        for (upper in listOf(false, true)) {
            for (stopAt in 1..if (upper) 3 else 2) {
                var reading = false
                var reads = 0
                lateinit var history: LpDirectionalHistory
                history = LpDirectionalHistory({}, charge = {
                    if (reading && ++reads == stopAt) {
                        reading = false
                        history.clear()
                    }
                })
                val variable = VarRef.Bool(0)
                history.beginNode(0)
                history.record(0.0)
                history.branch(variable, 0.5)
                repeat(2) {
                    for (up in listOf(false, true)) {
                        history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                        history.beginNode(1)
                        history.record(5.0)
                    }
                }
                history.record(0.0)
                reading = true

                assertTrue(history.minimumPrediction(variable, 0.5, 3.0, upper).isNaN())
                assertTrue(reads >= stopAt)
            }
        }
    }

    @Test
    fun `child order seeks open outcomes until a predicted gain reaches the cutoff`() {
        for (prunedUp in listOf(false, true)) {
            for (mixed in listOf(false, true)) {
                for (sameGain in listOf(false, true)) {
                    var work = 0L
                    val history = LpDirectionalHistory({}, charge = { work += it })
                    val variable = VarRef.Bool(0)
                    history.beginNode(0)
                    history.record(10.0)
                    history.branch(variable, 0.5)
                    repeat(2) { sample ->
                        for (up in listOf(false, true)) {
                            history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                            history.beginNode(1)
                            history.record(if (up == prunedUp || sameGain) 13.0 else 11.0)
                            history.finishNode(up == prunedUp && (!mixed || sample == 0))
                        }
                    }
                    history.beginNode(0)
                    history.record(10.0)
                    for (cutoff in listOf(11.0, 12.0, 13.0, 14.0)) {
                        val before = work

                        val preference = history.preferDirection(variable, 0.5, cutoff)

                        assertEquals(when {
                            cutoff <= 13.0 -> prunedUp
                            sameGain -> null
                            else -> !prunedUp
                        }, preference)
                        assertEquals((if (cutoff <= 13.0) 40L else 48L) + (if (preference != null) 80L else 0L),
                            work - before)
                    }
                }
            }
        }
    }

    @Test
    fun `child rate advice requires predictions consistent with its search intent`() {
        for (higherUp in listOf(false, true)) {
            val history = LpDirectionalHistory({})
            val variable = VarRef.Bool(0)
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            repeat(2) {
                for (up in listOf(false, true)) {
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(if (up == higherUp) 13.0 else 11.0)
                    history.finishNode(up != higherUp)
                }
            }
            history.beginNode(0)
            history.record(10.0)

            for (cutoff in listOf(11.0, 12.0, 14.0)) {
                val preference = history.preferDirection(variable, 0.5, cutoff)

                assertEquals(if (cutoff == 11.0) !higherUp else null, preference)
            }
        }
    }

    @Test
    fun `unknown or nonpositive remaining gaps cannot authorize child advice`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 0.5)
        repeat(2) {
            for (up in listOf(false, true)) {
                history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                history.beginNode(1)
                history.record(if (up) 13.0 else 11.0)
                history.finishNode(up)
            }
        }
        history.beginNode(0)
        history.record(10.0)

        for (cutoff in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 10.0, 9.0)) {
            assertNull(history.preferDirection(variable, 0.5, cutoff))
        }
    }

    @Test
    fun `a positive unrepresentable child prediction cannot supply zero cutoff reach`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(0.0)
        history.branch(variable, 0.5)
        repeat(2) {
            for (up in listOf(false, true)) {
                history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                history.beginNode(1)
                history.record(if (up) 1.0 else Double.MIN_VALUE)
                history.finishNode(up)
            }
        }
        history.beginNode(0)
        history.record(0.0)

        assertNull(history.preferDirection(variable, 0.125, 1.0))
        assertEquals(true, history.preferDirection(variable, 0.5, 1.0))
        assertNull(history.preferDirection(variable, 0.125, 2.0))
        assertEquals(false, history.preferDirection(variable, 0.5, 2.0))
    }

    @Test
    fun `reset during a charged preference lookup discards the result`() {
        for ((cutoff, equalRates) in listOf(10.25, 15.0).flatMap { cutoff ->
            listOf(cutoff to false, cutoff to true)
        }) {
            for (resetAt in 1..if (cutoff == 10.25) 7 else 8) {
                var reading = false
                var reads = 0
                lateinit var history: LpDirectionalHistory
                history = LpDirectionalHistory({}, charge = {
                    if (reading && ++reads == resetAt) {
                        reading = false
                        history.clear()
                    }
                })
                val variable = VarRef.Bool(0)
                history.beginNode(0)
                history.record(10.0)
                history.branch(variable, 0.5)
                repeat(2) {
                    for (up in listOf(false, true)) {
                        history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                        history.beginNode(1)
                        history.record(if (up) 14.0 else 12.0)
                        history.finishNode(if (equalRates) true else up)
                    }
                }
                history.beginNode(0)
                history.record(10.0)
                reading = true

                assertNull(history.preferDirection(variable, 0.5, cutoff))
                assertTrue(reads >= resetAt)
            }
        }
    }

    @Test
    fun `child order follows prune rates when a predicted gain reaches the cutoff`() {
        for (pruned in listOf("up", "down", "both", "neither", "missing", "one", "mixedUp", "mixedDown",
            "mixedEqual", "fullDownMixedUp", "aged")) {
            val history = LpDirectionalHistory({}, maxAge = 6L)
            val variable = VarRef.Bool(0)
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            repeat(2) { sample ->
                for (up in listOf(false, true)) {
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(if (up) 14.0 else 12.0)
                    history.finishNode(when (pruned) {
                        "missing" -> if (up) null else true
                        "one" -> if (up && sample == 1) null else !up
                        "both" -> true
                        "up" -> up
                        "down", "aged" -> !up
                        "mixedUp" -> up && sample == 0
                        "mixedDown" -> !up && sample == 0
                        "mixedEqual" -> sample == 0
                        "fullDownMixedUp" -> !up || sample == 0
                        else -> false
                    })
                }
            }
            if (pruned == "aged") repeat(2) {
                for (up in listOf(false, true)) {
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(if (up) 14.0 else 12.0)
                    history.finishNode()
                }
            }

            history.beginNode(0)
            history.record(10.0)
            val preferred = history.preferDirection(variable, 0.5, 10.25)

            assertEquals(when (pruned) {
                "up", "mixedUp", "both", "neither", "mixedEqual" -> true
                "down", "mixedDown", "fullDownMixedUp" -> false
                else -> null
            }, preferred)
        }
    }

    @Test
    fun `two measured zero directions cannot authorize child advice`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 0.5)
        repeat(2) {
            for (up in listOf(false, true)) {
                history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                history.beginNode(1)
                history.record(10.0)
                history.finishNode(!up)
            }
        }

        assertNull(history.preferDirection(variable, 0.5, 10.25))
    }

    @Test
    fun `cancelled completed outcomes cannot extend directional history`() {
        for (pruned in listOf(false, true)) {
            var recording = false
            var current = true
            val events = ArrayList<String>()
            val history = LpDirectionalHistory(events::add, charge = { if (recording) current = false })
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(0), 0.5)
            history.commit(SearchDecision.Bool(1), 1)
            history.beginNode(1)
            recording = true

            history.finishNode(pruned) { current }

            assertFalse("OUTCOME_PRUNED" in events)
            assertFalse("OUTCOME_OPEN" in events)
        }
    }

    @Test
    fun `infeasible outcomes retain numeric gains without manufacturing a sample`() {
        for (samples in listOf(1, 2)) {
            val events = ArrayList<String>()
            val history = LpDirectionalHistory(events::add)
            val variable = VarRef.Bool(0)
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            repeat(samples) {
                for (literal in listOf(0, 1)) {
                    history.commit(SearchDecision.Bool(literal), 1)
                    history.beginNode(1)
                    history.record(12.0)
                    history.finishNode(false)
                }
            }
            repeat(2) { observation ->
                history.commit(SearchDecision.Bool(1), 1)
                history.beginNode(1)
                history.infeasible()
                history.finishNode(true)
                if (observation == 0) {
                    history.beginNode(0)
                    history.record(10.0)
                    assertTrue(history.hasPruningHistory)
                    assertTrue(history.prunedDirections(variable, 0.5).isNaN())
                    if (samples == 2) assertEquals(false, history.preferDirection(variable, 0.5, 10.25))
                    else assertNull(history.preferDirection(variable, 0.5, 10.25))
                }
                history.finishNode()
            }

            assertTrue(history.hasPruningHistory)
            if (samples == 2) {
                assertEquals(2.0, history.score(variable, 0.5), 1e-9)
                assertEquals(1.0, history.prunedDirections(variable, 0.5))
            } else {
                assertTrue(history.score(variable, 0.5).isNaN())
                assertTrue(history.prunedDirections(variable, 0.5).isNaN())
            }
            assertEquals(samples, events.count { it == "DOWN" })
            assertEquals(samples, events.count { it == "UP" })
            assertEquals(2, events.count { it == "OUTCOME_PRUNED" })
        }
    }

    @Test
    fun `unknown and abandoned children cannot supply prune outcomes`() {
        for (action in listOf("unknown", "restart", "depth")) {
            val events = ArrayList<String>()
            val history = LpDirectionalHistory(events::add)
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(0), 0.5)
            history.commit(SearchDecision.Bool(1), 1)
            history.beginNode(1)
            when (action) {
                "unknown" -> history.finishNode()
                "restart" -> history.restart()
                "depth" -> history.beginNode(2)
            }

            history.infeasible()
            history.finishNode(true)

            assertFalse("OUTCOME_PRUNED" in events)
        }
    }

    @Test
    fun `reset while outcome bookkeeping is charged discards the observation`() {
        var recording = false
        val events = ArrayList<String>()
        lateinit var history: LpDirectionalHistory
        history = LpDirectionalHistory(events::add, charge = {
            if (recording) {
                recording = false
                history.clear()
            }
        })
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        history.commit(SearchDecision.Bool(1), 1)
        history.beginNode(1)
        recording = true

        history.infeasible()
        history.finishNode(true)

        assertEquals(0, history.retainedEntries)
        assertFalse("OUTCOME_PRUNED" in events)
    }

    @Test
    fun `restart retains samples but discards ancestor and pending child attribution`() {
        val events = ArrayList<String>()
        val history = LpDirectionalHistory(events::add)
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 0.5)
        repeat(2) {
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(12.0)
            }
        }
        history.commit(SearchDecision.Bool(1), 1)

        history.restart()
        history.beginNode(1)
        history.record(1000.0)

        assertEquals(2.0, history.score(variable, 0.5), 1e-9)
        assertEquals(1, history.retainedEntries)
        assertEquals(2, events.count { it == "DOWN" })
        assertEquals(2, events.count { it == "UP" })
        assertEquals(1, events.count { it == "RESTART_RETAINED" })
    }

    @Test
    fun `restarts do not renew the age of retained observations`() {
        val history = LpDirectionalHistory({}, maxAge = 6L)
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 0.5)
        repeat(2) {
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(12.0)
            }
        }
        assertTrue(history.score(variable, 0.5).isFinite())

        repeat(7) {
            history.restart()
            history.beginNode(0)
        }

        assertTrue(history.score(variable, 0.5).isNaN())
        assertFalse(history.hasReliableHistory)
    }

    @Test
    fun `geometric scores survive an underflowed child product`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(0.0)
        history.branch(variable, 0.75)
        repeat(2) {
            for ((literal, gain) in listOf(1 to Double.MIN_VALUE, 0 to 1.0e300)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(gain)
            }
        }

        val score = history.score(variable, 0.25)

        assertTrue(score.isFinite() && score > 0.0)
    }

    @Test
    fun `positive unrepresentable scores cannot supply a measured zero reference`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.Bool(0)
        repeat(2) {
            for (up in listOf(false, true)) {
                history.beginNode(0)
                history.record(0.0)
                history.branch(variable, if (up) 0.25 else 0.75)
                history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                history.beginNode(1)
                history.record(Double.MIN_VALUE)
            }
        }

        val score = history.estimate(variable, 0.25)

        assertTrue(score.isNaN())
    }

    @Test
    fun `directional stability respects factor two agreement at floating extremes`() {
        for ((observations, available) in listOf(
            (0.0 to 0.0) to true,
            (0.0 to Double.MIN_VALUE) to false,
            (Double.MIN_VALUE to Double.MIN_VALUE) to true,
            (3.0 * Double.MIN_VALUE to 7.0 * Double.MIN_VALUE) to false,
            (1.0e308 to 1.2e308) to true,
        )) {
            val history = LpDirectionalHistory({})
            val variable = VarRef.Bool(0)
            history.beginNode(0)
            history.record(0.0)
            history.branch(variable, 0.25)
            for (gain in listOf(observations.first, observations.second)) {
                history.commit(SearchDecision.Bool(1), 1)
                history.beginNode(1)
                history.record(1.0)
                history.commit(SearchDecision.Bool(0), 1)
                history.beginNode(1)
                history.record(gain)
            }
            history.beginNode(0)
            history.record(0.0)

            assertEquals(available, history.preferGainDirection(variable, 0.25) != null, "gains=$observations")
        }
    }

    @Test
    fun `parent bookkeeping pays allocation without repeatedly charging unused capacity`() {
        var work = 0L
        val history = LpDirectionalHistory({}, charge = { work += it })
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        assertTrue(work in 1L until 128L)
        val before = work

        history.commit(SearchDecision.Bool(0), 1)
        history.beginNode(1)
        history.record(12.0)
        history.branch(VarRef.Bool(1), 0.5)

        assertTrue(work - before in 1L until 128L)
    }

    @Test
    fun `clearing sparse parents pays the ancestor slots it visits`() {
        var work = 0L
        val history = LpDirectionalHistory({}, charge = { work += it })
        for (level in listOf(0, 100)) {
            history.beginNode(level)
            history.record(10.0)
            history.branch(VarRef.Bool(level), 0.5)
        }
        val before = work

        history.clear()

        assertTrue(work - before >= 101L)
        assertEquals(0, history.retainedEntries)
    }

    @Test
    fun `reliable child preference seeks lower gains unless a child can reach the cutoff`() {
        for (cheaperUp in listOf(false, true)) {
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(0), 0.75)
            repeat(2) {
                for (up in listOf(false, true)) {
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(if (up == cheaperUp) 10.0 else 13.0)
                }
            }

            history.beginNode(0)
            history.record(10.0)

            assertEquals(cheaperUp, history.preferGainDirection(VarRef.Bool(0), 0.75))
            assertEquals(cheaperUp, history.preferGainDirection(VarRef.Bool(0), 0.75, cutoff = 14.0))
            assertEquals(!cheaperUp, history.preferGainDirection(VarRef.Bool(0), 0.75, cutoff = 12.0))
            assertNull(history.preferGainDirection(VarRef.Bool(0), 0.75, cutoff = 10.0))
        }
    }

    @Test
    fun `collection prefers only a recent under observed direction`() {
        for (firstUp in listOf(false, true)) {
            val history = LpDirectionalHistory({}, maxAge = 4L)
            val variable = VarRef.IntVar(0)
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 2.25)
            assertNull(history.preferUnderObservedDirection(variable, 2.25))
            repeat(2) {
                history.commit(if (firstUp) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                history.beginNode(1)
                history.record(11.0)
            }

            assertTrue(history.hasCollectionHistory)
            assertEquals(!firstUp, history.preferUnderObservedDirection(variable, 2.25))
            history.commit(if (firstUp) SearchDecision.IntAtMost(0, 2) else SearchDecision.IntAtLeast(0, 3), 1)
            history.beginNode(1)
            history.record(11.0)

            assertTrue(history.hasCollectionHistory)
            assertEquals(!firstUp, history.preferUnderObservedDirection(variable, 2.25))
            assertTrue(history.score(variable, 2.25).isNaN())
            repeat(5) { history.beginNode(0) }
            assertFalse(history.hasCollectionHistory)
            assertNull(history.preferUnderObservedDirection(variable, 2.25))
        }
    }

    @Test
    fun `aging the opposite direction refreshes collection availability`() {
        val history = LpDirectionalHistory({}, maxAge = 4L)
        val variable = VarRef.Bool(0)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 0.5)
        history.commit(SearchDecision.Bool(1), 1)
        history.beginNode(1)
        history.record(11.0)
        repeat(2) { history.beginNode(1) }
        for (decision in listOf(SearchDecision.Bool(1), SearchDecision.Bool(0), SearchDecision.Bool(0))) {
            history.commit(decision, 1)
            history.beginNode(1)
            history.record(11.0)
        }

        assertTrue(history.hasCollectionHistory)
        assertFalse(history.hasReliableHistory)
        assertEquals(false, history.preferUnderObservedDirection(variable, 0.5))
    }

    @Test
    fun `zero observed gains keep ordinary child order`() {
        for (up in listOf(false, true)) {
            val history = LpDirectionalHistory({})
            val variable = VarRef.Bool(0)
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            repeat(2) {
                history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                history.beginNode(1)
                history.record(10.0)
            }
            history.commit(SearchDecision.Bool(if (up) 1 else 0), 1)
            history.beginNode(1)
            history.record(13.0)

            assertNull(history.preferUnderObservedDirection(variable, 0.5))
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

            assertFalse(history.hasReliableHistory)
        }
    }

    @Test
    fun `samples from different variables do not admit reliable scoring`() {
        val history = LpDirectionalHistory({})
        for (up in listOf(false, true)) {
            repeat(2) {
                val variable = if (up) 1 else 0
                history.beginNode(0)
                history.record(10.0)
                history.branch(VarRef.IntVar(variable), 2.25)
                history.commit(if (up) SearchDecision.IntAtLeast(variable, 3)
                else SearchDecision.IntAtMost(variable, 2), 1)
                history.beginNode(1)
                history.record(if (up) 13.0 else 11.0)
            }
        }

        assertFalse(history.hasReliableHistory)
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
        assertTrue(history.hasReliableHistory)

        history.clear()

        assertFalse(history.hasReliableHistory)
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

        assertEquals(sqrt(3.0), history.score(variable, 2.25), 1e-9)
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
        val history = LpDirectionalHistory(observations::add, maxAge = 4L)
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
        assertTrue(history.hasReliableHistory)
        repeat(5) { history.beginNode(0) }

        assertFalse(history.hasReliableHistory)
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

    @Test
    fun `a gain in only one child keeps ordinary scoring`() {
        for (improvingUp in listOf(false, true)) {
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(0), 0.5)
            repeat(2) {
                for (up in listOf(false, true)) {
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(if (up == improvingUp) 110.0 else 10.0)
                }
            }

            assertTrue(history.score(VarRef.Bool(0), 0.5).isNaN())
        }
    }

    @Test
    fun `refreshing one direction cannot renew the scope scoring gate`() {
        val history = LpDirectionalHistory({}, maxAge = 4L)
        history.beginNode(0)
        history.record(10.0)
        history.branch(VarRef.Bool(0), 0.5)
        repeat(2) {
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(12.0)
            }
        }
        assertTrue(history.hasReliableHistory)

        repeat(5) {
            history.commit(SearchDecision.Bool(0), 1)
            history.beginNode(1)
            history.record(12.0)
        }

        assertFalse(history.hasReliableHistory)
    }

    @Test
    fun `aging an older observation removes two sample reliability`() {
        for (firstUp in listOf(false, true)) {
            val history = LpDirectionalHistory({}, maxAge = 4L)
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(0), 0.5)
            repeat(2) {
                for (up in listOf(firstUp, !firstUp)) {
                    history.commit(SearchDecision.Bool(if (up) 0 else 1), 1)
                    history.beginNode(1)
                    history.record(12.0)
                }
            }
            assertTrue(history.hasReliableHistory)

            repeat(2) { history.beginNode(1) }

            assertFalse(history.hasReliableHistory)
            assertTrue(history.score(VarRef.Bool(0), 0.5).isNaN())
        }
    }

    @Test
    fun `disagreeing gains withhold advice until recent observations stabilize`() {
        val history = LpDirectionalHistory({})
        for ((gain, expected) in listOf(
            4.0 to Double.NaN, 8.0 to 4.0, 100.0 to Double.NaN, 6.0 to Double.NaN, 6.0 to 6.0,
        )) {
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.Bool(0), 0.5)
            for (literal in listOf(0, 1)) {
                history.commit(SearchDecision.Bool(literal), 1)
                history.beginNode(1)
                history.record(10.0 + gain)
            }

            val score = history.score(VarRef.Bool(0), 0.5)

            if (expected.isNaN()) assertTrue(score.isNaN()) else assertEquals(expected, score, 1e-9)
        }
    }
}
