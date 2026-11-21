package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.splitIntAlternatives
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPassiveVariableChoiceTest {
    @Test
    fun `variable context tags require one matching fresh changed commitment`() {
        for (apply in listOf(false, true)) {
            for (action in listOf("commit", "replay", "clear", "foreign", "stale", "charge", "report")) {
                val events = ArrayList<String>()
                var fresh = true
                var committing = false
                var stage = 0
                var work = 0L
                val choice = LpPassiveVariableChoice(apply, observe = { event ->
                    events.add(event)
                    if (action == "report" && event.contains("COMMIT") && event.endsWith("_CHANGED")) fresh = false
                }, charge = {
                    work += it
                    if (committing && action == "charge" && stage++ == 1) fresh = false
                })
                val selected = choice.select(LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 0.75),
                    LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), 0.25), 1, { fresh }, { true },
                    context = "UNMEASURED_GAIN_BELOW_SCORE")
                choice.branch(selected, 1)
                val down = SearchDecision.IntAtMost(selected.varId, 0L)
                choice.alternatives(selected, listOf(down, SearchDecision.IntAtLeast(selected.varId, 1L)), 1) { fresh }
                if (action == "clear") choice.clear()
                if (action == "stale") fresh = false
                val before = work
                committing = true

                choice.commit(if (action == "foreign") SearchDecision.IntAtMost(3, 0L) else down) { fresh }
                if (action == "replay") choice.commit(down) { fresh }

                val event = if (apply) "PASSIVE_VARIABLE_COMMIT_CONTEXT_"
                else "PASSIVE_VARIABLE_COMMIT_WITHHELD_CONTEXT_"
                val accepted = action == "commit" || action == "replay"
                assertEquals(if (accepted) 1 else 0, events.count { it == event + "UNMEASURED_GAIN_BELOW_SCORE" }, action)
                assertEquals(before + if (action == "clear") 0L else 8L, work)
                assertNull(choice.nominatedVariable)
            }
        }
    }

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
            choice.alternatives(selected, listOf(SearchDecision.IntAtMost(selected.varId, 0L),
                SearchDecision.IntAtLeast(selected.varId, 1L)), 1) { fresh }
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
            val alternatives = if (proposal.variable is VarRef.Bool) {
                listOf(SearchDecision.Bool(2), SearchDecision.Bool(3))
            } else listOf(SearchDecision.IntAtMost(1, 1L), SearchDecision.IntAtLeast(1, 2L))
            choice.alternatives(proposal.variable, alternatives, 1) { true }

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
        choice.alternatives(baseline.variable, listOf(SearchDecision.IntAtMost(0, 0L),
            SearchDecision.IntAtLeast(0, 1L)), 1) { true }

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
            choice.alternatives(variable, listOf(SearchDecision.IntAtMost(1, 0L),
                SearchDecision.IntAtLeast(1, 1L)), stamp) { true }

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
        choice.alternatives(VarRef.IntVar(1), listOf(SearchDecision.IntAtMost(1, 0L),
            SearchDecision.IntAtLeast(1, 1L)), 1) { true }

        choice.clear()
        choice.commit(SearchDecision.IntAtMost(1, 0L)) { true }

        assertFalse("PASSIVE_VARIABLE_COMMITTED" in events)
        assertNull(choice.nominatedVariable)
    }

    @Test
    fun `kernel endpoint and wide domain partitions retain withheld commitment attribution`() {
        for ((domain, point) in listOf(IntDomain(0, 1) to 1.0, IntDomain(0, 3_000_000_000L) to 0.0)) {
            val session = PropagationSession(Problem(0, 2, arrayOf(domain, IntDomain(0, 10)), emptyArray()))
            for (apply in listOf(false, true)) {
                for (alternative in 0..1) {
                    val events = ArrayList<String>()
                    val choice = LpPassiveVariableChoice(apply, events::add, {})
                    val selected = choice.select(LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 3.5),
                        LpPassiveVariableChoice.Candidate(VarRef.IntVar(0), point), 1, { true }, { true })
                    choice.branch(selected, 1)
                    val alternatives = splitIntAlternatives(session, selected as VarRef.IntVar,
                        if (apply) 3L else point.toLong())
                    choice.alternatives(selected, alternatives, 1) { true }

                    choice.commit(alternatives[alternative]) { true }

                    val event = if (apply) "PASSIVE_VARIABLE_COMMIT_CHANGED"
                    else "PASSIVE_VARIABLE_COMMIT_WITHHELD_CHANGED"
                    assertEquals(1, events.count { it == event })
                    assertNull(choice.nominatedVariable)
                }
            }
        }
    }

    @Test
    fun `uncaptured invalid or stale partitions cannot attribute an integral baseline`() {
        for (stage in 0..5) {
            var fresh = true
            var capturing = false
            val events = ArrayList<String>()
            val choice = LpPassiveVariableChoice(false, events::add, { if (capturing && stage == 5) fresh = false })
            val baseline = VarRef.IntVar(0)
            choice.select(LpPassiveVariableChoice.Candidate(VarRef.IntVar(1), 3.5),
                LpPassiveVariableChoice.Candidate(baseline, 1.0), 1, { true }, { true })
            choice.branch(baseline, 1)
            val down = SearchDecision.IntAtMost(0, 0L)
            val up = SearchDecision.IntAtLeast(0, 1L)
            if (stage != 0) {
                val alternatives = when (stage) {
                    1 -> listOf(down)
                    2 -> listOf(down, SearchDecision.IntAtLeast(0, 2L))
                    3 -> listOf(down, SearchDecision.IntAtLeast(1, 1L))
                    else -> listOf(down, up)
                }
                capturing = true
                choice.alternatives(baseline, alternatives, if (stage == 4) 2 else 1) { fresh }
                capturing = false
            }

            choice.commit(down) { fresh }

            assertFalse("PASSIVE_VARIABLE_COMMIT_WITHHELD_CHANGED" in events, "$stage: $events")
            assertNull(choice.nominatedVariable)
        }
    }
}
