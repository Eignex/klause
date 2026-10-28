package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.meta.coreguided.CoreRefinementMode
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.CompletedEngineResult
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CoreGuidedWorkerConfigTest {
    @Test
    fun `selected propagation deletion should attain the source optimum through the core worker`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
        )).bake()
        val objective = LinearObjective(longArrayOf(1, 100, 100))
        val worker = CoreGuidedWorkerConfig(coreRefinement = CoreRefinementMode.DELETION) {
            it.copy(nativeSat = false, randomSeed = 3L)
        }.materialize(problem, 0, 0, 3L, 1.0, objective, null, null, null, null)

        worker.use {
            requireNotNull(worker.newResumableSearch({ Double.POSITIVE_INFINITY }, Cancellation.Never)).use { handle ->
                val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {}

                assertEquals(100L, objective.evaluateLong(assertIs<MinimizeResult.Optimal>(result).sample))
                assertEquals(1L, handle.stats.coreGuided.cores)
                assertEquals(2, handle.stats.coreGuided.largestCore)
            }
        }
    }

    @Test
    fun `explicit core workers should consume selected seed reason refinement`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
        )).bake()
        val objective = LinearObjective(longArrayOf(1, 100, 100))
        var cancelled = false
        val floors = mutableListOf<Double>()
        val recipe = BacktrackRecipe("core-guided") { _, _ ->
            BacktrackParams(nativeSat = false, objectiveLowerBoundSink = {
                floors += it
                cancelled = true
            })
        }
        val worker = BacktrackWorkerConfig(recipe).withCoreRefinement(CoreRefinementMode.REASONS).materialize(
            problem, 0, 0, 3L, 1.0, objective, null, null, null, null,
        )

        worker.use {
            requireNotNull(worker.newResumableSearch({ Double.POSITIVE_INFINITY }, Cancellation.Never)).use { handle ->
                val paused = handle.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1L) {
                    error("unfinished refinement")
                }

                assertNull(paused)
                assertEquals(listOf(100.0), floors)
                assertEquals("100", handle.stats.coreGuided.lowerBound.toString())
                assertEquals(2, handle.stats.coreGuided.largestCore)
            }
        }
    }

    @Test
    fun `catalog worker should retain a completed core bound across cancellation`() {
        val source = Problem(
            3,
            0,
            emptyArray(),
            arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
            ),
        )
        val objective = LinearObjective(longArrayOf(-1, -1, -1), constant = 3)
        var cancelled = false
        val floors = mutableListOf<Double>()
        val recipe = BacktrackRecipe("core-guided") { _, _ ->
            BacktrackParams(objectiveLowerBoundSink = {
                floors += it
                if (it == 1.0) cancelled = true
            })
        }
        val worker = BacktrackWorkerConfig(recipe).materialize(
            source.bake(), 0, 0, 3L, 1.0, objective, null, null, null, null,
        )
        val handle = requireNotNull(worker.newResumableSearch({ Double.POSITIVE_INFINITY }, Cancellation.Never))

        val paused = handle.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1) { error("unfinished core") }
        assertNull(paused)
        assertEquals(listOf(1.0), floors)
        assertEquals("1", handle.stats.coreGuided.lowerBound.toString())
        cancelled = false
        val result = assertIs<MinimizeResult.Optimal>(
            handle.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1) {},
        )

        assertEquals(listOf(1.0, 2.0), floors)
        assertEquals(2L, objective.evaluateLong(result.sample))
        assertEquals("2", result.stats.coreGuided.lowerBound.toString())
        handle.close()
        worker.close()
    }

    @Test
    fun `rounded wide completion should not certify a scalar source bound`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val problem = source.bake()
        val objective = LinearObjective(longArrayOf(9_007_199_254_740_995L))
        val model = ModelIdentity.of(problem, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val worker = BacktrackWorkerConfig.byLabel("core-guided").materialize(
            problem, 0, 0, 3L, 1.0, objective, null, null, null, null,
        ).let { it.bindSource(SourceWorkerBinding(invocation, provesResults = it.permitsSourceProofs)) }
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindInvocation(invocation)
        val handle = requireNotNull(worker.newResumableSearch({ Double.POSITIVE_INFINITY }, Cancellation.Never))
        val result = assertIs<MinimizeResult.Optimal>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})

        val checked = verifier.proof(worker, EvidenceKind.Bound, CompletedEngineResult.Minimize(invocation, result))

        assertEquals("9007199254740995", objective.evaluateExact(result.sample).num.toString())
        assertIs<Verification.Indeterminate>(checked)
        handle.close()
        worker.close()
    }

    @Test
    fun `exact scalar completion should certify its source bound`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val problem = source.bake()
        val objective = LinearObjective(longArrayOf(5))
        val model = ModelIdentity.of(problem, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val worker = CoreGuidedWorkerConfig().materialize(
            problem, 0, 0, 3L, 1.0, objective, null, null, null, null,
        ).let { it.bindSource(SourceWorkerBinding(invocation, provesResults = it.permitsSourceProofs)) }
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindInvocation(invocation)
        val handle = requireNotNull(worker.newResumableSearch({ Double.POSITIVE_INFINITY }, Cancellation.Never))
        val result = assertIs<MinimizeResult.Optimal>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})

        val checked = verifier.proof(worker, EvidenceKind.Bound, CompletedEngineResult.Minimize(invocation, result))

        assertIs<Verification.Accepted<*, *>>(checked)
        handle.close()
        worker.close()
    }
}
