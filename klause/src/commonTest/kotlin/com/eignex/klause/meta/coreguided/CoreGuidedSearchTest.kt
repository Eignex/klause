package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.formats.opb.toProblem
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
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
import com.eignex.klause.util.Cancellation
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
    fun `initial residual query retains the captured source projection and root deductions`() {
        val hard = Clause(intArrayOf(Lit.make(0, true)))
        val source = Problem(1, 0, emptyArray(), arrayOf(hard))
        val observedModels = mutableListOf<Problem>()
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) { view ->
                val delegate = BuiltInPropagationProjectionProvider.create(view, null)
                object : Propagator by delegate {
                    override fun propagate(state: PropagationState, factorId: Int): Boolean {
                        observedModels += state.problem
                        return delegate.propagate(state, factorId)
                    }
                }
            }
        })
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
    fun `private assumption traversal resumes across decision work pauses`() {
        val problem = Problem(5, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true))),
            Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(3, true), Lit.make(4, true))),
        )).bake()
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
    fun `active preparation time cannot renew after an interrupted opening`() {
        val clock = TestTimeSource()
        val hard = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val source = Problem(2, 0, emptyArray(), arrayOf(hard))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { factor ->
            if (factor != hard) BuiltInPropagationProjectionProvider.select(factor)
            else PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) { view ->
                val delegate = BuiltInPropagationProjectionProvider.create(view, null)
                object : Propagator by delegate {
                    override fun propagate(state: PropagationState, factorId: Int): Boolean {
                        if (state.problem.numBoolVars > source.numBoolVars) clock += 3.seconds
                        return delegate.propagate(state, factorId)
                    }
                }
            }
        })
        val search = CoreGuidedSearch(source.bake(providers.prepare(source)), LinearObjective(longArrayOf(1, 1)),
            BacktrackParams(), maximumMillis = 2_000L, timeSource = clock)

        val stopped = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { error("expired preparation") }
        val charged = search.work
        val resumed = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { error("renewed active time") }

        assertEquals(TerminationReason.BudgetExhausted, assertIs<MinimizeResult.Unknown>(stopped).reason)
        assertEquals(stopped, resumed)
        assertEquals(charged, search.work)
        search.close()
    }

    @Test
    fun `core work allowance includes preparation and cannot renew on resume`() {
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
    fun `exhausted preparation slice pauses before decisions and resumes the same objective`() {
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
    fun `caller pins remain hard for either objective polarity`() {
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
    fun `interrupted query opening retains its baked model and charges preparation`() {
        val hard = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val source = Problem(2, 0, emptyArray(), arrayOf(hard))
        val augmentedModels = mutableListOf<Problem>()
        var cancelled = false
        var interrupted = false
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { factor ->
            if (factor != hard) BuiltInPropagationProjectionProvider.select(factor)
            else PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) { view ->
                val delegate = BuiltInPropagationProjectionProvider.create(view, null)
                object : Propagator by delegate {
                    override fun propagate(state: PropagationState, factorId: Int): Boolean {
                        if (state.problem.numBoolVars > source.numBoolVars) {
                            augmentedModels += state.problem
                            if (!interrupted) {
                                interrupted = true
                                cancelled = true
                            }
                        }
                        return delegate.propagate(state, factorId)
                    }
                }
            }
        })
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
    fun `rebuilt core queries retain captured propagation in engine views`() {
        val hard = Clause(intArrayOf(Lit.make(3, false), Lit.make(5, false)))
        val source = Problem(6, 0, emptyArray(), arrayOf(hard))
        val engineView = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
        ))
        var augmentedChecks = 0
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { factor ->
            if (factor != hard) BuiltInPropagationProjectionProvider.select(factor)
            else PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) { view ->
                val delegate = BuiltInPropagationProjectionProvider.create(view, null)
                object : Propagator by delegate {
                    override fun propagate(state: PropagationState, factorId: Int): Boolean {
                        if (state.problem.numBoolVars > engineView.numBoolVars) augmentedChecks++
                        return delegate.propagate(state, factorId)
                    }
                }
            }
        })
        val objective = LinearObjective(longArrayOf(-5, -3), constant = 8)

        val selection = providers.prepare(source).projected(engineView, intArrayOf(0))

        val result = CoreGuidedSolver(engineView.bake(selection)).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(3L, objective.evaluateLong(optimal.sample))
        assertTrue(augmentedChecks > 0)
    }

    @Test
    fun `propagated core premises preserve the cheaper original violation`() {
        val problem = Problem(
            4, 0, emptyArray(),
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
    fun `successive thresholds account for three unavoidable violations`() {
        val literals = IntArray(5) { Lit.make(it, true) }
        val problem = Problem(5, 0, emptyArray(), arrayOf(Cardinality(literals, min = 3, max = 5))).bake()
        val objective = LinearObjective(LongArray(5) { 1 })

        val result = CoreGuidedSolver(problem).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(3L, objective.evaluateLong(optimal.sample))
    }

    @Test
    fun `partially split threshold costs preserve later violations`() {
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
    fun `decision quota is retained when the objective stratum changes`() {
        val problem = Problem(4, 0, emptyArray(), emptyArray()).bake()
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
    fun `weighted overlapping cores preserve the original objective`() {
        val problem = Problem(
            3, 0, emptyArray(),
            arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
        )
        ).bake()
        val objective = LinearObjective(longArrayOf(-6, -1, -1), constant = 8)

        val result = CoreGuidedSolver(problem).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(2L, objective.evaluateLong(optimal.sample))
        assertTrue(optimal.sample.boolValue(0))
        assertEquals(3, optimal.sample.numBoolVars)
    }

    @Test
    fun `threshold cores charge multiple unavoidable violations`() {
        val problem = Problem(
            3, 0, emptyArray(),
            arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
        )
        ).bake()
        val objective = LinearObjective(longArrayOf(-1, -1, -1), constant = 3)

        val result = CoreGuidedSolver(problem).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(2L, objective.evaluateLong(optimal.sample))
    }

    @Test
    fun `source assumptions remain hard across weight splitting`() {
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
    fun `weighted WCNF preserves hard clauses and fixed cost`() {
        val source = Dimacs.parseWcnf("p wcnf 2 4 20\n20 -1 -2 0\n5 1 0\n3 2 0\n2 0\n").toProblem()
        val objective = source.objective.toLinearObjective()

        val result = CoreGuidedSolver(source.problem.bake()).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertTrue(optimal.sample.boolValue(0))
        assertFalse(optimal.sample.boolValue(1))
        assertEquals(5L, objective.evaluateLong(optimal.sample))
    }

    @Test
    fun `normalized OPB preserves complemented objective constants`() {
        val source = Opb.parse("min: +5 ~x1 +3 ~x2 ;\n+1 x1 +1 x2 <= 1 ;\n").toProblem()
        val objective = requireNotNull(source.objective).toLinearObjective()

        val result = CoreGuidedSolver(source.problem.bake()).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertTrue(optimal.sample.boolValue(0))
        assertFalse(optimal.sample.boolValue(1))
        assertEquals(3L, objective.evaluateLong(optimal.sample))
    }

    @Test
    fun `wide objective equality retains exact weights`() {
        val problem = Problem(
            2, 0, emptyArray(),
            arrayOf(
            Clause(intArrayOf(Lit.make(0, true))),
            Clause(intArrayOf(Lit.make(1, true))),
        )
        ).bake()
        val objective = LinearObjective(longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE))
        val search = CoreGuidedSearch(problem, objective, BacktrackParams())

        val result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {}

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals("18446744073709551614", objective.evaluateExact(optimal.sample).num.toString())
        assertEquals(objective.evaluateExact(optimal.sample), search.lowerBound)
    }

    @Test
    fun `cancelled slice publishes no incomplete proof and can resume`() {
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
    fun `cancellation after a core bound retains the checked incumbent and residual weights`() {
        val problem = Problem(
            3, 0, emptyArray(),
            arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
        )
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
    fun `hard infeasibility cannot be relaxed as an objective core`() {
        val problem = Problem(
            1, 0, emptyArray(),
            arrayOf(
            Clause(intArrayOf(Lit.make(0, true))),
            Clause(intArrayOf(Lit.make(0, false))),
        )
        ).bake()

        val result = CoreGuidedSolver(problem).minimize(LinearObjective(longArrayOf(5)), BacktrackParams())

        assertIs<MinimizeResult.Infeasible>(result)
    }

    @Test
    fun `closed pending search rejects another slice`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray()).bake()
        val search = CoreGuidedSearch(problem, LinearObjective(longArrayOf(1)), BacktrackParams())

        search.close()

        assertFailsWith<IllegalStateException> { search.runSlice(Cancellation.Never, 1, 1) {} }
    }
}
