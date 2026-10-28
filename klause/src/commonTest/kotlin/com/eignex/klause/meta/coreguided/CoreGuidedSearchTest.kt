package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.formats.opb.toProblem
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BuiltInPropagationProjectionProvider
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.PreparingSearchComponent
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SchedulingCancellation
import com.eignex.klause.util.workScheduled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class CoreGuidedSearchTest {
    @Test
    fun `wide integer cost frontiers should exhaust their allowance without claiming attainment`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1_000_000_000L) }, arrayOf(
            Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1_000_000_000L),
        )).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE))
        val search = CoreGuidedSearch(problem, objective, BacktrackParams(nativeSat = false, randomSeed = 3L),
            maximumWork = 2L)

        val stopped = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { error("unattained objective") }
        val charged = search.work
        val resumed = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { error("renewed allowance") }

        assertEquals(TerminationReason.BudgetExhausted, assertIs<MinimizeResult.Unknown>(stopped).reason)
        assertEquals(stopped, resumed)
        assertEquals(charged, search.work)
        assertTrue(search.stats.coreGuided.cores > 0L)
        assertTrue(search.lowerBound < BigFraction.ofLong(Long.MAX_VALUE) * BigFraction.ofLong(1_000_000_000L))
    }

    @Test
    fun `work scheduled queries should retain policy through preparation and ignore the slice clock`() {
        val clock = TestTimeSource()
        var marked = false
        val token = object : SchedulingCancellation {
            override val workScheduled: Boolean = true
            override fun isCancelled(): Boolean = false
        }
        val params = BacktrackParams(nativeSat = false, componentFactory = {
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck {
                    marked = context.executionControls.cancellation.workScheduled()
                    clock += 2.seconds
                    return ComponentCheck.Feasible
                }
            })
        })
        val objective = LinearObjective(longArrayOf(1))
        val search = CoreGuidedSearch(Problem(1, 0, emptyArray(), emptyArray()).bake(), objective,
            params, timeSource = clock)

        val result = search.runSlice(token, 1_000L, -1L) {}

        assertTrue(marked)
        assertEquals(0L, objective.evaluateLong(assertIs<MinimizeResult.Optimal>(result).sample))
    }

    @Test
    fun `cancelled prepared delegate construction should retain its query for retry`() {
        var cancelled = false
        var interrupted = false
        val params = BacktrackParams(nativeSat = false, componentFactory = {
            if (!interrupted) {
                interrupted = true
                cancelled = true
            }
            emptyList()
        })
        val objective = LinearObjective(longArrayOf(1))
        val search = CoreGuidedSearch(Problem(1, 0, emptyArray(), emptyArray()).bake(), objective, params)

        val paused = search.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1L) {
            error("cancelled construction")
        }
        val charged = search.preparationWork
        cancelled = false
        val resumed = search.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1L) {}

        assertNull(paused)
        assertTrue(interrupted)
        assertEquals(0L, objective.evaluateLong(assertIs<MinimizeResult.Optimal>(resumed).sample))
        assertTrue(search.preparationWork > charged)
    }

    @Test
    fun `paused source verification should retain its candidate without another query`() {
        val hard = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val source = Problem(2, 0, emptyArray(), Array(3) { hard })
        var augmented = false
        var cancelled = false
        var verificationStops = 0
        val providers = observingHard(hard) { state ->
            if (state.problem.numBoolVars > source.numBoolVars) augmented = true
            else if (augmented && verificationStops++ == 0) cancelled = true
        }
        val objective = LinearObjective(longArrayOf(1, 1))
        val search = CoreGuidedSearch(source.bake(providers.prepare(source)), objective,
            BacktrackParams(nativeSat = false, randomSeed = 3L))

        val paused = search.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1L) {
            error("unchecked publication")
        }
        val queries = search.queryCount
        val verification = search.verificationVisits
        cancelled = false
        val resumed = search.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1L) {}

        assertNull(paused)
        assertTrue(verificationStops > 0)
        assertEquals(1L, objective.evaluateLong(assertIs<MinimizeResult.Optimal>(resumed).sample))
        assertEquals(queries, search.queryCount)
        assertTrue(search.verificationVisits > verification)
    }

    @Test
    fun `native segment interruption after a decision should pause the core query`() {
        val clock = TestTimeSource()
        val interrupter = ClockJumpOnDecision(clock)
        val params = BacktrackParams(
            nativeSat = false,
            variableSelector = InputOrder,
            valueSelector = IndomainMin,
            componentFactory = { listOf(interrupter) },
        )
        val problem = Problem(
            40,
            0,
            emptyArray(),
            Array(19) {
                Clause(intArrayOf(Lit.make(2 * it + 1, true), Lit.make(2 * it + 2, true)))
            },
        ).bake()
        val objective = LinearObjective(longArrayOf(1))
        val search = CoreGuidedSearch(problem, objective, params, timeSource = clock)

        val paused = search.runSlice(Cancellation.Never, 1_000L, -1L) { error("interrupted query") }

        assertTrue(interrupter.interrupted)
        assertNull(paused)
        assertFalse(search.isDone)
        val resumed = assertIs<MinimizeResult.Optimal>(search.runSlice(Cancellation.Never, 1_000L, -1L) {})
        assertEquals(0L, objective.evaluateLong(resumed.sample))
        search.close()
    }

    @Test
    fun `an unsupported native component without cancellation should retire the core query`() {
        val params = BacktrackParams(nativeSat = false, componentFactory = {
            listOf(object : SearchComponent {
                override fun check(context: SearchContext): ComponentCheck = ComponentCheck.Indeterminate
            })
        })
        val search = CoreGuidedSearch(
            Problem(2, 0, emptyArray(), emptyArray()).bake(),
            LinearObjective(longArrayOf(1)),
            params,
        )

        val stopped = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { error("unchecked candidate") }
        val charged = search.work
        val resumed = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { error("renewed unsupported query") }

        assertEquals(TerminationReason.Unsupported, assertIs<MinimizeResult.Unknown>(stopped).reason)
        assertTrue(search.isDone)
        assertEquals(stopped, resumed)
        assertEquals(charged, search.work)
        search.close()
    }

    @Test
    fun `native segment interruption should pause before its containing allowance is exhausted`() {
        val clock = TestTimeSource()
        var interrupted = false
        val params = BacktrackParams(componentFactory = {
            listOf(object : PreparingSearchComponent {
                override fun prepareInitialization(context: SearchContext): Iterator<ComponentResult?> = sequence {
                    if (!interrupted) {
                        interrupted = true
                        clock += 2.seconds
                    }
                    yield(ComponentResult.Consistent)
                }.iterator()
            })
        })
        val objective = LinearObjective(longArrayOf(1))
        val search = CoreGuidedSearch(
            Problem(1, 0, emptyArray(), emptyArray()).bake(),
            objective,
            params,
            timeSource = clock,
        )

        val paused = search.runSlice(Cancellation.Never, 1_000L, -1L) { error("interrupted query") }

        assertNull(paused)
        assertTrue(interrupted)
        assertFalse(search.isDone)
        val resumed = assertIs<MinimizeResult.Optimal>(search.runSlice(Cancellation.Never, 1_000L, -1L) {})
        assertEquals(0L, objective.evaluateLong(resumed.sample))
        search.close()
    }

    @Test
    fun `native decision allowance exhaustion should not reopen a core query`() {
        val problem = Problem(
            5,
            0,
            emptyArray(),
            arrayOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(3, true), Lit.make(4, true))),
            ),
        ).bake()
        val objective = LinearObjective(longArrayOf(1))
        for (params in listOf(
            BacktrackParams(nativeSat = false, maxDecisions = 1L),
            BacktrackParams(nativeSat = false, maxInstructions = 1L),
            BacktrackParams(nativeSat = false, nodeBudget = NodeBudget(1L)),
        )) {
            val clock = TestTimeSource()
            val interrupter = ClockJumpOnDecision(clock)
            val bounded = params.copy(componentFactory = { listOf(interrupter) })
            val search = CoreGuidedSearch(problem, objective, bounded, timeSource = clock)

            val stopped = search.runSlice(Cancellation.Never, 1_000L, -1L) {}
            val charged = search.work
            val resumed = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { error("renewed decisions") }

            assertEquals(TerminationReason.BudgetExhausted, assertIs<MinimizeResult.Unknown>(stopped).reason)
            assertTrue(search.isDone)
            assertEquals(stopped, resumed)
            assertEquals(charged, search.work)
            search.close()
        }
    }

    @Test
    fun `source verification should observe the cumulative core active time allowance`() {
        val clock = TestTimeSource()
        val hard = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val source = Problem(2, 0, emptyArray(), Array(2_049) { hard })
        var augmented = false
        var verificationFires = 0
        val providers = observingHard(hard) { state ->
            if (state.problem.numBoolVars > source.numBoolVars) {
                augmented = true
            } else if (augmented) {
                if (verificationFires++ == 0) clock += 3.seconds
            }
        }
        val search = CoreGuidedSearch(
            source.bake(providers.prepare(source)),
            LinearObjective(longArrayOf(1, 1)),
            BacktrackParams(nativeSat = false),
            maximumMillis = 2_000L,
            timeSource = clock,
        )

        val stopped = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { error("unverified publication") }
        val resumed = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { error("renewed verification") }

        assertEquals(TerminationReason.BudgetExhausted, assertIs<MinimizeResult.Unknown>(stopped).reason)
        assertTrue(verificationFires in 1 until source.numFactors)
        assertEquals(stopped, resumed)
        assertEquals(BigFraction.ONE, search.lowerBound)
        search.close()
    }

    @Test
    fun `initial residual query should retain the captured source projection and root deductions`() {
        val hard = Clause(intArrayOf(Lit.make(0, true)))
        val source = Problem(1, 0, emptyArray(), arrayOf(hard))
        val observedModels = mutableListOf<Problem>()
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider {
                PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) { view ->
                    val delegate = BuiltInPropagationProjectionProvider.create(view, null)
                    object : Propagator by delegate {
                        override fun propagate(state: PropagationState, factorId: Int): Boolean {
                            observedModels += state.problem
                            return delegate.propagate(state, factorId)
                        }
                    }
                }
            },
        )
        val baked = source.bake(providers.prepare(source))
        val objective = LinearObjective(longArrayOf(-1))
        observedModels.clear()

        val result = CoreGuidedSolver(baked).minimize(objective, BacktrackParams(nativeSat = false))

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertTrue(optimal.sample.boolValue(0))
        assertEquals(-1L, objective.evaluateLong(optimal.sample))
        assertTrue(observedModels.isNotEmpty())
        assertTrue(observedModels.all { it === baked })
    }

    @Test
    fun `private assumption traversal should resume across decision work pauses`() {
        val problem = Problem(
            5,
            0,
            emptyArray(),
            arrayOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(3, true), Lit.make(4, true))),
            ),
        ).bake()
        val objective = LinearObjective(longArrayOf(1))
        val search = CoreGuidedSearch(problem, objective, BacktrackParams(nativeSat = false))
        var result: MinimizeResult? = null

        repeat(10) {
            if (result == null) result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) {}
        }

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(1L, objective.evaluateLong(optimal.sample))
        assertTrue(optimal.sample.boolValue(1) || optimal.sample.boolValue(2))
        assertTrue(optimal.sample.boolValue(3) || optimal.sample.boolValue(4))
        search.close()
    }

    @Test
    fun `active preparation time should not renew after an interrupted opening`() {
        val clock = TestTimeSource()
        val hard = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val source = Problem(2, 0, emptyArray(), arrayOf(hard))
        val providers = observingHard(hard) { state ->
            if (state.problem.numBoolVars > source.numBoolVars) clock += 3.seconds
        }
        val search = CoreGuidedSearch(
            source.bake(providers.prepare(source)),
            LinearObjective(longArrayOf(1, 1)),
            BacktrackParams(),
            maximumMillis = 2_000L,
            timeSource = clock,
        )

        val stopped = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { error("expired preparation") }
        val charged = search.work
        val resumed = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { error("renewed active time") }

        assertEquals(TerminationReason.BudgetExhausted, assertIs<MinimizeResult.Unknown>(stopped).reason)
        assertEquals(stopped, resumed)
        assertEquals(charged, search.work)
        search.close()
    }

    @Test
    fun `core work allowance should include preparation and not renew on resume`() {
        val problem = Problem(8192, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(LongArray(8192) { 1L })
        val search = CoreGuidedSearch(problem, objective, BacktrackParams(), maximumWork = 1L)

        val stopped = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { error("unprepared witness") }
        val charged = search.work
        val resumed = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { error("renewed allowance") }

        val unknown = assertIs<MinimizeResult.Unknown>(stopped)
        assertEquals(TerminationReason.BudgetExhausted, unknown.reason)
        assertEquals(stopped, resumed)
        assertEquals(charged, search.work)
        assertTrue(charged >= 1L)
        assertEquals(0.0, search.stats.search.nodes.sum)
        search.close()
    }

    @Test
    fun `exhausted preparation slice should pause before decisions and resume the same objective`() {
        val problem = Problem(8192, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(LongArray(8192) { 1L })
        val search = CoreGuidedSearch(problem, objective, BacktrackParams())

        val paused = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { error("unfinished preparation") }
        val charged = search.work
        val exhausted = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 0) { error("exhausted slice") }

        assertNull(paused)
        assertNull(exhausted)
        assertTrue(search.preparationPending)
        assertTrue(charged > 0L)
        assertEquals(charged, search.work)
        assertEquals(0.0, search.stats.search.nodes.sum)
        val optimal = assertIs<MinimizeResult.Optimal>(
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {},
        )
        assertEquals(0L, objective.evaluateLong(optimal.sample))
        search.close()
    }

    @Test
    fun `caller pins should remain hard for either objective polarity`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray()).bake()
        for (weight in listOf(-5L, 5L)) {
            for (pin in listOf(false, true)) {
                val objective = LinearObjective(longArrayOf(weight))
                val params = BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to pin)))

                val result = CoreGuidedSolver(problem).minimize(objective, params)

                val optimal = assertIs<MinimizeResult.Optimal>(result)
                assertEquals(pin, optimal.sample.boolValue(0))
                assertEquals(if (pin) weight else 0L, objective.evaluateLong(optimal.sample))
            }
        }
    }

    @Test
    fun `interrupted query opening should retain its baked model and charge preparation`() {
        val hard = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val source = Problem(2, 0, emptyArray(), arrayOf(hard))
        val augmentedModels = mutableListOf<Problem>()
        var cancelled = false
        var interrupted = false
        val providers = observingHard(hard) { state ->
            if (state.problem.numBoolVars > source.numBoolVars) {
                augmentedModels += state.problem
                if (!interrupted) {
                    interrupted = true
                    cancelled = true
                }
            }
        }
        val objective = LinearObjective(longArrayOf(1, 1))
        val search = CoreGuidedSearch(source.bake(providers.prepare(source)), objective, BacktrackParams())

        val paused = search.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1) { error("interrupted opening") }
        val charged = search.work
        cancelled = false
        val result = search.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1) {}

        assertNull(paused)
        assertTrue(interrupted)
        assertTrue(augmentedModels.size > 1)
        assertTrue(augmentedModels[0] === augmentedModels[1])
        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(1L, objective.evaluateLong(optimal.sample))
        assertTrue(search.work >= charged)
        search.close()
    }

    @Test
    fun `rebuilt core queries should retain captured propagation in engine views`() {
        val hard = Clause(intArrayOf(Lit.make(3, false), Lit.make(5, false)))
        val source = Problem(6, 0, emptyArray(), arrayOf(hard))
        val engineView = Problem(
            2,
            0,
            emptyArray(),
            arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ),
        )
        var augmentedChecks = 0
        val providers = observingHard(hard) { state ->
            if (state.problem.numBoolVars > engineView.numBoolVars) augmentedChecks++
        }
        val objective = LinearObjective(longArrayOf(-5, -3), constant = 8)

        val selection = providers.prepare(source).projected(engineView, intArrayOf(0))

        val result = CoreGuidedSolver(engineView.bake(selection)).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(3L, objective.evaluateLong(optimal.sample))
        assertTrue(augmentedChecks > 0)
    }

    @Test
    fun `propagated core premises should preserve the cheaper original violation`() {
        val problem = Problem(
            4,
            0,
            emptyArray(),
            arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(3, false))),
            ),
        ).bake()
        val objective = LinearObjective(longArrayOf(-1, -10, 0, -10), constant = 21)

        val result = CoreGuidedSolver(problem).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(1L, objective.evaluateLong(optimal.sample))
        assertFalse(optimal.sample.boolValue(0))
    }

    @Test
    fun `successive thresholds should account for three unavoidable violations`() {
        val literals = IntArray(5) { Lit.make(it, true) }
        val problem = Problem(5, 0, emptyArray(), arrayOf(Cardinality(literals, min = 3, max = 5))).bake()
        val objective = LinearObjective(LongArray(5) { 1 })

        val result = CoreGuidedSolver(problem).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(3L, objective.evaluateLong(optimal.sample))
    }

    @Test
    fun `partially split threshold costs should preserve later violations`() {
        val literals = IntArray(5) { Lit.make(it, true) }
        val factors: List<Factor> = listOf(Cardinality(literals, min = 3, max = 5)) + List(5) {
            Clause(intArrayOf(Lit.make(it, false), Lit.make((it + 1) % 5, true), Lit.make(5, true)))
        }
        val problem = Problem(6, 0, emptyArray(), factors).bake()
        val objective = LinearObjective(longArrayOf(3, 3, 3, 3, 3, 1))

        val result = CoreGuidedSolver(problem).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(10L, objective.evaluateLong(optimal.sample))
        assertTrue(optimal.sample.boolValue(5))
    }

    @Test
    fun `decision quota should be retained when the objective stratum changes`() {
        val reader = Cardinality(IntArray(4) { Lit.make(it, true) }, min = 0, max = 4)
        val problem = Problem(4, 0, emptyArray(), arrayOf<Factor>(reader)).bake()
        val objective = LinearObjective(longArrayOf(-5, -1, -1, 0), constant = 7)
        val search = CoreGuidedSearch(
            problem,
            objective,
            BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin, maxDecisions = 3),
            stratify = true,
        )

        val result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {}

        val retained = assertIs<MinimizeResult.BestFound>(result)
        assertEquals(2L, objective.evaluateLong(retained.sample))
        assertEquals(TerminationReason.BudgetExhausted, retained.reason)
    }

    @Test
    fun `weighted overlapping cores should preserve the original objective`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
            ),
        ).bake()
        val objective = LinearObjective(longArrayOf(-6, -1, -1), constant = 8)

        val result = CoreGuidedSolver(problem).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(2L, objective.evaluateLong(optimal.sample))
        assertTrue(optimal.sample.boolValue(0))
        assertEquals(3, optimal.sample.numBoolVars)
    }

    @Test
    fun `source assumptions should remain hard across weight splitting`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(longArrayOf(-5, -3), constant = 8)
        val params = BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to false)))

        val result = CoreGuidedSolver(problem).minimize(objective, params)

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertFalse(optimal.sample.boolValue(0))
        assertTrue(optimal.sample.boolValue(1))
        assertEquals(5L, objective.evaluateLong(optimal.sample))
    }

    @Test
    fun `weighted WCNF should preserve hard clauses and fixed cost`() {
        val source = Dimacs.parseWcnf("p wcnf 2 4 20\n20 -1 -2 0\n5 1 0\n3 2 0\n2 0\n").toProblem()
        val objective = source.objective.toLinearObjective()

        val result = CoreGuidedSolver(source.problem.bake()).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertTrue(optimal.sample.boolValue(0))
        assertFalse(optimal.sample.boolValue(1))
        assertEquals(5L, objective.evaluateLong(optimal.sample))
    }

    @Test
    fun `normalized OPB should preserve complemented objective constants`() {
        val source = Opb.parse("min: +5 ~x1 +3 ~x2 ;\n+1 x1 +1 x2 <= 1 ;\n").toProblem()
        val objective = requireNotNull(source.objective).toLinearObjective()

        val result = CoreGuidedSolver(source.problem.bake()).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertTrue(optimal.sample.boolValue(0))
        assertFalse(optimal.sample.boolValue(1))
        assertEquals(3L, objective.evaluateLong(optimal.sample))
    }

    @Test
    fun `wide objective equality should retain exact weights`() {
        val problem = Problem(
            2,
            0,
            emptyArray(),
            arrayOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true))),
            ),
        ).bake()
        val objective = LinearObjective(longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE))
        val search = CoreGuidedSearch(problem, objective, BacktrackParams())

        val result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {}

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals("18446744073709551614", objective.evaluateExact(optimal.sample).num.toString())
        assertEquals(objective.evaluateExact(optimal.sample), search.lowerBound)
    }

    @Test
    fun `cancelled slice should publish no incomplete proof and resume`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(longArrayOf(Long.MIN_VALUE), constant = 4)
        val search = CoreGuidedSearch(problem, objective, BacktrackParams())

        val paused = search.runSlice(Cancellation { true }, Long.MAX_VALUE, 1) { error("cancelled publication") }
        val result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {}

        assertNull(paused)
        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertTrue(optimal.sample.boolValue(0))
        assertEquals(objective.evaluateExact(optimal.sample), search.lowerBound)
    }

    @Test
    fun `cancellation after a core bound should retain the checked incumbent and residual weights`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
            ),
        ).bake()
        val objective = LinearObjective(longArrayOf(-6, -1, -1), constant = 8)
        var cancelled = false
        val incumbents = mutableListOf<Long>()
        val search = CoreGuidedSearch(
            problem,
            objective,
            BacktrackParams(objectiveLowerBoundSink = { if (it == 1.0) cancelled = true }),
            stratify = true,
        )

        val paused = search.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1) {
            incumbents += objective.evaluateLong(it.sample)
        }
        val retainedBound = search.lowerBound
        cancelled = false
        val result = search.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1) {
            incumbents += objective.evaluateLong(it.sample)
        }

        assertNull(paused)
        assertEquals(BigFraction.ONE, retainedBound)
        assertEquals(listOf(2L), incumbents)
        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(2L, objective.evaluateLong(optimal.sample))
        assertEquals(objective.evaluateExact(optimal.sample), search.lowerBound)
    }

    @Test
    fun `hard infeasibility should not be relaxed as an objective core`() {
        val problem = Problem(
            1,
            0,
            emptyArray(),
            arrayOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        ).bake()

        val result = CoreGuidedSolver(problem).minimize(LinearObjective(longArrayOf(5)), BacktrackParams())

        assertIs<MinimizeResult.Infeasible>(result)
    }

    @Test
    fun `integer costs should charge overlapping pair cores once to the attained floor`() {
        val floors = mutableListOf<Double>()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val search = CoreGuidedSearch(
            pairCover().bake(),
            objective,
            BacktrackParams(objectiveLowerBoundSink = { floors += it }),
        )

        val result = assertIs<MinimizeResult.Optimal>(search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})

        assertEquals(listOf(3.0, 6.0), floors)
        assertEquals(6L, objective.evaluateLong(result.sample))
        assertEquals(objective.evaluateExact(result.sample), search.lowerBound)
        assertEquals(2L, result.stats.coreGuided.cores)
        assertEquals(2, result.stats.coreGuided.largestCore)
    }

    @Test
    fun `an eager threshold view of the pair cover should reach the same floor with a wider core`() {
        val channels = longArrayOf(3, 3, 3, 8, 10, 11).mapIndexed { selector, value ->
            ReifiedLinear(selector, longArrayOf(1), intArrayOf(selector % 3), LinearOp.GE, value)
        }
        val problem = pairCover(6, channels).bake()
        val floors = mutableListOf<Double>()
        val objective = LinearObjective(longArrayOf(3, 3, 3, 5, 7, 8))
        val search = CoreGuidedSearch(problem, objective, BacktrackParams(objectiveLowerBoundSink = { floors += it }))

        val result = assertIs<MinimizeResult.Optimal>(search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})

        assertEquals(listOf(3.0, 6.0), floors)
        assertEquals(6L, (0 until 3).sumOf { result.sample.intValue(it) })
        assertEquals(5, result.stats.coreGuided.largestCore)
    }

    @Test
    fun `negative integer costs should charge signed value gaps from the root maximum`() {
        val problem = Problem(
            0,
            2,
            arrayOf(intDomainFromSurvivors(longArrayOf(0, 3, 8)), IntDomain(0, 1)),
            listOf(Linear(longArrayOf(1, 6), intArrayOf(0, 1), LinearOp.LE, 9)),
        ).bake()
        val floors = mutableListOf<Double>()
        val objective = LinearObjective(intCoefficients = longArrayOf(-2, -7), constant = 4)
        val search = CoreGuidedSearch(problem, objective, BacktrackParams(objectiveLowerBoundSink = { floors += it }))

        val result = assertIs<MinimizeResult.Optimal>(search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})

        assertEquals(listOf(-12.0), floors)
        assertEquals(8L, result.sample.intValue(0))
        assertEquals(-12L, objective.evaluateLong(result.sample))
        assertEquals(objective.evaluateExact(result.sample), search.lowerBound)
    }

    @Test
    fun `caller integer pins should scope the integer cost optimum`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val params = BacktrackParams(assumptions = Assumptions(ints = mapOf(0 to 8L)))
        val search = CoreGuidedSearch(pairCover().bake(), objective, params)

        val result = assertIs<MinimizeResult.Optimal>(search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})

        assertEquals(8L, result.sample.intValue(0))
        assertEquals(11L, objective.evaluateLong(result.sample))
        assertEquals(objective.evaluateExact(result.sample), search.lowerBound)
    }

    @Test
    fun `infeasibility found by search should not end as an integer cost bound`() {
        val problem = Problem(
            0,
            3,
            Array(3) { intDomainFromSurvivors(longArrayOf(0, 3)) },
            listOf(
                Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.NE, 0),
                Linear(longArrayOf(1, -1), intArrayOf(0, 2), LinearOp.NE, 0),
                Linear(longArrayOf(1, -1), intArrayOf(1, 2), LinearOp.NE, 0),
            ),
        ).bake()

        val result = CoreGuidedSolver(problem).minimize(
            LinearObjective(intCoefficients = longArrayOf(1, 1, 1)),
            BacktrackParams(),
        )

        assertIs<MinimizeResult.Infeasible>(result)
    }

    @Test
    fun `integer costs should be admitted only on exact models and stay out of curated eligibility`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val approximate = pairCover().withSourceSemantics(
            SourceSemantics(
                SourceFormat.IR,
                feasibilityTolerance = SourceTolerance(absolute = SemanticRational.ofLong(1)),
            ).resolve(),
        )

        assertTrue(CoreGuidedSearch.admits(pairCover().bake(), objective))
        assertFalse(CoreGuidedSearch.eligible(pairCover().bake(), objective))
        assertFalse(CoreGuidedSearch.admits(approximate.bake(), objective))
    }

    @Test
    fun `scalar proofs should count integer cost magnitude`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(-3, 2)), emptyList()).bake()

        assertTrue(CoreGuidedSearch.scalarProofs(problem, LinearObjective(intCoefficients = longArrayOf(1L shl 51))))
        assertFalse(CoreGuidedSearch.scalarProofs(problem, LinearObjective(intCoefficients = longArrayOf(1L shl 52))))
    }

    @Test
    fun `closed pending search should reject another slice`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray()).bake()
        val search = CoreGuidedSearch(problem, LinearObjective(longArrayOf(1)), BacktrackParams())

        search.close()

        assertFailsWith<IllegalStateException> { search.runSlice(Cancellation.Never, 1, 1) {} }
    }

    private fun pairCover(numBoolVars: Int = 0, channels: List<Factor> = emptyList()) = Problem(
        numBoolVars,
        3,
        arrayOf(
            intDomainFromSurvivors(longArrayOf(0, 3, 8)),
            intDomainFromSurvivors(longArrayOf(0, 3, 10)),
            intDomainFromSurvivors(longArrayOf(0, 3, 11)),
        ),
        listOf(
            Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            Linear(longArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 3),
            Linear(longArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 3),
        ) + channels,
    )

    private class ClockJumpOnDecision(private val clock: TestTimeSource) : SearchComponent {
        var interrupted = false

        override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
            if (!interrupted && context.decisionLevel > 0 && decision is SearchDecision.Bool &&
                Lit.variable(decision.literal) != 0
            ) {
                interrupted = true
                clock += 2.seconds
            }
            return ComponentResult.Consistent
        }
    }

    private fun observingHard(hard: Factor, observe: (PropagationState) -> Unit) = FactorProjectionProviders(
        propagation = PropagationProjectionProvider { factor ->
            if (factor != hard) {
                BuiltInPropagationProjectionProvider.select(factor)
            } else {
                PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) { view ->
                    val delegate = BuiltInPropagationProjectionProvider.create(view, null)
                    object : Propagator by delegate {
                        override fun propagate(state: PropagationState, factorId: Int): Boolean {
                            observe(state)
                            return delegate.propagate(state, factorId)
                        }
                    }
                }
            }
        },
    )
}
