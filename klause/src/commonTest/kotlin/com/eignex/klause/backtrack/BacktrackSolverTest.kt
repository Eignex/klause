package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.Chb
import com.eignex.klause.backtrack.selector.DomWdeg
import com.eignex.klause.backtrack.selector.DomainMaxRegret
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.LargestDomain
import com.eignex.klause.backtrack.selector.LastConflict
import com.eignex.klause.backtrack.selector.RandomVariable
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.minimizeInt
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.UnsoundnessException
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchConflictResolver
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflict
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BacktrackSolverTest {
    @Test
    fun `a finite selector should attach to its session before branching`() {
        for (optimize in listOf(false, true)) {
            val attached = ArrayList<PropagationSession>()
            val events = ArrayList<String>()
            val selector = object : VariableSelector by InputOrder {
                override fun fresh(): VariableSelector = this
                override fun onSessionCreated(session: PropagationSession) {
                    attached += session
                    events += "attach"
                }
                override fun pick(session: PropagationSession, rng: Random): VarRef? {
                    assertSame(attached.single(), session)
                    events += "pick"
                    return InputOrder.pick(session, rng)
                }
            }
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ))
            val solver = BacktrackSolver(problem.bake())
            val params = BacktrackParams(variableSelector = selector, valueSelector = IndomainMin, randomSeed = 3L)

            if (optimize) assertIs<MinimizeResult.Optimal>(solver.minimize(minimizeInt(0), params))
            else assertIs<SolveResult.Sat>(solver.solve(params))

            assertEquals(1, attached.size)
            assertEquals("attach", events.first())
            assertTrue("pick" in events)
        }
    }

    @Test
    fun `LP free satisfaction should consume a cumulative subset conflict before deciding`() {
        val problem = Problem(0, 4, arrayOf(
            IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(100, 100),
        ), arrayOf<Factor>(Cumulative(
            intArrayOf(0, 1, 2, 3), longArrayOf(2, 2, 2, 1), longArrayOf(2, 2, 2, 1), 3,
        )))

        val result = BacktrackSolver(problem.bake()).solve(BacktrackParams(maxDecisions = 0))

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `LP free minimization should consume a cumulative subset conflict before deciding`() {
        val problem = Problem(0, 4, arrayOf(
            IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(100, 100),
        ), arrayOf<Factor>(Cumulative(
            intArrayOf(0, 1, 2, 3), longArrayOf(2, 2, 2, 1), longArrayOf(2, 2, 2, 1), 3,
        )))

        val result = BacktrackSolver(problem.bake()).minimize(
            problem.minimizeInt(0), BacktrackParams(maxDecisions = 0),
        )

        assertIs<MinimizeResult.Infeasible>(result)
    }

    @Test
    fun `a completed satisfaction call should release its additional peers`() {
        var closes = 0
        val solver = BacktrackSolver(Problem(2, 0, emptyArray(), emptyArray()).bake())

        val result = solver.solve(
            BacktrackParams(componentFactory = {
                listOf(object : SearchComponent, AutoCloseable {
                    override fun close() {
                        closes++
                    }
                })
            }),
        )

        assertIs<SolveResult.Sat>(result)
        assertEquals(1, closes)
    }

    @Test
    fun `closing an abandoned enumeration should release additional peers in reverse order`() {
        val closed = ArrayList<Int>()
        val solver = BacktrackSolver(Problem(2, 0, emptyArray(), emptyArray()).bake())
        val stream = solver.openEnumerate(
            BacktrackParams(componentFactory = {
                List(2) { index ->
                    object : SearchComponent, AutoCloseable {
                        override fun close() {
                            closed += index
                        }
                    }
                }
            }),
        )
        val sample = stream.next()
        val values = BooleanArray(2) { sample.boolValue(it) }

        stream.close()
        stream.close()

        assertEquals(listOf(1, 0), closed)
        assertEquals(values.toList(), List(2) { sample.boolValue(it) })
    }

    @Test
    fun `finite initialization failures should preserve multiple peer cleanup failures`() {
        val primary = IllegalArgumentException("initialization")
        val cleanups = List(2) { IllegalStateException("cleanup-$it") }
        val closed = ArrayList<Int>()
        val solver = BacktrackSolver(Problem(2, 0, emptyArray(), emptyArray()).bake())

        val failure = assertFailsWith<IllegalArgumentException> {
            solver.solve(
                BacktrackParams(componentFactory = {
                    List(2) { index ->
                        object : SearchComponent, AutoCloseable {
                            override fun initialize(context: SearchContext): ComponentResult = throw primary
                            override fun close() {
                                closed += index
                                throw cleanups[index]
                            }
                        }
                    }
                }),
            )
        }

        assertSame(primary, failure)
        assertEquals(listOf(1, 0), closed)
        assertEquals(cleanups.asReversed(), failure.suppressedExceptions)
    }

    @Test
    fun `successful search propagation should inform the finite selector`() {
        for (nativeSat in listOf(false, true)) {
            val deductions = ArrayList<PropagationResult.Implied>()
            val base = InputOrder
            val selector = object : VariableSelector by base {
                override fun fresh(): VariableSelector = this
                override fun onPropagation(implied: PropagationResult.Implied) {
                    deductions += implied
                }
            }
            val problem = Problem(
                2,
                0,
                emptyArray(),
                arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
            )

            val result = BacktrackSolver(problem.bake()).solve(
                BacktrackParams(
                    variableSelector = selector,
                    valueSelector = IndomainMax,
                    nativeSat = nativeSat,
                ),
            )

            assertIs<SolveResult.Sat>(result)
            assertTrue(deductions.any { it.boolValueOrNull(1) == true })
            assertEquals(1.0, result.stats.search.propagations.sum)
        }
    }

    @Test
    fun `learned conflicts should retain the participating assumption core`() {
        val component = object : SearchConflictResolver {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                if (decision is SearchDecision.Bool && decision.literal ushr 1 == 1) {
                    ComponentResult.Conflict()
                } else {
                    ComponentResult.Consistent
                }

            override fun resolveConflict(context: SearchContext): SearchConflictResolution =
                SearchConflictResolution.Backjump(
                    object : SearchLearnedConflict {
                        override val decisionLevel: Int = 0
                        override val lbd: Int = 1
                        override val guardLiterals: IntArray = intArrayOf()
                        override val decisionLevels: IntArray = intArrayOf(1)

                        override fun apply(session: SearchSession): SearchLearnedConflictResult =
                            SearchLearnedConflictResult.Chronological
                    },
                )
        }
        val assumption = Assumptions(bools = mapOf(0 to true))
        val params = BacktrackParams(assumptions = assumption, componentFactory = { listOf(component) })

        val result = BacktrackSolver(Problem(2, 0, emptyArray(), emptyArray()).bake()).solve(params)

        assertEquals(assumption, assertIs<SolveResult.Unsat>(result).assumptionCore)
    }

    @Test
    fun `component factory should drive theory branches through legacy DFS`() {
        val branch = SearchDecision.Theory(object : SearchTheoryDecision {})
        fun brancher() = object : SearchBrancher {
            private var selected = false

            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                selected = decision == branch
                return ComponentResult.Consistent
            }

            override fun retract(decisionLevel: Int) {
                if (decisionLevel == 0) selected = false
            }

            override fun nextBranch(context: SearchContext): List<SearchDecision>? =
                if (selected) null else listOf(branch)

            override fun check(context: SearchContext): ComponentCheck =
                if (selected) ComponentCheck.Feasible else ComponentCheck.Indeterminate
        }
        val params = BacktrackParams(componentFactory = { listOf(brancher()) })

        val result = BacktrackSolver(Problem(0, 0, emptyArray(), emptyArray()).bake()).solve(params)

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `a leaf violating a factor should fail as unsound`() {
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
    fun `unsat core should capture chained propagation through intermediate factors`() {
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
    fun `maxInstructions should cap the search when smaller than maxDecisions`() {
        val p = Problem(
            numBoolVars = 10,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality(IntArray(10) { Lit.make(it, true) }, min = 0, max = 10)),
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
    fun `solve should respect assumptions`() {
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
    fun `returned assumption core should retain transitive propagated premises`() {
        val problem = Problem(
            4,
            0,
            emptyArray(),
            arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(3, false))),
            ),
        ).bake()
        val params = BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to true, 1 to true, 3 to true)))
        val solver = BacktrackSolver(problem)

        val result = assertIs<SolveResult.Unsat>(solver.solve(params))
        val core = requireNotNull(result.assumptionCore)

        assertIs<SolveResult.Unsat>(solver.solve(params.copy(assumptions = core)))
    }

    @Test
    fun `enumerate should yield every distinct model of exactly-one`() {
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
        assertEquals(4, models.toSet().size)
        for (m in models) {
            assertEquals(1, m.bools.count { it })
        }
    }

    @Test
    fun `minimize should find the optimal feasible assignment`() {
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
    fun `a deadline firing inside propagation should yield Unknown rather than a solution`() {
        // cancelFloor 0 lets the deadline cut the very first fixpoint short.
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
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `a column open below should be solved under every domain-reading selector`() {
        val selectors = listOf(
            "DomWdeg" to DomWdeg(),
            "SmallestDomain" to SmallestDomain,
            "LargestDomain" to LargestDomain,
            "InputOrder" to InputOrder,
            "RandomVariable" to RandomVariable,
            "DomainMaxRegret" to DomainMaxRegret,
            "Chb" to Chb(),
            "Vsids" to Vsids(),
            "LastConflict" to LastConflict(DomWdeg()),
        )
        for ((name, selector) in selectors) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
                factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, -5)),
            )

            val result = BacktrackSolver(problem.bake()).solve(
                BacktrackParams(variableSelector = selector, randomSeed = 0L),
            )

            val sat = assertIs<SolveResult.Sat>(result, name)
            assertTrue(
                sat.assignment.ints[0] <= -5L,
                "$name answered ${'$'}{sat.assignment.ints[0]}, which breaks the row",
            )
        }
    }

    @Test
    fun `an equality on an unbounded column should propagate to its single value`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7)),
        )

        val result = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        assertEquals(7L, assertIs<SolveResult.Sat>(result).assignment.ints[0])
    }

    @Test
    fun `a global over a column no span can enumerate should answer without walking it`() {
        // A truncated span here would walk 2^31 values and answer from the wrong set.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, Long.MAX_VALUE), IntDomain(0, Long.MAX_VALUE)),
            factors = arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            ),
        )

        val result = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val ints = assertIs<SolveResult.Sat>(result).assignment.ints
        assertTrue(ints[0] <= 3L, "the row bounds the indexed column, got ${'$'}{ints[0]}")
        assertTrue(ints[0] != ints[1])
    }

    @Test
    fun `backtrack solve should prove a strict order cycle unsat`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, -1),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, -1),
                Linear(intArrayOf(1, -1), intArrayOf(2, 0), LinearOp.LE, -1),
            ),
        )
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(variableSelector = InputOrder, randomSeed = 0L))
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `branch and bound should not over-prune with negative coefficients and holes`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(
                IntDomain(0, 4),
                IntDomain(0, 4).excludeValue(2),
                IntDomain(0, 4).excludeValue(0).excludeValue(4),
            ),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1L, 2L, -1L))

        val truth = assertIs<MinimizeResult.Optimal>(
            BruteForceSolver(problem.bake()).minimize(objective, BruteForceParams()),
        )
        val got = assertIs<MinimizeResult.WithSample>(
            BacktrackSolver(problem.bake()).minimize(objective, BacktrackParams(randomSeed = 3L)),
        )

        assertEquals(truth.objective, got.objective)
    }

    @Test
    fun `improvements should strictly decrease then end with optimal`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val obj = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L, variableSelector = InputOrder, valueSelector = IndomainMax)

        val seq = BacktrackSolver(problem.bake()).improvements(obj, params).toList()

        val terminal = assertIs<MinimizeResult.Optimal>(seq.last())
        val intermediate = seq.dropLast(1).map { assertIs<MinimizeResult.BestFound>(it).objective }
        assertEquals(0.0, terminal.objective)
        assertTrue(intermediate.isNotEmpty() && intermediate.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun `improvements should yield a single infeasible on an infeasible problem`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(3, 5)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L))
        val seq = BacktrackSolver(problem.bake()).improvements(obj, BacktrackParams(randomSeed = 0L)).toList()
        assertEquals(1, seq.size)
        assertIs<MinimizeResult.Infeasible>(seq[0])
    }

    @Test
    fun `minimize should equal the last improvement`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 7)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L))
        val solver = BacktrackSolver(problem.bake())
        val params = BacktrackParams(randomSeed = 0L, variableSelector = InputOrder, valueSelector = IndomainMin)
        val viaMinimize = solver.minimize(obj, params)
        val viaImprovements = solver.improvements(obj, params).last()
        assertEquals(viaMinimize.withoutStats(), viaImprovements.withoutStats())
    }

    @Test
    fun `solve should complete columns no factor reads without branching on them`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        ).bake()
        val tiered = TieredVariableSelector(
            listOf(SearchTier(intArrayOf(1, 2), intArrayOf(0), TierVarSelect.InputOrder, IndomainMax)),
            SmallestDomain,
        )
        for (selector in listOf(InputOrder, SmallestDomain, RandomVariable, Vsids(), DomWdeg(), Chb(), tiered)) {
            val result = BacktrackSolver(problem).solve(BacktrackParams(variableSelector = selector, randomSeed = 0L))

            assertIs<SolveResult.Sat>(result)
            assertEquals(0.0, result.stats.search.nodes.sum)
        }
    }

    @Test
    fun `enumerate should tell models apart by columns no factor reads`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )

        val models = BacktrackSolver(problem.bake()).enumerate(BacktrackParams(minHammingDistance = 0)).toList()

        assertEquals(16, models.toSet().size)
    }

    @Test
    fun `minimize should decide columns only the objective reads`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val objective = LinearObjective(boolWeights = longArrayOf(0L, -2L), intCoefficients = longArrayOf(-1L))

        val result = BacktrackSolver(problem.bake())
            .minimize(objective, BacktrackParams(valueSelector = IndomainMin, randomSeed = 0L))

        assertIs<MinimizeResult.Optimal>(result)
        assertEquals(-5.0, result.objective)
    }

    @Test
    fun `solve should keep assumptions on columns no factor reads`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val params = BacktrackParams(assumptions = Assumptions(bools = mapOf(2 to true), ints = mapOf(0 to 3L)))

        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(problem.bake()).solve(params))

        assertEquals(true, sat.assignment.bools[2])
        assertEquals(3L, sat.assignment.ints[0])
    }
}

private fun MinimizeResult.withoutStats(): MinimizeResult = when (this) {
    is MinimizeResult.Optimal -> copy(stats = SolveStats.EMPTY)
    is MinimizeResult.BestFound -> copy(stats = SolveStats.EMPTY)
    is MinimizeResult.Unbounded -> copy(stats = SolveStats.EMPTY)
    is MinimizeResult.Infeasible -> copy(stats = SolveStats.EMPTY)
    is MinimizeResult.Unknown -> copy(stats = SolveStats.EMPTY)
}
