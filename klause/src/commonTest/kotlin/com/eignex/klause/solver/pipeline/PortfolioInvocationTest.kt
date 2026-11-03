package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.ScriptedSearch
import com.eignex.klause.portfolio.SourceWorkerBinding
import com.eignex.klause.portfolio.trackingWorker
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Session
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class PortfolioInvocationTest {
    @Test
    fun `probe ordering should preserve source factory identities and seeds`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val objective = LinearObjective()
        val plan = source.prepareExecutionPlan(objective)
        val runtime = EngineInvocation(plan, ModelIdentity.of(source, objective), objective)
        val seeds = mutableListOf<Long>()
        val visits = mutableListOf<Int>()
        val factories = List(4) { arm ->
            PortfolioArmFactory(plan, arm, if (arm < 2) ArmFamily.Backtrack else ArmFamily.LocalSearch,
                binding = { SourceWorkerBinding(runtime) }) { slot ->
                seeds += slot.seed
                trackingWorker("worker$arm", arm, ScriptedSearch({ false }) { visits += arm })
            }
        }

        PortfolioInvocation(plan, factories,
            PortfolioInvocationControls(seed = 17, cancellation = Cancellation { visits.size >= 4 }),
            PortfolioEvidence(runtime.model, CandidateVerifier.trusting()), objective,
        ) { workers ->
            PortfolioInvocationPolicy(probeRanks = heuristicSecondProbeRanks(workers))
        }.use { it.minimize(Cancellation.Never, null) }

        assertEquals(listOf(17L, 18L, 19L, 20L), seeds)
        assertEquals(listOf(0, 2, 1, 3), visits)
    }

    @Test
    fun `parallel construction should create seeded private slots and close them once`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        val seeds = mutableListOf<Long>()
        val closed = mutableListOf<Int>()
        val factory = PortfolioArmFactory(
            plan,
            0,
            ArmFamily.Backtrack,
            binding = { SourceWorkerBinding(EngineInvocation(plan, ModelIdentity.of(source))) },
        ) { slot ->
            seeds += slot.seed
            slot.own(AutoCloseable { closed += slot.index })
            PortfolioWorker.ofSolve("source", 0) { _, _ -> SolveResult.Unknown(TerminationReason.BudgetExhausted) }
        }
        val invocation = PortfolioInvocation(
            plan,
            listOf(factory),
            PortfolioInvocationControls(parallelism = 4, seed = 17),
            PortfolioEvidence(ModelIdentity.of(source), CandidateVerifier.trusting()),
        )

        invocation.close()
        invocation.close()

        assertEquals(listOf(17L, 18L, 19L, 20L), seeds)
        assertEquals(listOf(3, 2, 1, 0), closed)
        assertEquals(4, invocation.resources.lanes)
    }

    @Test
    fun `construction failure should close private source state allocated by earlier slots`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        val foreign = Problem(0, 0, emptyArray(), emptyArray()).prepareExecutionPlan()
        var closed = 0
        val first = PortfolioArmFactory(
            plan,
            0,
            ArmFamily.Backtrack,
            binding = { SourceWorkerBinding(EngineInvocation(plan, ModelIdentity.of(source))) },
        ) { slot ->
            slot.own(AutoCloseable { closed++ })
            PortfolioWorker.ofSolve("source", 0) { _, _ -> SolveResult.Unknown(TerminationReason.BudgetExhausted) }
        }
        val second = PortfolioArmFactory(foreign, 1, ArmFamily.LocalSearch) {
            error("foreign plan cannot allocate private state")
        }

        assertFailsWith<IllegalArgumentException> {
            PortfolioInvocation(
                plan,
                listOf(first, second),
                PortfolioInvocationControls(),
                PortfolioEvidence(ModelIdentity.of(source), CandidateVerifier.trusting()),
            )
        }

        assertEquals(1, closed)
    }

    @Test
    fun `containing cancellation should prevent entry into an admitted worker`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        var entered = false
        val factory = PortfolioArmFactory(
            plan,
            0,
            ArmFamily.Backtrack,
            binding = { SourceWorkerBinding(EngineInvocation(plan, ModelIdentity.of(source))) },
        ) {
            PortfolioWorker.ofSolve("source", 0) { _, _ ->
                entered = true
                SolveResult.Unknown(TerminationReason.BudgetExhausted)
            }
        }
        val invocation = PortfolioInvocation(
            plan,
            listOf(factory),
            PortfolioInvocationControls(cancellation = Cancellation { true }),
            PortfolioEvidence(ModelIdentity.of(source), CandidateVerifier.trusting()),
        )

        val result = invocation.use { it.solve(Cancellation.Never) }

        assertIs<SolveResult.Unknown>(result)
        assertFalse(entered)
    }

    @Test
    fun `parallel replicas should install complete bindings before policy admission`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        val runtime = EngineInvocation(plan, ModelIdentity.of(source))
        val bindings = mutableListOf<SourceWorkerBinding>()
        val factory = PortfolioArmFactory(
            plan,
            0,
            ArmFamily.Backtrack,
            binding = { SourceWorkerBinding(runtime, sharingInvocation = runtime) },
        ) {
            PortfolioWorker.ofSolve("replica", 0) { _, _ -> SolveResult.Unsat() }
        }

        PortfolioInvocation(
            plan,
            listOf(factory),
            PortfolioInvocationControls(parallelism = 3),
            PortfolioEvidence(runtime.model, CandidateVerifier.trusting()),
        ) { workers ->
            for (worker in workers) {
                val binding = assertNotNull(worker.sourceBinding)
                assertSame(runtime, binding.invocation)
                assertSame(runtime, binding.sharingInvocation)
                bindings += binding
            }
            PortfolioInvocationPolicy()
        }.close()

        assertEquals(3, bindings.size)
        assertEquals(3, bindings.toSet().size)
    }

    @Test
    fun `source scheduling should reject model only worker metadata before policy admission`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        var admitted = false
        val factory = PortfolioArmFactory(plan, 0, ArmFamily.Backtrack) {
            PortfolioWorker.ofSolve("incomplete", 0) { _, _ -> SolveResult.Unsat() }
                .bindEvidence(ModelIdentity.of(source))
        }

        assertFailsWith<IllegalArgumentException> {
            PortfolioInvocation(
                plan,
                listOf(factory),
                PortfolioInvocationControls(),
                PortfolioEvidence(ModelIdentity.of(source), CandidateVerifier.trusting()),
            ) {
                admitted = true
                PortfolioInvocationPolicy()
            }
        }

        assertFalse(admitted)
    }

    @Test
    fun `failed replica binding should close workers before reversing private source owners`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        val runtime = EngineInvocation(plan, ModelIdentity.of(source))
        val foreign = EngineInvocation(source.prepareExecutionPlan(), runtime.model)
        val closed = mutableListOf<String>()
        val factory = PortfolioArmFactory(plan, 0, ArmFamily.Backtrack, binding = {
            SourceWorkerBinding(if (it.label == "1") foreign else runtime)
        }) { slot ->
            slot.own(AutoCloseable { closed += "source${slot.index}" })
            val delegate = BacktrackSolver(source.bake()).session()
            val session = object : Session<BacktrackParams> by delegate {
                override fun close() {
                    closed += "worker${slot.index}"
                    delegate.close()
                }
            }
            PortfolioWorker.of(slot.index.toString(), 0, session, BacktrackParams())
        }

        assertFailsWith<IllegalArgumentException> {
            PortfolioInvocation(
                plan,
                listOf(factory),
                PortfolioInvocationControls(parallelism = 2),
                PortfolioEvidence(runtime.model, CandidateVerifier.trusting()),
            )
        }

        assertEquals(listOf("worker1", "worker0", "source1", "source0"), closed)
    }

    @Test
    fun `binding failure should stay primary when worker close throws the same failure`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        val primary = IllegalArgumentException("invalid binding")
        var sourceClosed = false
        val delegate = BacktrackSolver(source.bake()).session()
        val session = object : Session<BacktrackParams> by delegate {
            override fun close() {
                delegate.close()
                throw primary
            }
        }
        val factory = PortfolioArmFactory(plan, 0, ArmFamily.Backtrack, binding = { throw primary }) { slot ->
            slot.own(AutoCloseable { sourceClosed = true })
            PortfolioWorker.of("failure", 0, session, BacktrackParams())
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            PortfolioInvocation(
                plan,
                listOf(factory),
                PortfolioInvocationControls(),
                PortfolioEvidence(ModelIdentity.of(source), CandidateVerifier.trusting()),
            )
        }

        assertSame(primary, failure)
        assertEquals(true, sourceClosed)
    }
}
