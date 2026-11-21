package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPassiveVariableChoiceTest {
    @Test
    fun `commitment invalidated while bookkeeping is charged supplies no attribution`() {
        for (apply in listOf(false, true)) {
            var fresh = true
            var committing = false
            var work = 0L
            val events = ArrayList<String>()
            val choice = LpPassiveVariableChoice(apply, events::add, {
                work += it
                if (committing) fresh = false
            })
            val selected = choice.select(LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 0.75),
                LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), 0.25), 1, { fresh }, { true })
            choice.branch(selected, 1)
            val before = work
            committing = true

            choice.commit(SearchDecision.IntAtMost(selected.varId, 0L)) { fresh }

            assertEquals(before + 4L, work)
            assertNull(choice.nominatedVariable)
            assertFalse("PASSIVE_VARIABLE_COMMITTED" in events)
            assertFalse("PASSIVE_VARIABLE_COMMIT_WITHHELD" in events)
        }
    }

    @Test
    fun `advice withholding preserves passive comparison cost`() {
        val baseline = LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), 0.25)
        val proposal = LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 0.75)
        val costs = ArrayList<Long>()
        for (apply in listOf(false, true)) {
            var work = 0L
            val choice = LpPassiveVariableChoice(apply, {}, { work += it })

            val selected = choice.select(proposal, baseline, 1, { true }, { true })

            assertEquals(if (apply) proposal.variable else baseline.variable, selected)
            costs.add(work)
        }
        assertTrue(costs.first() > 0L)
        assertEquals(costs.first(), costs.last())
    }

    @Test
    fun `a matching passive commitment is attributed once`() {
        for ((baseline, proposal, decision) in listOf(
            Triple(LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), 0.0),
                LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 1.75), SearchDecision.IntAtLeast(1, 2L)),
            Triple(LpPassiveVariableChoice.Candidate(VarRef.Bool(0), 0.25),
                LpPassiveVariableChoice.Candidate(VarRef.Bool(1), 0.75), SearchDecision.Bool(2)),
        )) {
            val events = ArrayList<String>()
            val choice = LpPassiveVariableChoice(true, events::add, {})
            choice.select(proposal, baseline, 1, { true }, { true })
            choice.branch(proposal.variable, 1)

            choice.commit(decision) { true }
            choice.commit(decision) { true }

            assertEquals(1, events.count { it == "PASSIVE_VARIABLE_COMMIT_CHANGED" })
            assertNull(choice.nominatedVariable)
        }
    }

    @Test
    fun `withheld passive commitments remain distinct from applied changes`() {
        val baseline = LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), 0.0)
        val proposal = LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 0.75)
        val events = ArrayList<String>()
        val choice = LpPassiveVariableChoice(false, events::add, {})
        choice.select(proposal, baseline, 1, { true }, { true })
        choice.branch(baseline.variable, 1)

        choice.commit(SearchDecision.IntAtMost(0, 0L)) { true }

        assertEquals(1, events.count { it == "PASSIVE_VARIABLE_COMMIT_WITHHELD_CHANGED" })
        assertFalse("PASSIVE_VARIABLE_COMMITTED" in events)
        assertFalse("PASSIVE_VARIABLE_COMMIT_CHANGED" in events)
    }

    @Test
    fun `selection invalidated while bookkeeping is charged keeps the baseline`() {
        val baseline = LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), 0.25)
        val proposal = LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 0.75)
        for (duringRead in listOf(false, true)) {
            var fresh = true
            val events = ArrayList<String>()
            val choice = LpPassiveVariableChoice(true, events::add, { if (!duringRead) fresh = false })

            val selected = choice.select(proposal, baseline, 1, { fresh }, {
                fresh = false
                true
            })

            assertEquals(baseline.variable, selected)
            assertNull(choice.nominatedVariable)
            assertFalse("PASSIVE_VARIABLE_COMPARED" in events)
        }
    }

    @Test
    fun `unreliable or invalid passive points supply no nomination`() {
        val baseline = LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), 0.25)
        for ((reference, proposal, reliable) in listOf(
            Triple(baseline, LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 0.75), false),
            Triple(baseline, LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), Double.NaN), true),
            Triple(baseline, LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 1.0), true),
            Triple(baseline, LpPassiveVariableChoice.Candidate(VarRef.Bool(1), 1.5), true),
            Triple(LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), Double.NaN),
                LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 0.75), true),
            Triple(LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), Long.MAX_VALUE.toDouble()),
                LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 0.75), true),
            Triple(LpPassiveVariableChoice.Candidate(VarRef.Bool(0), 1.5),
                LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 0.75), true),
        )) {
            val events = ArrayList<String>()
            val choice = LpPassiveVariableChoice(true, events::add, {})

            val selected = choice.select(proposal, reference, 1, { true }, { reliable })
            choice.branch(reference.variable, 1)
            choice.commit(SearchDecision.IntAtMost(0, 0L)) { true }

            assertEquals(reference.variable, selected)
            assertNull(choice.nominatedVariable)
            assertFalse("PASSIVE_VARIABLE_COMMITTED" in events)
        }
    }

    @Test
    fun `a different variable stamp or partition cannot borrow a passive nomination`() {
        for ((variable, stamp, decision) in listOf(
            Triple(VarRef.IntVar(0), 1, SearchDecision.IntAtMost(1, 0L)),
            Triple(VarRef.IntVar(1), 2, SearchDecision.IntAtMost(1, 0L)),
            Triple(VarRef.IntVar(1), 1, SearchDecision.IntAtMost(1, 1L)),
        )) {
            val events = ArrayList<String>()
            val choice = LpPassiveVariableChoice(true, events::add, {})
            choice.select(LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 0.75),
                LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), 0.25), 1, { true }, { true })
            choice.branch(variable, stamp)

            choice.commit(decision) { true }

            assertFalse("PASSIVE_VARIABLE_COMMITTED" in events)
            assertNull(choice.nominatedVariable)
        }
    }

    @Test
    fun `clearing a passive nomination prevents its commitment`() {
        val events = ArrayList<String>()
        val choice = LpPassiveVariableChoice(true, events::add, {})
        choice.select(LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 0.75),
            LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), 0.25), 1, { true }, { true })
        choice.branch(VarRef.IntVar(1), 1)

        choice.clear()
        choice.commit(SearchDecision.IntAtMost(1, 0L)) { true }

        assertFalse("PASSIVE_VARIABLE_COMMITTED" in events)
        assertNull(choice.nominatedVariable)
    }
}
