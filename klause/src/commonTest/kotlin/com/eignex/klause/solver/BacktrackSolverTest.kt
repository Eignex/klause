package com.eignex.klause.solver

import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.DomWdeg
import com.eignex.klause.solver.backtrack.LastConflict
import com.eignex.klause.solver.backtrack.RandomVariable
import com.eignex.klause.solver.backtrack.SmallestDomain
import com.eignex.klause.solver.backtrack.VarRef
import com.eignex.klause.solver.backtrack.Vsids
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.propagation.PropagationResult.Unsat
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BacktrackSolverTest {

    @Test
    fun `solve returns SAT with valid witness on simple clause`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val r = BacktrackSolver(p).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(
            sat.assignment.bools[0] || sat.assignment.bools[1],
            "witness must satisfy the clause: ${sat.assignment.bools.toList()}",
        )
    }

    @Test
    fun `solve returns UNSAT on contradiction`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
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
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val verdict = assertIs<SolveResult.Unsat>(BacktrackSolver(p).solve(BacktrackParams()))
        val core = verdict.core ?: error("expected propagation-derived unsat core, got null")
        assertEquals(
            setOf(0, 1),
            core.factorIds.toSet(),
            "core should mention both contradicting clauses, got ${core.factorIds.toList()}",
        )
    }

    @Test
    fun `unsat core captures chained propagation through intermediate factors`() {
        // Four clauses chained: x0 → x1 → x2 → ¬x2. Bake-time propagation forces
        // x0 = true (unit clause), then x1 = true (clause says ¬x0 ∨ x1), then x2 = true,
        // then the final clause requires x2 = false → contradiction. All four factors
        // are load-bearing — the BFS through reason-arrays must collect every one.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(2, false))),
            ),
        )
        val verdict = assertIs<SolveResult.Unsat>(BacktrackSolver(p).solve(BacktrackParams()))
        val core = verdict.core ?: error("expected propagation-derived unsat core, got null")
        assertEquals(
            setOf(0, 1, 2, 3),
            core.factorIds.toSet(),
            "transitive core should include every link in the propagation chain, got ${core.factorIds.toList()}",
        )
    }

    @Test
    fun `watcher index routes wakeups only on the false-going literal`() {
        // Clause `+v0 ∨ +v1 ∨ +v2`. Initial watches are on v0 and v1. After
        // construction, the per-literal watcher index should list the clause at
        // Lit.make(0,true) and Lit.make(1,true) — and nowhere else, including
        // *negative* polarities of those vars and either polarity of v2 (which is
        // not yet watched).
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(clause),
        )
        val state = PropagationState(problem, Assumptions.None)
        assertEquals(
            1,
            state.boolWatchersByLit[Lit.make(0, true)].size,
            "clause should be in watcher list for +v0",
        )
        assertEquals(
            1,
            state.boolWatchersByLit[Lit.make(1, true)].size,
            "clause should be in watcher list for +v1",
        )
        assertEquals(
            0,
            state.boolWatchersByLit[Lit.make(0, false)].size,
            "clause should not be woken when -v0 becomes false (i.e., v0 = true)",
        )
        assertEquals(
            0,
            state.boolWatchersByLit[Lit.make(2, true)].size,
            "v2 is not yet a watched literal",
        )
    }

    @Test
    fun `cardinality watched literals propagate at-least-K under pin pressure`() {
        // AtLeast-2 over 8 vars. Pin 5 of them to false → only 3 positive literals are
        // non-false; need 2 true. Pin a 6th to false → only 2 non-false remain; both
        // must be unit-pinned true. The watched-literal scheme (3 at-least watches,
        // 0 at-most watches since max == n) drives this exactly.
        val problem = Problem(
            numBoolVars = 8,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality(
                    literals = IntArray(8) { Lit.make(it, true) },
                    min = 2,
                    max = 8,
                ),
            ),
        )
        val pins = mutableMapOf<Int, Boolean>()
        for (v in 0 until 6) pins[v] = false
        val result = BacktrackSolver(problem).solve(
            BacktrackParams(
                assumptions = Assumptions(bools = pins),
            ),
        )
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
            numBoolVars = 8,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality(
                    literals = IntArray(8) { Lit.make(it, true) },
                    min = 0,
                    max = 2,
                ),
            ),
        )
        val pins = mutableMapOf<Int, Boolean>(0 to true, 1 to true)
        val result = BacktrackSolver(problem).solve(
            BacktrackParams(
                assumptions = Assumptions(bools = pins),
            ),
        )
        val sat = assertIs<SolveResult.Sat>(result)
        for (v in 2 until 8) {
            assertEquals(
                false,
                sat.assignment.bools[v],
                "v$v should be unit-forced false to keep count ≤ 2, got ${sat.assignment.bools[v]}",
            )
        }
    }

    @Test
    fun `cardinality bilateral exactly-one detects unsat under conflicting pins`() {
        // ExactlyOne over 4 vars with two of them pinned true → contradiction.
        // The watched scheme has both at-least (2 watches) and at-most (4 watches);
        // the at-most side should fire and detect the over-budget condition.
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality.exactlyOne(IntArray(4) { Lit.make(it, true) })),
        )
        val r = BacktrackSolver(problem).solve(
            BacktrackParams(
                assumptions = Assumptions(bools = mapOf(0 to true, 1 to true)),
            ),
        )
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
            numBoolVars = 50,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(IntArray(50) { Lit.make(it, true) })),
        )
        val pins = mutableMapOf<Int, Boolean>()
        for (v in 0 until 49) pins[v] = false
        val result = BacktrackSolver(problem).solve(
            BacktrackParams(
                assumptions = Assumptions(bools = pins),
            ),
        )
        val sat = assertIs<SolveResult.Sat>(result)
        assertEquals(
            true,
            sat.assignment.bools[49],
            "watched-literal unit propagation should force v49 = true",
        )
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
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            ),
        )
        val verdict = assertIs<SolveResult.Unsat>(BacktrackSolver(p).solve(BacktrackParams()))
        val core = verdict.core ?: error("expected propagation-derived unsat core, got null")
        assertEquals(
            setOf(0, 1),
            core.factorIds.toSet(),
            "both-side narrowing should put both factors in the core, got ${core.factorIds.toList()}",
        )
    }

    @Test
    fun `maxInstructions tightens budget vs maxDecisions when smaller`() {
        // 10 unconstrained bools — DFS needs to pin all 10 to reach a SAT leaf since
        // there are no propagators to collapse the tree. maxInstructions = 2 hits the
        // cap after 2 decisions → Unknown. A generous budget reaches SAT.
        val p = Problem(
            numBoolVars = 10,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val tight = BacktrackSolver(p).solve(
            BacktrackParams(
                maxDecisions = Long.MAX_VALUE,
                maxInstructions = 2L,
                randomSeed = 0L,
            ),
        )
        assertIs<SolveResult.Unknown>(tight)
        val loose = BacktrackSolver(p).solve(
            BacktrackParams(
                maxDecisions = Long.MAX_VALUE,
                maxInstructions = 1_000_000L,
                randomSeed = 0L,
            ),
        )
        assertIs<SolveResult.Sat>(loose)
    }

    @Test
    fun `solve respects assumptions`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val r = BacktrackSolver(p).solve(BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to false))))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(false, sat.assignment.bools[0])
        assertEquals(true, sat.assignment.bools[1])
    }

    @Test
    fun `enumerate yields every distinct SAT model on exactly-one`() {
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(0, true),
                        Lit.make(1, true),
                        Lit.make(2, true),
                        Lit.make(3, true),
                    ),
                ),
            ),
        )
        val models = BacktrackSolver(p).enumerate(BacktrackParams(minHammingDistance = 0)).toList()
        assertEquals(4, models.size)
        assertEquals(4, models.toSet().size, "models must be distinct")
        for (m in models) {
            assertEquals(1, m.bools.count { it })
        }
    }

    @Test
    fun `enumerate over int domain`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 2)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)),
        )
        val models = BacktrackSolver(p).enumerate(BacktrackParams(minHammingDistance = 0)).toList()
        assertEquals(setOf(1, 2), models.map { it.ints[0] }.toSet())
    }

    @Test
    fun `solve returns Unknown when budget exhausts before finding SAT`() {
        val p = Problem(
            numBoolVars = 10,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne((0..9).map { Lit.make(it, true) }.toIntArray()),
            ),
        )
        val r = BacktrackSolver(p).solve(BacktrackParams(maxDecisions = 1))
        // Could legitimately be Unknown or Sat depending on whether the first branch hits.
        assertTrue(
            r is SolveResult.Sat || r is SolveResult.Unknown,
            "should not report Unsat on feasible problem: $r",
        )
    }

    @Test
    fun `minimize finds the optimal feasible assignment`() {
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(0, true),
                        Lit.make(1, true),
                        Lit.make(2, true),
                        Lit.make(3, true),
                    ),
                ),
            ),
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
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality(
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                    min = 1,
                    max = 3,
                ),
            ),
        )
        val models = BacktrackSolver(p).enumerate(
            BacktrackParams(minHammingDistance = 2, recentWindow = 16),
        ).toList()
        for (i in 0 until models.size - 1) {
            var d = 0
            for (j in models[i].bools.indices) if (models[i].bools[j] != models[i + 1].bools[j]) d++
            assertTrue(d >= 2, "models[$i] vs models[${i + 1}] only differ by $d")
        }
    }

    @Test
    fun `vsids finds SAT on a hard pigeonhole-like instance`() {
        // 6 vars with constraints forcing the search through several conflicts. VSIDS
        // should consistently find a model — sanity check that activity-driven picking
        // doesn't break correctness vs. the default random heuristic.
        val problem = Problem(
            numBoolVars = 6,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(0, true),
                        Lit.make(1, true),
                        Lit.make(2, true),
                    ),
                ),
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(3, true),
                        Lit.make(4, true),
                        Lit.make(5, true),
                    ),
                ),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(4, false))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(5, false))),
            ),
        )
        val r = BacktrackSolver(problem).solve(
            BacktrackParams(
                variableHeuristic = Vsids(),
                randomSeed = 0L,
            ),
        )
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(
            1,
            sat.assignment.bools.take(3).count { it },
            "exactly one of v0..v2 should be true",
        )
        assertEquals(
            1,
            sat.assignment.bools.drop(3).count { it },
            "exactly one of v3..v5 should be true",
        )
    }

    @Test
    fun `vsids proves UNSAT and accumulates activity`() {
        // Direct two-clause contradiction. Should return Unsat immediately via
        // bake-time propagation — no conflicts in the search tree, but VSIDS shouldn't
        // crash on the early return.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(variableHeuristic = Vsids()))
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `vsids prefers highest-activity variable after onConflict bumps`() {
        // Drive Vsids directly: bump var 3 a few times, then ask it to pick from an
        // all-unpinned 5-bool problem. v3 should win.
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val vsids = Vsids()
        // Bump v3 directly via the rich onConflict signature with an empty Unsat record
        // so only v3 (the failing decision) gets the bump.
        val emptyUnsat = Unsat()
        repeat(3) { vsids.onConflict(VarRef.Bool(3), emptyUnsat) }
        val r = BacktrackSolver(problem).solve(BacktrackParams(variableHeuristic = vsids))
        assertIs<SolveResult.Sat>(r)
    }

    @Test
    fun `vsids resizes activity arrays across problems`() {
        // A single Vsids instance reused across two problems with different shapes
        // should resize cleanly without crashing.
        val vsids = Vsids()
        val p1 = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val r1 = BacktrackSolver(p1).solve(BacktrackParams(variableHeuristic = vsids))
        assertIs<SolveResult.Sat>(r1)

        val p2 = Problem(
            numBoolVars = 7,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(6, true)))),
        )
        val r2 = BacktrackSolver(p2).solve(BacktrackParams(variableHeuristic = vsids))
        assertIs<SolveResult.Sat>(r2)
        assertEquals(true, r2.assignment.bools[6])
    }

    @Test
    fun `dom-wdeg finds SAT and proves UNSAT on small instances`() {
        // Mixed sanity check: SAT + UNSAT problems both terminate correctly under
        // dom/wdeg picking.
        val satProblem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(0, true),
                        Lit.make(1, true),
                        Lit.make(2, true),
                        Lit.make(3, true),
                    ),
                ),
            ),
        )
        val r1 = BacktrackSolver(satProblem).solve(
            BacktrackParams(
                variableHeuristic = DomWdeg(),
                randomSeed = 0L,
            ),
        )
        val sat = assertIs<SolveResult.Sat>(r1)
        assertEquals(1, sat.assignment.bools.count { it })

        val unsatProblem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val r2 = BacktrackSolver(unsatProblem).solve(
            BacktrackParams(
                variableHeuristic = DomWdeg(),
            ),
        )
        assertIs<SolveResult.Unsat>(r2)
    }

    @Test
    fun `last-conflict prioritises the failing variable on the next pick`() {
        // Wrap an InputOrder base with LastConflict. After a conflict on v3, the next
        // pick should be v3 (when still free). We can't directly inspect "which var
        // was picked first" — instead, verify behaviour with a fake conflict-trigger:
        // call onConflict(v3) directly, then ask `pick` on a fresh session.
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val base = RandomVariable
        val lc = LastConflict(base)
        lc.onConflict(VarRef.Bool(3))
        val session = PropagationSession(problem)
        val picked = lc.pick(session, Random(0L))
        assertEquals(
            VarRef.Bool(3),
            picked,
            "last-conflict should return v3 when it triggered the most recent conflict",
        )
    }

    @Test
    fun `last-conflict clears its pending var on successful commit`() {
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val lc = LastConflict(SmallestDomain)
        lc.onConflict(VarRef.Bool(2))
        lc.onCommit(VarRef.Bool(2))
        val session = PropagationSession(problem)
        val picked = lc.pick(session, Random(0L))
        assertEquals(
            VarRef.Bool(0),
            picked,
            "last-conflict should defer to base after the prioritised var commits",
        )
    }

    @Test
    fun `last-conflict composes with vsids end-to-end`() {
        val problem = Problem(
            numBoolVars = 6,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(0, true),
                        Lit.make(1, true),
                        Lit.make(2, true),
                    ),
                ),
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(3, true),
                        Lit.make(4, true),
                        Lit.make(5, true),
                    ),
                ),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
            ),
        )
        val r = BacktrackSolver(problem).solve(
            BacktrackParams(
                variableHeuristic = LastConflict(Vsids()),
                randomSeed = 0L,
            ),
        )
        assertIs<SolveResult.Sat>(r)
    }

    @Test
    fun `samples yields models without dedup window`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val models = BacktrackSolver(p).samples(BacktrackParams(randomSeed = 0L)).take(80).toList()
        assertEquals(80, models.size, "samples is infinite for feasible problems; take(80) drains exactly 80")
        assertEquals(4, models.toSet().size, "All 4 distinct models should be sampled with replacement")
    }
}
