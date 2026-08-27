package com.eignex.klause.propagation

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ),
        )
        val session = PropagationSession(problem)
        val result = session.pinBool(0, true)
        val unsat = assertIs<PropagationResult.Unsat>(result)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        // Expected learned clause: {¬a}. Backjump to level 0 (only literal is at level 1
        // and after the jump, the clause is empty/unit at level 0 which immediately
        // forces a=false in the next propagation).
        assertEquals(
            setOf(Lit.make(0, false)),
            learned.literals.toSet(),
            "learned clause should be [¬a], got ${learned.literals.toList()}",
        )
        assertEquals(
            0,
            learned.backjumpLevel,
            "single-level conflict over a single decision should backjump to level 0",
        )
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
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
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
        assertEquals(
            setOf(Lit.make(0, false), Lit.make(1, false)),
            learned.literals.toSet(),
            "1UIP learned clause should be [¬a, ¬b], got ${learned.literals.toList()}",
        )
        assertEquals(
            1,
            learned.backjumpLevel,
            "backjump should target level 1 (the only lower-level variable's level)",
        )
    }

    @Test
    fun `a non-clause failing factor still proves UNSAT`() {
        // A Linear factor at the conflict — the analyzer can't get a clause-form
        // reason from it, so it bails out and the engine falls back to chronological
        // backtrack (the search behaviour without LCG).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(
                    intArrayOf(1),
                    intArrayOf(0),
                    LinearOp.EQ,
                    5,
                ),
            ),
        )
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(variableSelector = InputOrder))
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `CDB-driven search proves UNSAT on a small encoding`() {
        // Two-clause direct contradiction. The analyzer's clause is empty so no jump is
        // requested, and the engine still arrives at Unsat.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams())
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `learned clause persists in the session after backjump`() {
        // Two-decision conflict that learns [¬a, ¬b]. Once the clause is asserted, a=true forces
        // b=false through it, so no returned assignment may set both.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        val r = BacktrackSolver(problem.bake()).solve(
            BacktrackParams(
                variableSelector = InputOrder,
                randomSeed = 0L,
            ),
        )
        val sat = assertIs<SolveResult.Sat>(r)
        val a = sat.assignment.bools[0]
        val b = sat.assignment.bools[1]
        assertTrue(
            !(a && b),
            "learned clause [¬a, ¬b] should block the a=true ∧ b=true assignment; got a=$a b=$b",
        )
    }

    @Test
    fun `LBD reflects the distinct decision levels in the learned clause`() {
        // Same two-decision conflict as `learned clause spans multiple decision levels`,
        // but here we assert the LBD field. Learned clause is [¬a, ¬b]; literals span
        // two distinct decision levels (1 and 2) → LBD = 2 (glue-clause boundary).
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
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
    fun `search stays correct under default learning and under aggressive forgetting`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, false))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
            ),
        )
        val clauses = listOf(
            listOf(Lit.make(0, true), Lit.make(1, true)),
            listOf(Lit.make(0, false), Lit.make(2, true)),
            listOf(Lit.make(1, false), Lit.make(2, false)),
            listOf(Lit.make(2, true), Lit.make(3, false)),
            listOf(Lit.make(2, false), Lit.make(3, true)),
        )
        val runs = listOf(
            "default learning" to BacktrackParams(randomSeed = 42L),
            // Cap at 0 → forgetting will drop everything except glue (LBD ≤ 2). Combined
            // with a tight Luby restart base, the forgetting pass triggers reliably.
            "aggressive forgetting" to BacktrackParams(
                lubyRestartBase = 4,
                maxLearnedClauses = 0,
                lbdGlueThreshold = 2,
                randomSeed = 7L,
            ),
        )
        for ((label, params) in runs) {
            val sat = assertIs<SolveResult.Sat>(BacktrackSolver(problem.bake()).solve(params))
            val s = sat.assignment.bools
            for ((i, c) in clauses.withIndex()) {
                assertTrue(
                    c.any { Lit.evaluate(it, s[Lit.variable(it)]) },
                    "$label: clause $i not satisfied by ${s.toList()}",
                )
            }
        }
    }

    @Test
    fun `state forgetLearnedClauses directly compacts and remaps`() {
        // Hand-construct a state, add three learned clauses with different LBDs, drop
        // the middle one, and verify the remaining are correctly renumbered and the
        // watcher index points at the new ids.
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val state = PropagationState(problem, Assumptions.None)
        val baseFid = problem.numFactors
        val c0 = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))).asPropagator() as ClausePropagator
        val c1 = Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))).asPropagator() as ClausePropagator
        val c2 = Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))).asPropagator() as ClausePropagator
        val fid0 = state.addLearnedClause(c0, lbd = 1)
        val fid1 = state.addLearnedClause(c1, lbd = 5)
        val fid2 = state.addLearnedClause(c2, lbd = 1)
        assertEquals(baseFid, fid0)
        assertEquals(baseFid + 1, fid1)
        assertEquals(baseFid + 2, fid2)
        assertEquals(3, state.learnedClauses.size)
        assertTrue(
            state.watches.byLit[Lit.make(1, false)].toIntArray().toList().contains(fid1),
            "c1 should be in ¬b watcher list before forget",
        )

        state.forgetLearnedClauses { _, lbd -> lbd <= 1 }
        assertEquals(
            2,
            state.learnedClauses.size,
            "expected 2 clauses kept after dropping the high-LBD one",
        )
        assertContentEquals(c0.literals, (state.learnedClauses[0] as ClausePropagator).literals)
        assertContentEquals(c2.literals, (state.learnedClauses[1] as ClausePropagator).literals)
        assertTrue(
            !state.watches.byLit[Lit.make(1, false)].toIntArray().toList().contains(fid1),
            "watcher entry for the dropped clause should be removed",
        )
        val newFid2 = baseFid + 1
        assertTrue(
            state.watches.byLit[Lit.make(2, true)].toIntArray().toList().contains(newFid2),
            "c2 should be findable at its new fid ($newFid2) via its watch literal",
        )
    }

    @Test
    fun `clause minimization drops redundant literals`() {
        // Build a propagation chain that produces a learnable clause whose raw 1UIP
        // output contains a redundant literal, then verify minimization removes it.
        //
        //   c0: ¬a ∨ b        (a forces b)
        //   c1: ¬b ∨ c        (b forces c)
        //   c2: ¬a ∨ ¬b ∨ ¬c  (fails when a, b, c all true)
        //
        // Decision: a=true (lvl 1) → b forced (c0), c forced (c1), c2 fails.
        //
        // Raw 1UIP resolution:
        //   Seed (c2): [¬a, ¬b, ¬c]. All at level 1.
        //   currentLevelCount = 3.
        //   Walk trail. Most recent = c. Resolve via c's antecedents (c1): [¬b, c],
        //   minus c → [¬b]. ¬b already in seen — nothing new. Continue.
        //   Most recent = b. Resolve via b's antecedents (c0): [¬a, b], minus b → [¬a].
        //   ¬a already in seen. Continue.
        //   Most recent = a. UIP. learned literals are [¬a, ¬b, ¬c] (added during seed),
        //   plus UIP literal ¬a added at the end → but ¬a is already there.
        //
        // After 1UIP: literals are {¬a, ¬b, ¬c} (raw, possibly with one dup).
        // Minimization: variable c is implied by b (antecedents = [¬b], which is
        // already in clause). Drop c. Variable b is implied by a (antecedents = [¬a],
        // also in clause). Drop b. Result: just {¬a}.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(0, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertEquals(
            setOf(Lit.make(0, false)),
            learned.literals.toSet(),
            "minimization should drop ¬b and ¬c (both implied by ¬a via the chain), " +
                "leaving just [¬a]; got ${learned.literals.toList()}",
        )
        assertEquals(
            0,
            learned.backjumpLevel,
            "unit-clause learning forces backjump to level 0",
        )
        assertEquals(1, learned.lbd, "minimized to single literal → LBD = 1")
    }

    @Test
    fun `clause minimization preserves correctness on minimized SAT search`() {
        // End-to-end check: run a CDB search that learns minimizable clauses, verify
        // the resulting assignment satisfies every original clause.
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false), Lit.make(3, false))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(4, true))),
            ),
        )
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 11L))
        val sat = assertIs<SolveResult.Sat>(r)
        val s = sat.assignment.bools
        val clauses = listOf(
            listOf(Lit.make(0, false), Lit.make(1, true)),
            listOf(Lit.make(1, false), Lit.make(2, true)),
            listOf(Lit.make(2, false), Lit.make(3, true)),
            listOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false), Lit.make(3, false)),
            listOf(Lit.make(0, true), Lit.make(4, true)),
        )
        for ((i, c) in clauses.withIndex()) {
            assertTrue(
                c.any { Lit.evaluate(it, s[Lit.variable(it)]) },
                "clause $i not satisfied by ${s.toList()}",
            )
        }
    }

    @Test
    fun `CDB finds SAT on a chained-propagation instance`() {
        // (¬a ∨ b), (¬b ∨ c), (¬c ∨ d), (¬d ∨ e), (a).
        // a=true forces b → c → d → e via unit propagation. No conflict arises, so this pins the
        // happy path where the analyzer never runs.
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(3, false), Lit.make(4, true))),
                Clause(intArrayOf(Lit.make(0, true))),
            ),
        )
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        for (v in 0 until 5) {
            assertTrue(sat.assignment.bools[v], "v$v should be true; got ${sat.assignment.bools.toList()}")
        }
    }
}
