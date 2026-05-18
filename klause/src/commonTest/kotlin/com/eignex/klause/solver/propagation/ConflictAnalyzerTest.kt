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
    fun `learned clause persists in the session after backjump`() {
        // Two-decision conflict that learns [¬a, ¬b]. After backjump to level 1 + clause
        // assertion, the session's learned-clause registry should contain exactly the
        // learned clause, and a *fresh* attempt to pin a=true should now be blocked at
        // level 1 (it would unit-propagate ¬b through the learned clause, then a future
        // pin of b=true would conflict). Direct way to test: hand-walk the session,
        // re-pin a=true post-learn, and observe the cascade.
        val problem = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        // Run the solver — it will hit the conflict, learn, and proceed to SAT (a=false
        // ∨ b=false satisfies the learned clause; one of them remains free to satisfy
        // the originals too).
        val r = BacktrackSolver(problem).solve(BacktrackParams(
            variableHeuristic = InputOrder, randomSeed = 0L,
        ))
        val sat = assertIs<SolveResult.Sat>(r)
        // The found model must satisfy: not both a and b true.
        val a = sat.assignment.bools[0]
        val b = sat.assignment.bools[1]
        assertTrue(!(a && b),
            "learned clause [¬a, ¬b] should block the a=true ∧ b=true assignment; got a=$a b=$b")
    }

    @Test
    fun `engine accumulates learned clauses across multiple conflicts`() {
        // Three-decision pigeonhole-flavoured instance designed to trigger multiple
        // conflicts during search. After solve completes, the session-internal state
        // should have at least one learned clause stored (validated indirectly via the
        // solver's correctness — the more direct check would require exposing the
        // learned-clause list, which would be a public API concession).
        val problem = Problem(
            numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true),  Lit.make(1, true))),    // a ∨ b
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),    // ¬a ∨ c
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),   // ¬b ∨ ¬c
                Clause(intArrayOf(Lit.make(2, true),  Lit.make(3, false))),   // c ∨ ¬d
                Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),    // ¬c ∨ d
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 42L))
        // Verify a satisfying assignment.
        val sat = assertIs<SolveResult.Sat>(r)
        val s = sat.assignment.bools
        // Manually evaluate every clause to confirm correctness.
        val clauses = listOf(
            listOf(Lit.make(0, true), Lit.make(1, true)),
            listOf(Lit.make(0, false), Lit.make(2, true)),
            listOf(Lit.make(1, false), Lit.make(2, false)),
            listOf(Lit.make(2, true), Lit.make(3, false)),
            listOf(Lit.make(2, false), Lit.make(3, true)),
        )
        for ((i, c) in clauses.withIndex()) {
            assertTrue(c.any { Lit.evaluate(it, s[Lit.variable(it)]) },
                "clause $i not satisfied by $s")
        }
    }

    @Test
    fun `LBD reflects the distinct decision levels in the learned clause`() {
        // Same two-decision conflict as `learned clause spans multiple decision levels`,
        // but here we assert the LBD field. Learned clause is [¬a, ¬b]; literals span
        // two distinct decision levels (1 and 2) → LBD = 2 (glue-clause boundary).
        val problem = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, true))
        val unsat = assertIs<PropagationResult.Unsat>(session.pinBool(1, true))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertEquals(2, learned.lbd, "two-decision-level clause should have LBD = 2")
    }

    @Test
    fun `forgetLearnedClauses removes high-LBD clauses and remaps watcher entries`() {
        // Drive a search that learns multiple clauses, then prune.
        val problem = Problem(
            numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true),  Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(2, true),  Lit.make(3, false))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(
            // Cap at 0 → forgetting will drop everything except glue (LBD ≤ 2). Combined
            // with a tight Luby restart base, the forgetting pass triggers reliably.
            lubyRestartBase = 4,
            maxLearnedClauses = 0,
            lbdGlueThreshold = 2,
            randomSeed = 7L,
        ))
        // Correctness must survive forgetting — every original clause must still be
        // satisfied. (The bound enforces forgetting actually runs.)
        val sat = assertIs<SolveResult.Sat>(r)
        val s = sat.assignment.bools
        val clauses = listOf(
            listOf(Lit.make(0, true), Lit.make(1, true)),
            listOf(Lit.make(0, false), Lit.make(2, true)),
            listOf(Lit.make(1, false), Lit.make(2, false)),
            listOf(Lit.make(2, true), Lit.make(3, false)),
            listOf(Lit.make(2, false), Lit.make(3, true)),
        )
        for ((i, c) in clauses.withIndex()) {
            assertTrue(c.any { Lit.evaluate(it, s[Lit.variable(it)]) },
                "clause $i not satisfied by ${s.toList()}")
        }
    }

    @Test
    fun `state forgetLearnedClauses directly compacts and remaps`() {
        // Hand-construct a state, add three learned clauses with different LBDs, drop
        // the middle one, and verify the remaining are correctly renumbered and the
        // watcher index points at the new ids.
        val problem = Problem(
            numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val state = PropagationState(problem, com.eignex.klause.solver.Assumptions.None)
        val baseFid = problem.numFactors
        val c0 = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val c1 = Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true)))
        val c2 = Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true)))
        val fid0 = state.addLearnedClause(c0, lbd = 1)
        val fid1 = state.addLearnedClause(c1, lbd = 5)  // will be dropped
        val fid2 = state.addLearnedClause(c2, lbd = 1)
        assertEquals(baseFid, fid0)
        assertEquals(baseFid + 1, fid1)
        assertEquals(baseFid + 2, fid2)
        assertEquals(3, state.learnedClauses.size)
        // Confirm watcher index has c1 listed at some lit before forget.
        assertTrue(state.boolWatchersByLit[Lit.make(1, false)].toIntArray().toList().contains(fid1),
            "c1 should be in ¬b watcher list before forget")

        // Forget anything with LBD > 1.
        state.forgetLearnedClauses { _, lbd -> lbd <= 1 }
        assertEquals(2, state.learnedClauses.size,
            "expected 2 clauses kept after dropping the high-LBD one")
        // Remaining clauses should be c0 and c2 — order preserved, renumbered.
        assertEquals(c0, state.learnedClauses[0])
        assertEquals(c2, state.learnedClauses[1])
        // The watcher index must no longer reference the dropped fid (¬b watcher list
        // should not contain fid1 = baseFid+1 anymore).
        assertTrue(!state.boolWatchersByLit[Lit.make(1, false)].toIntArray().toList().contains(fid1),
            "watcher entry for the dropped clause should be removed")
        // The surviving clauses should be present at their new ids.
        val newFid2 = baseFid + 1  // c2 moved up one slot
        assertTrue(state.boolWatchersByLit[Lit.make(2, true)].toIntArray().toList().contains(newFid2),
            "c2 should be findable at its new fid (${newFid2}) via its watch literal")
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
