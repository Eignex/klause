package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioEvent
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.ScriptedSearch
import com.eignex.klause.portfolio.exactlyOneOver
import com.eignex.klause.portfolio.fixedBandit
import com.eignex.klause.portfolio.initialProbeFraction
import com.eignex.klause.portfolio.trackingWorker
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class FinitePortfolioExecutorTest {
    @Test
    fun `LP transfers should preserve non-LP sibling probe shares`() {
        val labels = listOf("bt/satOptimized", "bt/lp-default", "ls/fixed", "ls/plateau",
            "ls/smooth", "ls/sa", "alns/balanced", "bt/lp-aggressive")
        val workers = labels.mapIndexed { arm, label ->
            trackingWorker(label, arm, ScriptedSearch({ false }) {}).also {
                if (arm in 2..6) it.family = ArmFamily.LocalSearch
            }
        }
        val initial = workers.indices.toList()

        val weights = lpTransferredProbeWeights(workers)

        assertEquals(0.25, initialProbeFraction(0, initial, weights), 1e-12)
        for (arm in 2..6) assertEquals(0.125, initialProbeFraction(arm, initial, weights), 1e-12)
        for (arm in listOf(1, 7)) assertEquals(0.0625, initialProbeFraction(arm, initial, weights), 1e-12)
        assertEquals(1.0, initial.sumOf { initialProbeFraction(it, initial, weights) }, 1e-12)
    }

    @Test
    fun `LP transfers should fund a complete source worker instead of a witness worker`() {
        val model = ModelIdentity.of(Any())
        val workers = listOf("witness", "proof", "bt/lp-default", "ls/fixed").mapIndexed { arm, label ->
            trackingWorker(label, arm, ScriptedSearch({ false }) {}).also {
                if (arm == 0) it.bindEvidence(model, provesResults = false)
                if (arm == 3) it.family = ArmFamily.LocalSearch
            }
        }
        val initial = workers.indices.toList()

        val weights = lpTransferredProbeWeights(workers)

        assertEquals(0.25, initialProbeFraction(0, initial, weights), 1e-12)
        assertEquals(0.375, initialProbeFraction(1, initial, weights), 1e-12)
    }

    @Test
    fun `deferred LP probes should leave ordinary initial shares without LP donors`() {
        val workers = listOf("proof", "bt/lp-default", "ls/fixed", "bt/lp-aggressive")
            .mapIndexed { arm, label ->
                trackingWorker(label, arm, ScriptedSearch({ false }) {}).also {
                    if (arm == 2) it.family = ArmFamily.LocalSearch
                }
            }
        deferredLpProbes(workers)

        val weights = lpTransferredProbeWeights(workers)

        assertEquals(0.5, initialProbeFraction(0, listOf(0, 2), weights), 1e-12)
        assertEquals(0.5, initialProbeFraction(2, listOf(0, 2), weights), 1e-12)
    }

    @Test
    fun `the leading complete worker should receive half the initially eligible probe budget`() {
        val model = ModelIdentity.of(Any())
        val workers = List(6) { arm ->
            trackingWorker("worker$arm", arm, ScriptedSearch({ false }) {}).also {
                if (arm == 0) it.startAfterIncumbent = true
                if (arm == 1) it.auxiliaryProof = true
                if (arm == 2) it.bindEvidence(model, provesResults = false)
                if (arm >= 4) it.family = ArmFamily.LocalSearch
            }
        }
        val initial = listOf(2, 3, 4, 5)

        val weights = leadingProbeWeights(workers)

        assertEquals(0.5, initialProbeFraction(3, initial, weights), 1e-12)
        assertEquals(0.5, initial.filter { it != 3 }.sumOf { initialProbeFraction(it, initial, weights) }, 1e-12)
    }

    @Test
    fun `weighted probes should still attempt every initially eligible sibling`() {
        for (transfer in listOf(false, true)) {
            val visits = mutableListOf<Int>()
            val workers = listOf("proof", "bt/lp-default", "ls/fixed").mapIndexed { arm, label ->
                trackingWorker(label, arm, ScriptedSearch({ false }) { visits += arm })
                    .also { if (arm == 2) it.family = ArmFamily.LocalSearch }
            }

            Portfolio(workers, fixedBandit(3, 0)).also {
                it.initialProbeWeights = if (transfer) lpTransferredProbeWeights(workers)
                else leadingProbeWeights(workers)
            }.use { it.minimize(Cancellation { visits.size >= 3 }) }

            assertEquals(listOf(0, 1, 2), visits)
        }
    }

    @Test
    fun `deferred LP admission should leave half the initial budget to the leading complete worker`() {
        val workers = List(4) { arm ->
            trackingWorker(if (arm == 1) "bt/lp-default" else "worker$arm", arm, ScriptedSearch({ false }) {})
                .also { if (arm >= 2) it.family = ArmFamily.LocalSearch }
        }
        deferredLpProbes(workers)

        val weights = leadingProbeWeights(workers)

        assertEquals(0.5, initialProbeFraction(0, listOf(0, 2, 3), weights), 1e-12)
    }

    @Test
    fun `LP probes should follow the other initial attempts even when one fails`() {
        for (failed in listOf(false, true)) {
            val visits = mutableListOf<Int>()
            val workers = List(3) { arm ->
                trackingWorker(if (arm == 0) "bt/lp-default" else "worker$arm", arm,
                    ScriptedSearch({ false }) {
                        visits += arm
                        if (failed && arm == 1) error("initial attempt failed")
                    }).also { if (arm == 2) it.family = ArmFamily.LocalSearch }
            }
            val observer = deferredLpProbes(workers)

            Portfolio(workers, fixedBandit(3, 1), onProgress = observer).use {
                it.minimize(Cancellation { visits.size >= 3 })
            }

            assertEquals(listOf(1, 2, 0), visits)
        }
    }

    @Test
    fun `an accepted incumbent should admit deferred LP before the remaining initial probes`() {
        val visits = mutableListOf<Int>()
        val workers = List(3) { arm ->
            trackingWorker(if (arm == 0) "bt/lp-default" else "worker$arm", arm,
                ScriptedSearch({ arm == 1 && it == 0 }) { visits += arm })
                .also { if (arm == 2) it.family = ArmFamily.LocalSearch }
        }
        val observer = deferredLpProbes(workers)

        Portfolio(workers, fixedBandit(3, 1), onProgress = observer).use {
            it.minimize(Cancellation { visits.size >= 3 })
        }

        assertEquals(listOf(1, 0, 2), visits)
    }

    @Test
    fun `admitted silent LP traversal should retain proof coverage`() {
        val clock = TestTimeSource()
        val elapsed = LongArray(3)
        val workers = List(3) { arm ->
            trackingWorker(if (arm == 0) "bt/lp-default" else "worker$arm", arm,
                ScriptedSearch({ arm == 1 && it == 0 }) {
                    elapsed[arm] += 100L
                    clock += 100.milliseconds
                }).also {
                    if (arm == 1) it.restartingSearch = true
                    if (arm == 2) it.family = ArmFamily.LocalSearch
                }
        }
        val observer = deferredLpProbes(workers)

        Portfolio(workers, fixedBandit(3, 1), reseedStaleThreshold = 0, onProgress = observer).also {
            it.runTimeSource = clock
        }.use { it.minimize(Cancellation { elapsed.sum() >= 8_000L }) }

        assertTrue(elapsed[0] >= (elapsed.sum() - 100L) / 2 - 100L)
    }

    @Test
    fun `diagnostic finite invocations should reproduce the source schedule and rewards`() {
        val traces = List(2) {
            val source = exactlyOneOver(8)
            val objective = LinearObjective(LongArray(8) { (8 - it).toLong() })
            val prepared = source.prepareExecutionPlan(objective, preferFinite = true)
            val scenario = PortfolioScenario(cores = 1, arms = 6, kind = Kind.COP, engine = EngineMix.MIXED,
                seed = 17, sliceWork = 1, maxSliceWork = 4, diagnosticWorkLimit = 256)
            val trace = mutableListOf<PortfolioEvent>()
            val executor = FinitePipeline.portfolioExecutor(source.bake(), scenario, objective, null, null,
                { _, event -> if (event is SearchEvent.PortfolioDiagnostic) trace += event.event },
                null, prepared, Cancellation.Never)

            executor.use { it.minimize(Cancellation.Never, null) }
            trace
        }

        assertEquals(traces[0], traces[1])
        assertTrue(traces[0].filterIsInstance<PortfolioEvent.Selected>().map { it.arm }.distinct().size > 1)
        assertTrue(traces[0].any { it is PortfolioEvent.Rewarded })
        assertTrue(traces[0].any { it is PortfolioEvent.Imported })
        assertIs<PortfolioEvent.Finished>(traces[0].last())
    }

    @Test
    fun `bounded Boolean proof service should preserve one feasibility share for complete siblings`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(longArrayOf(1, 2))
        for ((cores, arms) in listOf(
            1 to PortfolioScenario.DEFAULT_ARMS,
            1 to 12,
            2 to PortfolioScenario.DEFAULT_ARMS,
        )) {
            val workers = PortfolioBuilder.build(
                problem,
                PortfolioScenario.sequential(Kind.COP).copy(cores = cores, arms = arms),
                objective,
            )

            try {
                val shares = booleanIncumbentShares(workers)

                if (cores == 1) {
                    val families = workers.map { it.family }.distinct().size
                    assertEquals(1.0 / families, shares.sum())
                    for ((index, worker) in workers.withIndex()) {
                        assertEquals(
                            worker.family == ArmFamily.Backtrack && !worker.auxiliaryProof && !worker.improvementOnly,
                            shares[index] > 0.0,
                        )
                    }
                    if (arms == 12) {
                        assertTrue(
                            workers.any {
                                it.family == ArmFamily.Backtrack && !it.auxiliaryProof && it.improvementOnly
                            },
                        )
                    }
                } else {
                    assertTrue(shares.isEmpty())
                }
            } finally {
                workers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `conditional root floors should not prove the unconditioned source optimum`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val prepared = source.prepareExecutionPlan(objective, preferFinite = true)
        val scenario = PortfolioScenario(
            cores = 1,
            arms = 1,
            kind = Kind.COP,
            engine = EngineMix.BACKTRACK,
            btEdit = { it.copy(assumptions = Assumptions.None.withInt(0, 1L)) },
        )
        val invocation = FinitePipeline.portfolioExecutor(
            source.bake(), scenario, objective, null, null, null,
            null, prepared, Cancellation.Never,
        )

        val result = invocation.use { it.minimize(Cancellation.Never, null) }

        assertFalse(result is MinimizeResult.Optimal)
    }

    @Test
    fun `finite source factories should keep their selected plan through shared construction`() {
        val source = Problem(0, 1, arrayOf(IntDomain(1, 2)), emptyArray())
        val prepared = source.prepareExecutionPlan(preferFinite = true)
        val problem = source.bake()
        val scenario = PortfolioScenario(cores = 1, arms = 1, kind = Kind.CSP, engine = EngineMix.BACKTRACK, seed = 17)
        val invocation = assertIs<PortfolioInvocation>(
            FinitePipeline.portfolioExecutor(
                problem, scenario, null, null, null, null, null, prepared, Cancellation.Never,
            ),
        )

        val result = invocation.use { it.solve(Cancellation.Never) }

        val witness = assertIs<SolveResult.Sat>(result).assignment
        assertSame(prepared, invocation.preparedPlan)
        assertTrue(witness.intValue(0) in 1L..2L)
    }
}
