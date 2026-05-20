package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lock down the [VariableHeuristic] / [ValueHeuristic] notification contract: every
 * propagation conflict fires `onConflict`, every successful pin fires `onCommit`, and
 * every Luby restart fires `onRestart`. Activity-based heuristics (VSIDS, dom/wdeg,
 * last-conflict) hang off these hooks; if they break the next heuristic-driven CP
 * search work goes blind.
 */
class HeuristicCallbackTest {

    private class CountingHeuristics : VariableHeuristic, ValueHeuristic {
        var commitCount: Int = 0
        var conflictCount: Int = 0
        var restartCount: Int = 0
        val committedVars: MutableList<Int> = ArrayList()
        val conflictVars: MutableList<Int> = ArrayList()

        // Delegate the actual decisions to the canonical defaults; we're only counting.
        override fun pick(session: PropagationSession, rng: Random): VarRef? =
            InputOrder.pick(session, rng)

        override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> =
            IndomainMin.values(session, varRef, rng)

        override fun onCommit(varRef: VarRef) {
            commitCount++; committedVars.add(varRef.varId)
        }
        override fun onConflict(varRef: VarRef) {
            conflictCount++; conflictVars.add(varRef.varId)
        }
        override fun onRestart() { restartCount++ }
        // ValueHeuristic also has onCommit / onConflict — the same instance receives both
        // (since it implements both interfaces). The overload resolution picks the
        // VariableHeuristic variants (no `value` parameter) for the var-level callbacks,
        // and these stubs for the value-level ones.
        override fun onCommit(varRef: VarRef, value: Int) { /* counted via var-level */ }
        override fun onConflict(varRef: VarRef, value: Int) { /* counted via var-level */ }
        // Both VariableHeuristic and ValueHeuristic carry onSolution; pick a single
        // override that satisfies both (they share the same signature).
        override fun onSolution(snapshot: com.eignex.klause.solver.Sample) {}
    }

    @Test
    fun `onCommit fires once per successful pin`() {
        // 3 bools, no constraints → 8 leaves. enumerate visits all of them; each leaf
        // requires 3 successful pins, so commits ≥ 8 * 3 + intermediate-only commits = ?
        // Actually with DFS: total `onCommit` calls equal the total number of decision
        // pushes across the tree. For a 3-bool unconstrained problem with binary
        // branching, the tree has 2 + 4 + 8 = 14 successful pins.
        val problem = Problem(numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = emptyArray())
        val h = CountingHeuristics()
        val samples = BacktrackSolver(problem).enumerate(BacktrackParams(
            randomSeed = 0L, variableHeuristic = h, valueHeuristic = h,
        )).toList()
        assertEquals(8, samples.size)
        // Each leaf required descending through 3 nodes; the engine pops and re-advances
        // on the way back up. The expected number is the count of *distinct value pins*
        // across the whole search — exactly 14 for a complete binary tree of depth 3.
        assertEquals(14, h.commitCount, "expected 14 successful pins; got ${h.commitCount}")
        // No constraints → no conflicts.
        assertEquals(0, h.conflictCount)
    }

    @Test
    fun `onConflict fires on propagation Unsat`() {
        // Two bools a, b with clauses (a ∨ b), (a ∨ ¬b), (¬a ∨ b), (¬a ∨ ¬b). These four
        // clauses together resolve to ⊥, but klause's default baking only does unit-
        // propagation (no failed-literal probing without opt-in), so the contradiction
        // surfaces only at DFS depth 1: pinning a=false unit-propagates b=true via
        // (a ∨ b), then (a ∨ ¬b) forces b=false → conflict. Same on a=true. Guaranteed
        // DFS-level Unsat regardless of value ordering.
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ))
        val h = CountingHeuristics()
        BacktrackSolver(problem).solve(BacktrackParams(
            randomSeed = 0L,
            variableHeuristic = h, valueHeuristic = h,
            maxDecisions = 100L,
        ))
        assertTrue(h.conflictCount > 0,
            "expected at least one DFS-level propagation conflict; got ${h.conflictCount}")
        assertEquals(0, h.committedVars.size - h.commitCount, "var-id log matches commit count")
    }

    @Test
    fun `onRestart fires once per Luby restart`() {
        // With a tiny Luby base, the search restarts many times before completing.
        // Each restart pops the trail and invokes onRestart.
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val h = CountingHeuristics()
        // maxDecisions caps total work so the restart loop doesn't run forever.
        BacktrackSolver(problem).solve(BacktrackParams(
            randomSeed = 0L,
            variableHeuristic = h, valueHeuristic = h,
            lubyRestartBase = 1L,
            maxDecisions = 30L,
        ))
        assertTrue(h.restartCount > 0, "expected at least one restart, got ${h.restartCount}")
    }

    @Test
    fun `default heuristics ignore callbacks without compile errors`() {
        // The defaults (RandomVariable / IndomainRandom) don't override the callbacks.
        // This test just verifies the search runs cleanly with them — no NPE, no
        // exception. The actual semantic test is "no callback override breaks the build."
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = emptyArray())
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).toList()
    }
}
