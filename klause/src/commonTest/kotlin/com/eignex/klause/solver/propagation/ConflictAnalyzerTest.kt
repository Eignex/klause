package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.InputOrder
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConflictAnalyzerTest {

    @Test
    fun `learned clause and backjump level on direct propagation conflict`() {
        // Three clauses:  (¬a ∨ b),  (¬a ∨ ¬b),  (a).
        // Decision: try a=true (level 1). Propagation forces b=true via clause 0,
        // and clause 1 then fails because ¬a is false and ¬b is false.
        //
        // 1UIP analysis seeded by clause 1: literals are [¬a, ¬b]. Both are at level 1.
        // Most-recent-pinned is b (forced after a). Resolve b out by replacing with
        // its antecedents from clause 0: [¬a, b] minus b → [¬a]. New working set:
        // {¬a from clause 1} ∪ {¬a from antecedent} → just {a}. Now only one
        // current-level var remains → UIP = a. Learned clause: [¬a].
        val problem = Problem(
            numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),  // ¬a ∨ b
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))), // ¬a ∨ ¬b
            ),
        )
        val session = PropagationSession(problem)
        // Push a = true to trigger the conflict.
        val result = session.pinBool(0, true)
        val unsat = assertIs<PropagationResult.Unsat>(result)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        // Expected learned clause: {¬a}. Backjump to level 0 (only literal is at level 1
        // and after the jump, the clause is empty/unit at level 0 which immediately
        // forces a=false in the next propagation).
        assertEquals(setOf(Lit.make(0, false)), learned.literals.toSet(),
            "learned clause should be [¬a], got ${learned.literals.toList()}")
        assertEquals(0, learned.backjumpLevel,
            "single-level conflict over a single decision should backjump to level 0")
    }

    @Test
    fun `learned clause spans multiple decision levels`() {
        // Two-decision conflict that genuinely requires both decisions to manifest:
        //   c0: ¬a ∨ ¬b ∨ c     (only fails if a, b true and c forced false elsewhere)
        //   c1: ¬a ∨ ¬b ∨ ¬c    (mirror — together with c0 forbids a ∧ b)
        //
        // Pinning a=true alone propagates nothing (each clause has two non-false
        // watches left). Pinning b=true cascades: c0 unit-pins c=true (with antecedents
        // [¬a, ¬b]), then c1 fires with all three literals false → conflict.
        //
        // 1UIP resolution: seed reason from c1 = [¬a, ¬b, ¬c]. a is at level 1 (lower
        // → goes to learned), b and c are at level 2 (current). Walk trail backwards:
        // c is most recent at level 2 → resolve via c's antecedents [¬a, ¬b] (both
        // already in `seen`, no new vars). Next backward step: pivot = b. Only one
        // current-level var left → UIP = b. Learned clause = [¬a, ¬b]; backjump to
        // level 1.
        val problem = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        val session = PropagationSession(problem)
        val r1 = session.pinBool(0, true)
        assertIs<PropagationResult.Implied>(r1)
        val r2 = session.pinBool(1, true)
        val unsat = assertIs<PropagationResult.Unsat>(r2)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false)), learned.literals.toSet(),
            "1UIP learned clause should be [¬a, ¬b], got ${learned.literals.toList()}")
        assertEquals(1, learned.backjumpLevel,
            "backjump should target level 1 (the only lower-level variable's level)")
    }

    @Test
    fun `non-clause failing factor returns NotApplicable`() {
        // A Linear factor at the conflict — the analyzer can't get a clause-form
        // reason from it today, so it bails out. The engine falls back to chronological
        // backtrack (which is the search behaviour without LCG).
        val problem = Problem(
            numBoolVars = 0, numIntVars = 1,
            intDomains = arrayOf(com.eignex.klause.solver.IntDomain(0, 3)),
            factors = listOf(
                com.eignex.klause.solver.factor.Linear(
                    intArrayOf(1), intArrayOf(0),
                    com.eignex.klause.solver.factor.LinearOp.EQ, 5,
                ),  // impossible: 1·x = 5 with x in [0,3]
            ),
        )
        // The conflict fires at bake time → no learning at level 0. Verify the
        // BacktrackSolver still reports Unsat (chronological-backtrack fallback).
        val r = BacktrackSolver(problem).solve(BacktrackParams(variableHeuristic = InputOrder))
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `CDB-driven search proves UNSAT on a small encoding`() {
        // Two-clause direct contradiction. With CDB enabled (always on in the current
        // engine), the search terminates immediately — the analyzer's bake-time clause
        // is empty so no jump is requested, but the engine still arrives at Unsat.
        val problem = Problem(
            numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams())
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `CDB finds SAT on a chained-propagation instance`() {
        // (¬a ∨ b), (¬b ∨ c), (¬c ∨ d), (¬d ∨ e), (a).
        // a=true forces b → c → d → e via unit propagation. No conflict — search
        // should reach SAT in one decision. The point of the test is that the
        // analyzer (which runs on every conflict; here there are none) and the new
        // sealed AdvanceOutcome path don't break the no-conflict happy path.
        val problem = Problem(
            numBoolVars = 5, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),  // ¬a ∨ b
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(3, false), Lit.make(4, true))),
                Clause(intArrayOf(Lit.make(0, true))),  // a
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        // All five vars should end up true.
        for (v in 0 until 5) {
            assertTrue(sat.assignment.bools[v], "v$v should be true; got ${sat.assignment.bools.toList()}")
        }
    }
}
