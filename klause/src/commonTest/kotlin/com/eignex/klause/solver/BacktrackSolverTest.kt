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
