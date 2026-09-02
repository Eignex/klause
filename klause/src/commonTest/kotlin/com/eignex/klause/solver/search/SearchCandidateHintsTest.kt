package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.result.OpenTheoryWorkSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SearchCandidateHintsTest {

    @Test
    fun `a hint decides which polarity of a split is tried first`() {
        for (preferred in listOf(true, false)) {
            val commits = CommitRecorder()
            val session = SearchSession(emptyList())
            val run = session.openRun(
                numBoolVars = 1,
                observer = commits,
                candidateHints = SearchCandidateHints.ofLiterals(intArrayOf(literal(0, preferred))),
            )

            assertIs<SearchRunEvent.Satisfied>(run.next())

            assertEquals(listOf(literal(0, preferred)), commits.literals, "preferred=$preferred")
            assertEquals(preferred, session.boolValue(0))
        }
    }

    @Test
    fun `a hinted split still tries its complement on backtracking`() {
        val commits = CommitRecorder()
        val session = SearchSession(emptyList())
        val run = session.openRun(
            numBoolVars = 1,
            observer = commits,
            candidateHints = SearchCandidateHints.ofLiterals(intArrayOf(literal(0, true))),
        )

        assertIs<SearchRunEvent.Satisfied>(run.next())
        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertIs<SearchRunEvent.Exhausted>(run.next())
        assertEquals(listOf(literal(0, true), literal(0, false)), commits.literals)
    }

    @Test
    fun `propagation overrides a hint before its split is reached`() {
        val commits = CommitRecorder()
        val session = SearchSession(
            listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(literal(0, false)))))),
        )
        val run = session.openRun(
            numBoolVars = 1,
            observer = commits,
            candidateHints = SearchCandidateHints.ofLiterals(intArrayOf(literal(0, true))),
        )

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(false, session.boolValue(0))
        assertEquals(emptyList<Int>(), commits.literals)
    }

    @Test
    fun `a contradictory hint keeps the default branch order`() {
        val commits = CommitRecorder()
        val session = SearchSession(emptyList())
        val run = session.openRun(
            numBoolVars = 1,
            observer = commits,
            candidateHints = SearchCandidateHints.ofLiterals(
                intArrayOf(literal(0, true), literal(0, false)),
            ),
        )

        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(listOf(literal(0, false)), commits.literals)
    }

    @Test
    fun `a hint for a variable outside the split keeps the default branch order`() {
        val commits = CommitRecorder()
        val session = SearchSession(emptyList())
        val run = session.openRun(
            numBoolVars = 1,
            observer = commits,
            candidateHints = SearchCandidateHints.ofLiterals(intArrayOf(literal(3, true))),
        )

        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(listOf(literal(0, false)), commits.literals)
    }

    @Test
    fun `a misleading hint still refutes an unsatisfiable space`() {
        val session = SearchSession(
            listOf(
                ClauseSearchComponent(
                    listOf(
                        Clause(intArrayOf(literal(0, true), literal(1, true))),
                        Clause(intArrayOf(literal(0, true), literal(1, false))),
                        Clause(intArrayOf(literal(0, false), literal(1, true))),
                        Clause(intArrayOf(literal(0, false), literal(1, false))),
                    ),
                ),
            ),
        )
        val run = session.openRun(
            numBoolVars = 2,
            candidateHints = SearchCandidateHints.ofLiterals(
                intArrayOf(literal(0, true), literal(1, true)),
            ),
        )

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<SearchRunEvent.Exhausted>(run.next())
    }

    @Test
    fun `a hint still orders splits after a restart`() {
        val commits = CommitRecorder()
        val session = SearchSession(emptyList())
        val run = session.openRun(
            numBoolVars = 2,
            params = SearchSolveParams(restart = OneShotRestart()),
            observer = commits,
            candidateHints = SearchCandidateHints.ofLiterals(
                intArrayOf(literal(0, true), literal(1, true)),
            ),
        )

        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(listOf(literal(0, true), literal(0, true), literal(1, true)), commits.literals)
    }

    @Test
    fun `a cancelled hinted run reaches no verdict`() {
        val session = SearchSession(emptyList(), cancellation = Cancellation { true })
        val run = session.openRun(
            numBoolVars = 1,
            candidateHints = SearchCandidateHints.ofLiterals(intArrayOf(literal(0, true))),
        )

        assertIs<SearchRunEvent.Indeterminate>(run.next())
    }

    @Test
    fun `a hinted run charges the same decisions and open work as an unhinted one`() {
        val hints = listOf(
            "unhinted" to SearchCandidateHints.None,
            "hinted" to SearchCandidateHints.ofLiterals(intArrayOf(literal(0, true), literal(1, true))),
        )

        for ((name, hint) in hints) {
            val work = OpenTheoryWorkSink()
            val commits = CommitRecorder()
            val session = SearchSession(emptyList())
            session.attachOpenTheoryWork(work)
            val run = session.openRun(numBoolVars = 2, observer = commits, candidateHints = hint)

            assertIs<SearchRunEvent.Satisfied>(run.next())

            assertEquals(2, commits.literals.size, name)
            assertEquals(2L, work.snapshot().openBoolDecisions, name)
            assertEquals(2L, work.snapshot().openWork, name)
        }
    }

    @Test
    fun `a hinted run stops on the same decision budget as an unhinted one`() {
        val session = SearchSession(emptyList())
        val run = session.openRun(
            numBoolVars = 2,
            params = SearchSolveParams(maxDecisions = 1),
            candidateHints = SearchCandidateHints.ofLiterals(
                intArrayOf(literal(0, true), literal(1, true)),
            ),
        )

        assertEquals(SearchRunEvent.Indeterminate.Budget, run.next())
    }

    @Test
    fun `hints from literals drop a variable hinted both ways`() {
        val hints = SearchCandidateHints.ofLiterals(
            intArrayOf(
                literal(0, true),
                literal(1, false),
                literal(1, true),
                literal(2, true),
                literal(2, true),
            ),
        )

        assertEquals(true, hints.preferredBool(0))
        assertNull(hints.preferredBool(1))
        assertEquals(true, hints.preferredBool(2))
        assertNull(hints.preferredBool(3))
    }

    @Test
    fun `a split that is not one Boolean variable keeps its delegate order`() {
        val split = listOf(SearchDecision.IntAtMost(0, 1), SearchDecision.IntAtLeast(0, 2))
        val branching = HintedBooleanBranching(BooleanBranching { split }) { true }

        assertEquals(split, branching.alternatives(SearchSession(emptyList())))
    }

    private fun literal(variable: Int, value: Boolean): Int = (variable shl 1) or if (value) 0 else 1

    /** Restarts the traversal once, so a second run re-selects the same hinted splits from root. */
    private class OneShotRestart : SearchRestartPolicy {
        private var fired = false

        override fun shouldRestart(decisionsThisRun: Long): Boolean = !fired

        override fun onRestart() {
            fired = true
        }
    }

    private class CommitRecorder : SearchRunObserver {
        val literals = ArrayList<Int>()

        override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
            if (decision is SearchDecision.Bool) literals.add(decision.literal)
        }
    }
}
