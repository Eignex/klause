package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpPassiveVariableCollectionTest {
    @Test
    fun `incomplete positive histories reach a matching collection commitment`() {
        for ((boolean, balanced) in listOf(false to false, false to true, true to false, true to true)) {
            val variable = if (boolean) VarRef.Bool(1) else VarRef.IntVar(1)
            val down = if (boolean) SearchDecision.Bool(3) else SearchDecision.IntAtMost(1, 0)
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(10.0)
            history.branch(variable, 0.5)
            history.commit(down, 1)
            history.beginNode(1)
            history.record(11.0)
            if (balanced) {
                history.commit(if (boolean) SearchDecision.Bool(2) else SearchDecision.IntAtLeast(1, 1), 1)
                history.beginNode(1)
                history.record(12.0)
            }
            val events = ArrayList<String>()
            var work = 0L
            val collector = LpPassiveVariableCollection(events::add, { work += it })

            val selected = collector.select(LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), 0.0),
                history, 7, { 0.5 }, { true }, { true })
            collector.branch(selected, 7)
            collector.commit(down) { true }
            collector.commit(down) { true }

            assertEquals(variable, selected)
            assertEquals(1, events.count { it == "PASSIVE_COLLECTION_VARIABLE_COMMIT_CHANGED" })
            assertTrue(events.none { it.startsWith("PASSIVE_VARIABLE_") })
            assertEquals(32L, work)
            assertTrue(history.score(variable, 0.5).isNaN())
        }
    }

    @Test
    fun `a productive baseline preserves the scan allowance for another variable`() {
        val history = LpDirectionalHistory({})
        val variable = VarRef.IntVar(1)
        history.beginNode(0)
        history.record(10.0)
        history.branch(variable, 0.5)
        history.commit(SearchDecision.IntAtMost(1, 0), 1)
        history.beginNode(1)
        history.record(11.0)
        val events = ArrayList<String>()
        var work = 0L
        val collector = LpPassiveVariableCollection(events::add, { work += it }, maxScans = 1)

        repeat(2) {
            assertEquals(variable, collector.select(LpPassiveVariableChoice.Candidate(variable, 0.5),
                history, 1, { 0.5 }, { true }, { true }))
        }
        val selected = collector.select(LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), 0.5),
            history, 1, { 0.5 }, { true }, { true })

        assertEquals(variable, selected)
        assertEquals(2, events.count { it == "PASSIVE_COLLECTION_VARIABLE_BASELINE_READY" })
        assertEquals(1, events.count { it == "PASSIVE_COLLECTION_VARIABLE_SCAN" })
        assertEquals(32L, work)
    }

    @Test
    fun `unavailable histories and fixed variables keep the baseline`() {
        for (condition in listOf("missing", "zero", "reliable", "expired", "integral", "invalid", "fixed")) {
            val history = LpDirectionalHistory({})
            val variable = VarRef.IntVar(1)
            if (condition != "missing") repeat(if (condition == "reliable") 2 else 1) {
                history.beginNode(0)
                history.record(10.0)
                history.branch(variable, 0.5)
                for (up in if (condition == "reliable") listOf(false, true) else listOf(false)) {
                    history.commit(if (up) SearchDecision.IntAtLeast(1, 1) else SearchDecision.IntAtMost(1, 0), 1)
                    history.beginNode(1)
                    history.record(if (condition == "zero") 10.0 else 11.0)
                }
            }
            if (condition == "expired") repeat(257) { history.beginNode(0) }
            val events = ArrayList<String>()
            val collector = LpPassiveVariableCollection(events::add, {})
            val baseline = VarRef.IntVar(0)
            val point = when (condition) {
                "integral" -> 1.0
                "invalid" -> Double.NaN
                else -> 0.5
            }

            val selected = collector.select(LpPassiveVariableChoice.Candidate(baseline, 0.5), history, 1,
                { point }, { condition != "fixed" }, { true })
            collector.branch(baseline, 1)
            collector.commit(SearchDecision.IntAtMost(0, 0)) { true }

            assertEquals(baseline, selected, condition)
            assertFalse("PASSIVE_COLLECTION_VARIABLE_SELECTION" in events, condition)
            assertFalse("PASSIVE_COLLECTION_VARIABLE_COMMITTED" in events, condition)
        }
    }

    @Test
    fun `scan and visit limits survive clearing nominations and history`() {
        val history = LpDirectionalHistory({})
        repeat(4) { id ->
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.IntVar(id), 0.5)
            history.commit(SearchDecision.IntAtMost(id, 0), 1)
            history.beginNode(1)
            history.record(10.0)
        }
        val events = ArrayList<String>()
        var work = 0L
        val collector = LpPassiveVariableCollection(events::add, { work += it }, maxScans = 2, maxVisitsPerScan = 2)
        val baseline = LpPassiveVariableChoice.Candidate(VarRef.IntVar(4), 0.5)
        repeat(2) { collector.select(baseline, history, 1, { 0.5 }, { true }, { true }) }
        collector.clear()
        history.clear()
        val before = work

        val selected = collector.select(baseline, history, 2, { 0.5 }, { true }, { true })

        assertEquals(baseline.variable, selected)
        assertEquals(2, events.count { it == "PASSIVE_COLLECTION_VARIABLE_SCAN" })
        assertEquals(4, events.count { it == "PASSIVE_COLLECTION_VARIABLE_VISIT" })
        assertEquals(24L, work)
        assertEquals(before, work)
    }

    @Test
    fun `invalidation during paid inspection preserves the baseline and spent work`() {
        for (invalidateAt in listOf(4L, 8L, 24L)) {
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.IntVar(1), 0.5)
            history.commit(SearchDecision.IntAtMost(1, 0), 1)
            history.beginNode(1)
            history.record(11.0)
            val events = ArrayList<String>()
            var work = 0L
            val collector = LpPassiveVariableCollection(events::add, { work += it })
            val baseline = VarRef.IntVar(0)

            val selected = collector.select(LpPassiveVariableChoice.Candidate(baseline, 0.5), history, 1,
                { 0.5 }, { true }, { work < invalidateAt })
            collector.branch(VarRef.IntVar(1), 1)
            collector.commit(SearchDecision.IntAtMost(1, 0)) { true }

            assertEquals(baseline, selected)
            assertEquals(invalidateAt, work)
            assertFalse("PASSIVE_COLLECTION_VARIABLE_COMMITTED" in events)
        }
    }

    @Test
    fun `cleared stale cancelled and mismatched commitments cannot consume a nomination`() {
        for (condition in listOf("cleared", "stamp", "variable", "partition", "cancelled")) {
            val history = LpDirectionalHistory({})
            history.beginNode(0)
            history.record(10.0)
            history.branch(VarRef.IntVar(1), 0.5)
            history.commit(SearchDecision.IntAtMost(1, 0), 1)
            history.beginNode(1)
            history.record(11.0)
            val events = ArrayList<String>()
            val collector = LpPassiveVariableCollection(events::add, {})
            collector.select(LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), 0.5), history, 1,
                { 0.5 }, { true }, { true })
            collector.branch(VarRef.IntVar(if (condition == "variable") 0 else 1), if (condition == "stamp") 2 else 1)
            if (condition == "cleared") collector.clear()

            collector.commit(SearchDecision.IntAtMost(1, if (condition == "partition") 1 else 0)) {
                condition != "cancelled"
            }
            collector.commit(SearchDecision.IntAtMost(1, 0)) { true }

            assertFalse("PASSIVE_COLLECTION_VARIABLE_COMMITTED" in events, condition)
        }
    }
}
