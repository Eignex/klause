package com.eignex.klause.solver

import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.BacktrackParams

import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BacktrackSolverTest {

    @Test
    fun `solve returns SAT with valid witness on simple clause`() {
        // (x0 ∨ x1)
        val p = Problem(
            numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val r = BacktrackSolver(p).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(sat.assignment.bools[0] || sat.assignment.bools[1],
            "witness must satisfy the clause: ${sat.assignment.bools.toList()}")
    }

    @Test
    fun `solve returns UNSAT on contradiction`() {
        val p = Problem(
            numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(p).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `solve populates unsat core when propagation rules out the problem at root`() {
        // Two-clause direct contradiction. Bake-time propagation pins var 0 via the first
        // clause; the second clause then fails on a conflicting pin. Both factors are
        // load-bearing for the contradiction, and the propagation-graph BFS captures both.
        val p = Problem(
            numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val verdict = assertIs<SolveResult.Unsat>(BacktrackSolver(p).solve(BacktrackParams()))
        val core = verdict.core ?: error("expected propagation-derived unsat core, got null")
        assertEquals(setOf(0, 1), core.factorIds.toSet(),
            "core should mention both contradicting clauses, got ${core.factorIds.toList()}")
    }

    @Test
    fun `unsat core captures chained propagation through intermediate factors`() {
        // Four clauses chained: x0 → x1 → x2 → ¬x2. Bake-time propagation forces
        // x0 = true (unit clause), then x1 = true (clause says ¬x0 ∨ x1), then x2 = true,
        // then the final clause requires x2 = false → contradiction. All four factors
        // are load-bearing — the BFS through reason-arrays must collect every one.
        val p = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true))),                                  // x0
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),              // ¬x0 ∨ x1
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),              // ¬x1 ∨ x2
                Clause(intArrayOf(Lit.make(2, false))),                                 // ¬x2
            ),
        )
        val verdict = assertIs<SolveResult.Unsat>(BacktrackSolver(p).solve(BacktrackParams()))
        val core = verdict.core ?: error("expected propagation-derived unsat core, got null")
        assertEquals(setOf(0, 1, 2, 3), core.factorIds.toSet(),
            "transitive core should include every link in the propagation chain, got ${core.factorIds.toList()}")
    }

    @Test
    fun `watcher index routes wakeups only on the false-going literal`() {
        // Clause `+v0 ∨ +v1 ∨ +v2`. Initial watches are on v0 and v1. After
        // construction, the per-literal watcher index should list the clause at
        // Lit.make(0,true) and Lit.make(1,true) — and nowhere else, including
        // *negative* polarities of those vars and either polarity of v2 (which is
        // not yet watched).
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(clause))
        val state = com.eignex.klause.solver.propagation.PropagationState(problem, Assumptions.None)
        // Initially watching the literals at indices 0 and 1 — the positive forms of v0 and v1.
        assertEquals(1, state.boolWatchersByLit[Lit.make(0, true)].size,
            "clause should be in watcher list for +v0")
        assertEquals(1, state.boolWatchersByLit[Lit.make(1, true)].size,
            "clause should be in watcher list for +v1")
        // Should NOT be on the negative polarities (those becoming false means the
        // positive literal is true → clause satisfied → no wakeup needed).
        assertEquals(0, state.boolWatchersByLit[Lit.make(0, false)].size,
            "clause should not be woken when -v0 becomes false (i.e., v0 = true)")
        // Should NOT be on v2 at all yet — not a watched literal.
        assertEquals(0, state.boolWatchersByLit[Lit.make(2, true)].size,
            "v2 is not yet a watched literal")
    }

    @Test
    fun `cardinality watched literals propagate at-least-K under pin pressure`() {
        // AtLeast-2 over 8 vars. Pin 5 of them to false → only 3 positive literals are
        // non-false; need 2 true. Pin a 6th to false → only 2 non-false remain; both
        // must be unit-pinned true. The watched-literal scheme (3 at-least watches,
        // 0 at-most watches since max == n) drives this exactly.
        val problem = Problem(
            numBoolVars = 8, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Cardinality(
                literals = IntArray(8) { Lit.make(it, true) },
                min = 2, max = 8,
            )),
        )
        val pins = mutableMapOf<Int, Boolean>()
        for (v in 0 until 6) pins[v] = false  // 6 of 8 false → exactly 2 non-false left
        val result = BacktrackSolver(problem).solve(BacktrackParams(
            assumptions = Assumptions(bools = pins),
        ))
        val sat = assertIs<SolveResult.Sat>(result)
        assertEquals(true, sat.assignment.bools[6], "v6 should be unit-forced true")
        assertEquals(true, sat.assignment.bools[7], "v7 should be unit-forced true")
    }

    @Test
    fun `cardinality watched literals propagate at-most-K when count saturates`() {
        // AtMost-2 over 8 vars. Pin 2 of them to true → no more can be true; the
        // remaining 6 must be forced false. Watched-literal at-most side detects this
        // when its (n - max + 1) = 7 watches can't find a non-true replacement.
        val problem = Problem(
            numBoolVars = 8, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Cardinality(
                literals = IntArray(8) { Lit.make(it, true) },
                min = 0, max = 2,
            )),
        )
        val pins = mutableMapOf<Int, Boolean>(0 to true, 1 to true)
        val result = BacktrackSolver(problem).solve(BacktrackParams(
            assumptions = Assumptions(bools = pins),
        ))
        val sat = assertIs<SolveResult.Sat>(result)
        for (v in 2 until 8) {
            assertEquals(false, sat.assignment.bools[v],
                "v$v should be unit-forced false to keep count ≤ 2, got ${sat.assignment.bools[v]}")
        }
    }

    @Test
    fun `cardinality bilateral exactly-one detects unsat under conflicting pins`() {
        // ExactlyOne over 4 vars with two of them pinned true → contradiction.
        // The watched scheme has both at-least (2 watches) and at-most (4 watches);
        // the at-most side should fire and detect the over-budget condition.
        val problem = Problem(
            numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Cardinality.exactlyOne(IntArray(4) { Lit.make(it, true) })),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(
            assumptions = Assumptions(bools = mapOf(0 to true, 1 to true)),
        ))
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `watched literals propagate wide unit clauses correctly`() {
        // A 50-literal clause: at least one of v0..v49 must be true. Bake-time
        // propagation can't pin anything (50 unassigned). After pinning v0..v48 to
        // false via assumptions, the clause becomes unit on v49 → propagation pins
        // v49 = true. This exercises the watched-literal scheme on a clause where
        // most literals are false at propagation time; the per-fire walk has to find
        // the one remaining non-false literal as a replacement watch and detect unit.
        val problem = Problem(
            numBoolVars = 50, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Clause(IntArray(50) { Lit.make(it, true) })),
        )
        val pins = mutableMapOf<Int, Boolean>()
        for (v in 0 until 49) pins[v] = false
        val result = BacktrackSolver(problem).solve(BacktrackParams(
            assumptions = Assumptions(bools = pins),
        ))
        val sat = assertIs<SolveResult.Sat>(result)
        assertEquals(true, sat.assignment.bools[49],
            "watched-literal unit propagation should force v49 = true")
        for (v in 0 until 49) {
            assertEquals(false, sat.assignment.bools[v], "v$v assumption should hold")
        }
    }

    @Test
    fun `unsat core handles two-sided int narrowing`() {
        // Two linear constraints: 1·x >= 5 and 1·x <= 3. Each individually is fine on
        // domain [0,10]; together they empty the domain. Both factors must appear in
        // the core — the separate intMinReason / intMaxReason tracking is what catches
        // this (a single-reason scheme would lose whichever side was set first).
        val p = Problem(
            numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            ),
        )
        val verdict = assertIs<SolveResult.Unsat>(BacktrackSolver(p).solve(BacktrackParams()))
        val core = verdict.core ?: error("expected propagation-derived unsat core, got null")
        assertEquals(setOf(0, 1), core.factorIds.toSet(),
            "both-side narrowing should put both factors in the core, got ${core.factorIds.toList()}")
    }

    @Test
    fun `maxInstructions tightens budget vs maxDecisions when smaller`() {
        // 10 unconstrained bools — DFS needs to pin all 10 to reach a SAT leaf since
        // there are no propagators to collapse the tree. maxInstructions = 2 hits the
        // cap after 2 decisions → Unknown. A generous budget reaches SAT.
        val p = Problem(
            numBoolVars = 10, numIntVars = 0, intDomains = emptyArray(),
            factors = emptyList(),
        )
        val tight = BacktrackSolver(p).solve(BacktrackParams(
            maxDecisions = Long.MAX_VALUE, maxInstructions = 2L, randomSeed = 0L,
        ))
        assertIs<SolveResult.Unknown>(tight)
        val loose = BacktrackSolver(p).solve(BacktrackParams(
            maxDecisions = Long.MAX_VALUE, maxInstructions = 1_000_000L, randomSeed = 0L,
        ))
        assertIs<SolveResult.Sat>(loose)
    }

    @Test
    fun `solve respects assumptions`() {
        // (x0 ∨ x1) with x0=false pinned → x1 must be true in the SAT witness.
        val p = Problem(
            numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val r = BacktrackSolver(p).solve(BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to false))))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(false, sat.assignment.bools[0])
        assertEquals(true, sat.assignment.bools[1])
    }

    @Test
    fun `enumerate yields every distinct SAT model on exactly-one`() {
        // exactly-one over 4 vars → 4 models.
        val p = Problem(
            numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Cardinality.exactlyOne(intArrayOf(
                Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
            ))),
        )
        val models = BacktrackSolver(p).enumerate(BacktrackParams(minHammingDistance = 0)).toList()
        assertEquals(4, models.size)
        assertEquals(4, models.toSet().size, "models must be distinct")
        // Each model has exactly one true bool.
        for (m in models) {
            assertEquals(1, m.bools.count { it })
        }
    }

    @Test
    fun `enumerate over int domain`() {
        // x in [0..2] with x ≥ 1 → values {1, 2}.
        val p = Problem(
            numBoolVars = 0, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 2)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)),
        )
        val models = BacktrackSolver(p).enumerate(BacktrackParams(minHammingDistance = 0)).toList()
        assertEquals(setOf(1, 2), models.map { it.ints[0] }.toSet())
    }

    @Test
    fun `solve returns Unknown when budget exhausts before finding SAT`() {
        // Hard-to-find problem with tiny budget.
        val p = Problem(
            numBoolVars = 10, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                // Force exactly one of 10 vars to be true; budget=1 won't find it.
                Cardinality.exactlyOne((0..9).map { Lit.make(it, true) }.toIntArray()),
            ),
        )
        val r = BacktrackSolver(p).solve(BacktrackParams(maxDecisions = 1))
        // Could legitimately be Unknown or Sat depending on whether the first branch hits.
        // The strong assertion: it must not be Unsat (the problem is feasible).
        assertTrue(r is SolveResult.Sat || r is SolveResult.Unknown,
            "should not report Unsat on feasible problem: $r")
    }

    @Test
    fun `minimize finds the optimal feasible assignment`() {
        // exactly-one over 4 vars with weights — minimum at the cheapest.
        val p = Problem(
            numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Cardinality.exactlyOne(intArrayOf(
                Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
            ))),
        )
        val obj = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val best = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(best)
        assertEquals(3.0, obj.evaluate(best))
        assertEquals(true, best.bools[3])
    }

    @Test
    fun `enumerate honours minHammingDistance`() {
        // 3-var cardinality at least one; all 7 models exist, but with minDistance=2
        // we should get only a few.
        val p = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Cardinality(
                literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                min = 1, max = 3,
            )),
        )
        val models = BacktrackSolver(p).enumerate(
            BacktrackParams(minHammingDistance = 2, recentWindow = 16)
        ).toList()
        // Every adjacent pair must differ by at least 2 bools.
        for (i in 0 until models.size - 1) {
            var d = 0
            for (j in models[i].bools.indices) if (models[i].bools[j] != models[i + 1].bools[j]) d++
            assertTrue(d >= 2, "models[$i] vs models[${i + 1}] only differ by $d")
        }
    }

    @Test
    fun `samples yields models without dedup window`() {
        val p = Problem(
            numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = emptyList(),  // 4 models total
        )
        val models = BacktrackSolver(p).samples(BacktrackParams(randomSeed = 0L)).take(80).toList()
        assertEquals(80, models.size, "samples is infinite for feasible problems; take(80) drains exactly 80")
        assertEquals(4, models.toSet().size, "All 4 distinct models should be sampled with replacement")
    }
}
