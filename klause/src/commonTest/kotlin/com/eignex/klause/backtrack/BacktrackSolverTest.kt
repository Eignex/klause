package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.UnsoundnessException
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class BacktrackSolverTest {

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
    fun `a leaf whose assignment violates a factor fails as unsound`() {
        // A selector that stops before any column is fixed stands in for an engine defect that reads an open
        // node as a solved leaf: the minima it reports put both columns of the AllDifferent at 0.
        val stopsEarly = object : VariableSelector {
            override fun pick(session: PropagationSession, rng: Random): VarRef? = null

            override fun fresh(): VariableSelector = this
        }
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2)),
        )

        val solver = BacktrackSolver(problem.bake())

        assertFailsWith<UnsoundnessException> { solver.solve(BacktrackParams(variableSelector = stopsEarly)) }
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
}
