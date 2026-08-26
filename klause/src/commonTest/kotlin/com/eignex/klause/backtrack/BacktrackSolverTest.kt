package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.allDifferent
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BacktrackSolverTest {

    @Test
    fun `satisfaction restart refreshes the clause exchange`() {
        var restarts = 0
        val decision = object : com.eignex.klause.solver.search.SearchTheoryDecision {}
        val component = object : com.eignex.klause.solver.search.SearchBrancher {
            private var leaf = false

            override fun assert(
                decision: com.eignex.klause.solver.search.SearchDecision,
                context: com.eignex.klause.solver.search.SearchContext,
            ): com.eignex.klause.solver.search.ComponentResult =
                com.eignex.klause.solver.search.ComponentResult.Consistent

            override fun onRestart(context: com.eignex.klause.solver.search.SearchContext) {
                leaf = true
            }

            override fun nextBranch(
                context: com.eignex.klause.solver.search.SearchContext,
            ): List<com.eignex.klause.solver.search.SearchDecision>? = if (leaf) {
                null
            } else {
                listOf(com.eignex.klause.solver.search.SearchDecision.Theory(decision))
            }
        }
        val exchange = object : ClauseExchange {
            override fun onRestart(session: PropagationSession) {
                restarts++
            }
        }
        val result = BacktrackSolver(
            Problem(numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray()).bake(),
        ).solve(
            BacktrackParams(
                lubyRestartBase = 1,
                clauseExchange = exchange,
                componentFactory = { listOf(component) },
            ),
        )

        assertIs<SolveResult.Sat>(result)
        assertEquals(1, restarts)
    }

    @Test
    fun `learned conflicts retain the participating assumption core`() {
        val component = object : com.eignex.klause.solver.search.SearchConflictResolver {
            override fun assert(
                decision: com.eignex.klause.solver.search.SearchDecision,
                context: com.eignex.klause.solver.search.SearchContext,
            ): com.eignex.klause.solver.search.ComponentResult = if (
                decision is com.eignex.klause.solver.search.SearchDecision.Bool && decision.literal ushr 1 == 1
            ) {
                com.eignex.klause.solver.search.ComponentResult.Conflict()
            } else {
                com.eignex.klause.solver.search.ComponentResult.Consistent
            }

            override fun resolveConflict(
                context: com.eignex.klause.solver.search.SearchContext,
            ): com.eignex.klause.solver.search.SearchConflictResolution =
                com.eignex.klause.solver.search.SearchConflictResolution.Backjump(
                    object : com.eignex.klause.solver.search.SearchLearnedConflict {
                        override val decisionLevel: Int = 0
                        override val lbd: Int = 1
                        override val guardLiterals: IntArray = intArrayOf()
                        override val decisionLevels: IntArray = intArrayOf(1)

                        override fun apply(
                            session: com.eignex.klause.solver.search.SearchSession,
                        ): com.eignex.klause.solver.search.SearchLearnedConflictResult =
                            com.eignex.klause.solver.search.SearchLearnedConflictResult.Chronological
                    },
                )
        }
        val assumption = Assumptions(bools = mapOf(0 to true))
        val result = BacktrackSolver(
            Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray()).bake(),
        ).solve(
            BacktrackParams(
                assumptions = assumption,
                componentFactory = { listOf(component) },
            ),
        )

        assertEquals(assumption, assertIs<SolveResult.Unsat>(result).assumptionCore)
    }

    @Test
    fun `component factory drives theory branches through legacy DFS`() {
        val branch = object : com.eignex.klause.solver.search.SearchTheoryDecision {}
        val result = BacktrackSolver(
            Problem(numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray()).bake(),
        ).solve(
            BacktrackParams(
                componentFactory = {
                    listOf(object : com.eignex.klause.solver.search.SearchBrancher {
                        private var selected = false

                        override fun assert(
                            decision: com.eignex.klause.solver.search.SearchDecision,
                            context: com.eignex.klause.solver.search.SearchContext,
                        ): com.eignex.klause.solver.search.ComponentResult {
                            selected = decision == com.eignex.klause.solver.search.SearchDecision.Theory(branch)
                            return com.eignex.klause.solver.search.ComponentResult.Consistent
                        }

                        override fun retract(decisionLevel: Int) {
                            if (decisionLevel == 0) selected = false
                        }

                        override fun nextBranch(
                            context: com.eignex.klause.solver.search.SearchContext,
                        ): List<com.eignex.klause.solver.search.SearchDecision>? = if (selected) {
                            null
                        } else {
                            listOf(
                                com.eignex.klause.solver.search.SearchDecision.Theory(branch),
                            )
                        }

                        override fun check(
                            context: com.eignex.klause.solver.search.SearchContext,
                        ): com.eignex.klause.solver.search.ComponentCheck = if (selected) {
                            com.eignex.klause.solver.search.ComponentCheck.Feasible
                        } else {
                            com.eignex.klause.solver.search.ComponentCheck.Indeterminate
                        }
                    })
                },
            ),
        )

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `component factory refutes finite CP leaves through the shared session`() {
        val problem = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val result = BacktrackSolver(problem.bake()).solve(
            BacktrackParams(
                componentFactory = {
                    listOf(object : com.eignex.klause.solver.search.TheoryComponent {
                        override fun check(
                            context: com.eignex.klause.solver.search.SearchContext,
                        ): com.eignex.klause.solver.search.ComponentCheck =
                            com.eignex.klause.solver.search.ComponentCheck.Infeasible()
                    })
                },
            ),
        )

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `component assertion conflict prunes a CP branch through the shared session`() {
        val problem = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val result = BacktrackSolver(problem.bake()).solve(
            BacktrackParams(
                componentFactory = {
                    listOf(object : com.eignex.klause.solver.search.TheoryComponent {
                        override fun assert(
                            decision: com.eignex.klause.solver.search.SearchDecision,
                            context: com.eignex.klause.solver.search.SearchContext,
                        ): com.eignex.klause.solver.search.ComponentResult =
                            com.eignex.klause.solver.search.ComponentResult.Conflict(
                                com.eignex.klause.solver.search.SearchExplanation(
                                    intArrayOf(
                                        (decision as com.eignex.klause.solver.search.SearchDecision.Bool).literal xor 1,
                                    ),
                                ),
                            )
                    })
                },
            ),
        )

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `solve returns SAT with valid witness on simple clause`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val r = BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 0L))
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
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `tight cumulative packing solves without overflowing conflict-clause minimization`() {
        // A tight unary-resource cumulative drives many conflicts whose atom antecedents form
        // deep (and occasionally cyclic) implication chains, so self-subsuming-resolution
        // minimization must walk them without recursing off the stack or indexing past the
        // atom-antecedent array. Six duration-2 tasks at capacity 1 must pack back-to-back into
        // the horizon [0, 12); the start domains [0, 10] just admit the even-slot schedule, so
        // the search is forced through heavy conflict analysis. It must return a valid
        // non-overlapping witness, not crash.
        val n = 6
        val factor = Cumulative(
            starts = IntArray(n) { it },
            durations = LongArray(n) { 2L },
            resources = LongArray(n) { 1L },
            capacity = 1L,
        )
        val p = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 10) },
            factors = arrayOf<Factor>(factor),
        )
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 0L)))
        val starts = sat.assignment.ints
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val noOverlap = starts[i] + 2 <= starts[j] || starts[j] + 2 <= starts[i]
                assertTrue(noOverlap, "tasks $i@${starts[i]} and $j@${starts[j]} overlap under capacity 1")
            }
        }
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
        val verdict = assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
        val core = verdict.core ?: error("expected propagation-derived unsat core, got null")
        assertEquals(
            setOf(0, 1),
            core.factorIds.toSet(),
            "core should mention both contradicting clauses, got ${core.factorIds.toList()}",
        )
    }

    @Test
    fun `unsat core captures chained propagation through intermediate factors`() {
        // Four clauses chained: x0 -> x1 -> x2 -> not x2. Bake-time propagation forces
        // x0 = true (unit clause), then x1 = true (clause says not x0 or x1), then x2 = true,
        // then the final clause requires x2 = false, a contradiction. All four factors
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
        val verdict = assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
        val core = verdict.core ?: error("expected propagation-derived unsat core, got null")
        assertEquals(
            setOf(0, 1, 2, 3),
            core.factorIds.toSet(),
            "transitive core should include every link in the propagation chain, got ${core.factorIds.toList()}",
        )
    }

    @Test
    fun `watcher index routes wakeups only on the false-going literal`() {
        // Clause `+v0 or +v1 or +v2`. Initial watches are on v0 and v1. After
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
            state.watches.byLit[Lit.make(0, true)].size,
            "clause should be in watcher list for +v0",
        )
        assertEquals(
            1,
            state.watches.byLit[Lit.make(1, true)].size,
            "clause should be in watcher list for +v1",
        )
        assertEquals(
            0,
            state.watches.byLit[Lit.make(0, false)].size,
            "clause should not be woken when -v0 becomes false (i.e., v0 = true)",
        )
        assertEquals(
            0,
            state.watches.byLit[Lit.make(2, true)].size,
            "v2 is not yet a watched literal",
        )
    }

    @Test
    fun `cardinality watched literals propagate at-least-K under pin pressure`() {
        // AtLeast-2 over 8 vars. Pin 5 of them to false: only 3 positive literals are
        // non-false; need 2 true. Pin a 6th to false and only 2 non-false remain; both
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
        val result = BacktrackSolver(problem.bake()).solve(
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
        // AtMost-2 over 8 vars. Pin 2 of them to true and no more can be true; the
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
        val result = BacktrackSolver(problem.bake()).solve(
            BacktrackParams(
                assumptions = Assumptions(bools = pins),
            ),
        )
        val sat = assertIs<SolveResult.Sat>(result)
        for (v in 2 until 8) {
            assertEquals(
                false,
                sat.assignment.bools[v],
                "v$v should be unit-forced false to keep count <= 2, got ${sat.assignment.bools[v]}",
            )
        }
    }

    @Test
    fun `cardinality bilateral exactly-one detects unsat under conflicting pins`() {
        // ExactlyOne over 4 vars with two of them pinned true is a contradiction.
        // The watched scheme has both at-least (2 watches) and at-most (4 watches);
        // the at-most side should fire and detect the over-budget condition.
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality.exactlyOne(IntArray(4) { Lit.make(it, true) })),
        )
        val r = BacktrackSolver(problem.bake()).solve(
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
        // false via assumptions, the clause becomes unit on v49, so propagation pins
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
        val result = BacktrackSolver(problem.bake()).solve(
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
        // Two linear constraints: x >= 5 and x <= 3. Each individually is fine on
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
        val verdict = assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
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
        // cap after 2 decisions, giving Unknown. A generous budget reaches SAT.
        val p = Problem(
            numBoolVars = 10,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val tight = BacktrackSolver(p.bake()).solve(
            BacktrackParams(
                maxDecisions = Long.MAX_VALUE,
                maxInstructions = 2L,
                randomSeed = 0L,
            ),
        )
        assertIs<SolveResult.Unknown>(tight)
        val loose = BacktrackSolver(p.bake()).solve(
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
        val r = BacktrackSolver(p.bake()).solve(BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to false))))
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
        val models = BacktrackSolver(p.bake()).enumerate(BacktrackParams(minHammingDistance = 0)).toList()
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
        val models = BacktrackSolver(p.bake()).enumerate(BacktrackParams(minHammingDistance = 0)).toList()
        assertEquals(setOf(1L, 2L), models.map { it.ints[0] }.toSet())
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
        val r = BacktrackSolver(p.bake()).solve(BacktrackParams(maxDecisions = 1))
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
        val obj = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val best = BacktrackSolver(p.bake()).minimize(obj, BacktrackParams(randomSeed = 0L)).assignment
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
        val models = BacktrackSolver(p.bake()).enumerate(
            BacktrackParams(minHammingDistance = 2, recentWindow = 16),
        ).toList()
        for (i in 0 until models.size - 1) {
            var d = 0
            for (j in models[i].bools.indices) if (models[i].bools[j] != models[i + 1].bools[j]) d++
            assertTrue(d >= 2, "models[$i] vs models[${i + 1}] only differ by $d")
        }
    }

    @Test
    fun `samples yields models without dedup window`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val models = BacktrackSolver(p.bake()).samples(BacktrackParams(randomSeed = 0L)).take(80).toList()
        assertEquals(80, models.size, "samples is infinite for feasible problems; take(80) drains exactly 80")
        assertEquals(4, models.toSet().size, "All 4 distinct models should be sampled with replacement")
    }

    /**
     * Complete enumeration must terminate and report the brute-force feasible set exactly —
     * every solution once, no revisits. Equality channels plus random clauses drive conflicts
     * whose backjumps unwind the frames of already-yielded leaves; without a blocking nogood
     * per yielded solution the search re-finds the same leaves indefinitely.
     */
    @Test
    fun `enumeration over equality channels matches the brute-force set exactly once`() {
        val lo = 1
        val hi = 5
        val span = hi - lo + 1
        for (seed in 0 until 12) {
            val rnd = Random(seed)
            val numDrivers = 2
            fun cx(v: Int) = numDrivers + (v - lo)
            fun cy(v: Int) = numDrivers + span + (v - lo)
            val numBool = numDrivers + 2 * span
            val factors = ArrayList<Factor>()
            for (v in lo..hi) {
                factors.add(
                    ReifiedLinear(
                        auxBoolVar = cx(v),
                        coeffs = intArrayOf(1),
                        vars = intArrayOf(0),
                        op = LinearOp.EQ,
                        bound = v,
                    ),
                )
                factors.add(
                    ReifiedLinear(
                        auxBoolVar = cy(v),
                        coeffs = intArrayOf(1),
                        vars = intArrayOf(1),
                        op = LinearOp.EQ,
                        bound = v,
                    ),
                )
            }
            val clauses = ArrayList<IntArray>()
            repeat(10) {
                val lits = IntArray(3) {
                    val b = rnd.nextInt(numBool)
                    Lit.make(b, rnd.nextBoolean())
                }
                clauses.add(lits)
                factors.add(Clause(lits))
            }
            val p = Problem(
                numBoolVars = numBool,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(lo.toLong(), hi.toLong()), IntDomain(lo.toLong(), hi.toLong())),
                factors = factors.toTypedArray(),
            )

            val brute = HashSet<List<Int>>()
            for (x in lo..hi) {
                for (y in lo..hi) {
                    for (mask in 0 until (1 shl numDrivers)) {
                        val bools = BooleanArray(numBool)
                        for (d in 0 until numDrivers) bools[d] = (mask shr d) and 1 == 1
                        for (v in lo..hi) {
                            bools[cx(v)] = x == v
                            bools[cy(v)] = y == v
                        }
                        val ok = clauses.all { cl ->
                            cl.any { lit -> bools[Lit.variable(lit)] == Lit.isPositive(lit) }
                        }
                        if (ok) brute.add(bools.map { if (it) 1 else 0 } + listOf(x, y))
                    }
                }
            }

            val params =
                BacktrackParams(randomSeed = seed.toLong(), variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val raw = BacktrackSolver(p.bake()).enumerate(params).take(brute.size + 10)
                .map { s -> s.bools.map { if (it) 1 else 0 } + s.ints.map { it.toInt() } }.toList()
            assertEquals(raw.size, raw.toHashSet().size, "seed $seed: a solution was yielded more than once")
            assertEquals(brute, raw.toHashSet(), "seed $seed: enumeration must equal the brute-force feasible set")
        }
    }

    @Test
    fun `a deadline firing inside propagation yields Unknown rather than a solution`() {
        // Satisfiable (x0 or x1). With cancelFloor 0 a fired deadline cuts the very first fixpoint
        // short, leaving an under-propagated state the search must not report as SAT — the honest
        // verdict is Unknown, never a solution built on a cut-short fixpoint.
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val fired = BacktrackSolver(p.bake()).solve(
            BacktrackParams(randomSeed = 0L, cancellation = Cancellation { true }, propagationCancelFloor = 0),
        )
        assertIs<SolveResult.Unknown>(fired)
        // With no deadline the same problem is solved.
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 0L)))
    }

    private class ThreeDistinct : VariableSchema() {
        val a by intVar(min = 1, max = 3)
        val b by intVar(min = 1, max = 3)
        val c by intVar(min = 1, max = 3)
        val unique by constraint { allDifferent(a, b, c) }
    }

    @Test
    fun `constructor accepts a compiled problem`() {
        val compiled = ThreeDistinct().compile()
        assertIs<SolveResult.Sat>(BacktrackSolver(compiled).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `constructor accepts a schema and compiles it`() {
        assertIs<SolveResult.Sat>(BacktrackSolver(ThreeDistinct()).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `enumerate with default params yields every distinct model`() {
        val schema = ThreeDistinct()
        val compiled = schema.compile()
        val decoded = BacktrackSolver(compiled).enumerate()
            .map { Triple(compiled.decode(schema.a, it), compiled.decode(schema.b, it), compiled.decode(schema.c, it)) }
            .toSet()
        assertEquals(6, decoded.size)
    }
}
