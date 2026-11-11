package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
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

class PreparingMinimizeTest {
    @Test
    fun `pending root layout stays visible until the prepared worker can query`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        var constructions = 0
        var queries = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                constructions++
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by delegate {
                    private var preparationWork = 0L
                    override val lastMetrics get() = delegate.lastMetrics.copy(
                        workOps = delegate.lastMetrics.workOps + preparationWork,
                    )
                    override fun prepareLogicals(token: Cancellation): Basis? =
                        delegate.prepareLogicals(token).also { preparationWork = 5_000L * LP_WORK_PER_NODE }

                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                        preparationWork = 0L
                        queries++
                        return delegate.resolveBounds(allowance)
                    }
                }
            }
        }
        val params = BacktrackParams(maxDecisions = 0L,
            lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true, rootCutHarvest = false))
        val samples = mutableListOf<MinimizeResult.WithSample>()
        val stopAtWitness = Cancellation { samples.isNotEmpty() }
        PreparingMinimize(BacktrackSolver(problem, LpSolveContext(factory)), objective, params).use { search ->
            search.runSlice(stopAtWitness, Long.MAX_VALUE, 5_000L, samples::add)

            assertTrue(search.preparationPending)
            assertFalse(search.canReseed)
            assertEquals(1, constructions)
            assertEquals(0, queries)
            assertTrue(samples.isEmpty())
            search.runSlice(stopAtWitness, Long.MAX_VALUE, 100_000L, samples::add)

            assertFalse(search.preparationPending)
            assertEquals(listOf(1L, 0L), samples.single().sample.ints.toList())
            assertEquals(1, constructions)
            assertEquals(1, queries)
        }
    }

    @Test
    fun `a preparation time boundary should publish no incumbent before resuming`() {
        val fixture = FiniteCutoffKnapsackFixture
        val samples = mutableListOf<MinimizeResult.WithSample>()
        PreparingMinimize(BacktrackSolver(fixture.problem), fixture.objective, fixture.params).use { search ->
            val paused = search.runSlice(Cancellation.Never, 0L, 100L, samples::add)

            assertNull(paused)
            assertTrue(search.preparationPending)
            assertFalse(search.canReseed)
            assertTrue(samples.isEmpty())
            val result = assertIs<MinimizeResult.Optimal>(
                search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L, samples::add),
            )
            assertEquals(-4.0, result.objective)
            assertFalse(search.preparationPending)
            assertTrue(search.canReseed)
        }
    }

    @Test
    fun `preparation slices should preserve the optimum and propagation work`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            Array(2048) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1) },
        ).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L, lpPlan = LpPlan())
        val solver = BacktrackSolver(problem)
        val whole = solver.resumable(objective, params).use {
            assertIs<MinimizeResult.Optimal>(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {})
        }
        solver.resumable(objective, params).use { search ->
            var terminal: MinimizeResult? = null
            var preparationPauses = 0
            var slices = 0
            while (terminal == null && slices++ < 100) {
                val before = search.work
                terminal = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L) {}
                assertTrue(search.work >= before)
                if (search.preparationPending && search.work > 0L) preparationPauses++
            }

            assertTrue(preparationPauses > 0)
            assertEquals(whole.objective, assertIs<MinimizeResult.Optimal>(terminal).objective)
            assertEquals(whole.stats.search.propagationWork, search.stats.search.propagationWork)
        }
    }

    @Test
    fun `an installation failure should close the retained handle`() {
        val fixture = FiniteCutoffKnapsackFixture
        val search = PreparingMinimize(
            BacktrackSolver(fixture.problem),
            fixture.objective,
            fixture.params.copy(assumptions = Assumptions.None.withInt(Int.MAX_VALUE, 1)),
        )

        assertFailsWith<IndexOutOfBoundsException> {
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { }
        }

        assertFalse(search.preparationPending)
        assertFailsWith<IllegalStateException> {
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { }
        }
        search.close()
    }

    @Test
    fun `a construction refutation should preserve shared cutoff result semantics`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, -1)),
        ).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(objectiveBoundSupplier = { 0.0 })
        PreparingMinimize(BacktrackSolver(problem), objective, params).use { search ->
            val result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { }

            assertEquals(TerminationReason.SearchExhausted, assertIs<MinimizeResult.Unknown>(result).reason)
            assertTrue(search.isDone)
        }
    }

    @Test
    fun `cancellation at installation handoff should leave search for a later slice`() {
        val fixture = FiniteCutoffKnapsackFixture
        PreparingMinimize(BacktrackSolver(fixture.problem), fixture.objective, fixture.params).use { search ->
            val samples = mutableListOf<MinimizeResult.WithSample>()
            val token = Cancellation { !search.preparationPending }

            val paused = search.runSlice(token, Long.MAX_VALUE, -1L, samples::add)

            assertNull(paused)
            assertFalse(search.preparationPending)
            assertEquals(0.0, search.stats.search.nodes.sum)
            assertEquals(0.0, search.stats.lp.solves.sum)
            assertTrue(samples.isEmpty())
            val result = assertIs<MinimizeResult.Optimal>(
                search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L, samples::add),
            )
            assertEquals(-4.0, result.objective)
        }
    }
}
