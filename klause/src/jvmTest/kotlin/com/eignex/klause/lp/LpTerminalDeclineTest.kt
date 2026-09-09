package com.eignex.klause.lp

import com.eignex.klause.formats.mps.MpsConstraint
import com.eignex.klause.formats.mps.MpsModel
import com.eignex.klause.formats.mps.MpsObjective
import com.eignex.klause.formats.mps.MpsVar
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.ComponentLpSolverCapability
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpNeighborhood
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpCertificationPolicy
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FiniteSolveCallbacks
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.FiniteSolveRequest
import com.eignex.klause.solver.pipeline.FiniteSolveShape
import com.eignex.klause.solver.pipeline.FiniteSolveVerdict
import com.eignex.klause.solver.pipeline.solve
import com.eignex.klause.util.Cancellation
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class TerminalRecordingFactory : LpEngineFactory {
    var generalSolvers = 0
        private set
    var generalSolves = 0
        private set
    var generalCloses = 0
        private set
    var persistentSolversCreated = 0
        private set
    var persistentCloses = 0
        private set
    val cancellations = ArrayList<Cancellation>()

    override fun newGeneralSolver(model: LpModel, cancellation: Cancellation): LpSolver {
        generalSolvers++
        cancellations += cancellation
        val delegate = ProductionLpEngineFactory.newGeneralSolver(model, cancellation)
        return object : LpSolver by delegate {
            override fun solve(warm: Basis?): FloatLpResult? {
                generalSolves++
                return delegate.solve(warm)
            }

            override fun close() {
                generalCloses++
                delegate.close()
            }
        }
    }

    override fun newComponentSolver(
        model: LpModel,
        parts: List<LpNeighborhood>,
        solvers: List<LpSolver>,
        isolated: IntArray,
    ): ComponentLpSolverCapability = ProductionLpEngineFactory.newComponentSolver(model, parts, solvers, isolated)

    override fun newTableauSolver(
        model: LpModel,
        cancellation: Cancellation,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
    ): TableauCutSolver = ProductionLpEngineFactory.newTableauSolver(
        model,
        cancellation,
        iterationLimit,
        workLimit,
        trackDegeneracy,
    )

    override fun newPersistentSolver(
        model: LpModel,
        cancellation: Cancellation,
        refactorUpdateLimit: Int,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
    ): PersistentLpSolver {
        persistentSolversCreated++
        val delegate = ProductionLpEngineFactory.newPersistentSolver(
            model,
            cancellation,
            refactorUpdateLimit,
            iterationLimit,
            workLimit,
            trackDegeneracy,
        )
        return object : PersistentLpSolver by delegate {
            private var closed = false

            override fun close() {
                if (closed) return
                closed = true
                persistentCloses++
                delegate.close()
            }
        }
    }
}

private class TerminalPolicy(private val accept: (certifier: LpCertifier, successful: Boolean) -> Boolean) :
    LpCertificationPolicy {
    val attempts = ArrayList<Pair<LpCertifier, Boolean>>()

    override fun accepts(certifier: LpCertifier, successful: Boolean): Boolean {
        attempts += certifier to successful
        return accept(certifier, successful)
    }

    fun observedSuccessful(certifier: LpCertifier): Boolean = certifier to true in attempts
}

class LpTerminalDeclineTest {
    companion object {
        private val continuous = continuousMps(includeInteger = false)
        private val mixed = continuousMps(includeInteger = true)

        @BeforeClass
        @JvmStatic
        fun warmRuntime() {
            run(continuous, optimize = false)
            run(continuous, optimize = true)
            run(mixed, optimize = true)
        }

        private fun continuousMps(includeInteger: Boolean) = MpsModel(
            name = if (includeInteger) "mixed-terminal" else "continuous-terminal",
            sense = ObjectiveSense.MINIMIZE,
            objective = MpsObjective("cost", intArrayOf(0), doubleArrayOf(1.0), 0.0),
            variables = buildList {
                add(MpsVar("x", integer = false, lower = 0.0, upper = 1.0))
                if (includeInteger) add(MpsVar("choice", integer = true, lower = 0.0, upper = 1.0))
            },
            constraints = listOf(
                MpsConstraint("fractional", intArrayOf(0), doubleArrayOf(1.0), lower = 0.5, upper = 0.5),
            ),
        ).toProblem()

        private fun run(
            compiled: com.eignex.klause.formats.mps.MpsCompiled,
            optimize: Boolean,
            context: LpSolveContext? = null,
            cancellation: Cancellation = Cancellation.Never,
            samples: MutableList<Sample>? = null,
            allSolutions: Boolean = false,
        ): FiniteSolveOutcome.Completed {
            val objective = compiled.objective?.toLinearObjective()
            val shape = FiniteSolveShape(
                problem = compiled.model,
                optimize = optimize,
                maximize = false,
                localSearchObjective = null,
                linearObjective = objective,
                objectiveIntVar = null,
                definitionalSweep = null,
            )
            val request = FiniteSolveRequest(
                shape = shape,
                engine = FiniteEngine.FIXED,
                presolveConfig = PresolveConfig.NONE,
                explicitPresolveConfig = true,
                solutionSetSensitive = allSolutions,
                cancellation = cancellation,
                presolveBudget = null,
                cores = 1,
                engineParams = emptyList(),
                randomSeed = 1L,
                defaultArms = 1,
                lpConfig = LpConfig(),
                nodeBudget = null,
                solveBudgetMillis = null,
                allSolutions = allSolutions,
                solutionCap = null,
                deadlineExceeded = { false },
                onEvent = null,
                onPortfolioEvent = null,
            )
            val callbacks = FiniteSolveCallbacks(onSample = { samples?.add(it) }, onImprovement = {})
            val result = if (context == null) {
                FinitePipeline.solve(request, callbacks)
            } else {
                FinitePipeline.solve(request, callbacks, context)
            }
            return result.outcome as FiniteSolveOutcome.Completed
        }
    }

    @Test
    fun `finite MPS satisfaction decline is unknown`() {
        val rejectingFactory = TerminalRecordingFactory()
        val rejectingPolicy = TerminalPolicy { _, _ -> false }
        val acceptingFactory = TerminalRecordingFactory()
        val acceptedSamples = ArrayList<Sample>()
        val declined = run(
            continuous,
            optimize = false,
            context = LpSolveContext(rejectingFactory, rejectingPolicy),
        )
        val accepted = run(
            continuous,
            optimize = false,
            context = LpSolveContext(acceptingFactory, ProductionLpCertificationPolicy),
            samples = acceptedSamples,
        )
        assertEquals(FiniteSolveVerdict.UNKNOWN, declined.verdict)
        assertEquals(0L, declined.solutions)
        assertEquals(FiniteSolveVerdict.SAT, accepted.verdict)
        assertEquals(1L, accepted.solutions)
        assertEquals(0.5, acceptedSamples.single().reals.single())
        assertTrue(rejectingFactory.generalSolves >= 1)
        assertTrue(acceptingFactory.generalSolves >= 1)
        assertTrue(rejectingFactory.persistentSolversCreated >= 1)
        assertTrue(acceptingFactory.persistentSolversCreated >= 1)
        assertTrue(rejectingPolicy.observedSuccessful(LpCertifier.EXACT_BASIS))
        assertTrue(rejectingPolicy.observedSuccessful(LpCertifier.EXACT_POINT))
        assertTrue(rejectingPolicy.observedSuccessful(LpCertifier.RATIONAL))
        assertEquals(rejectingFactory.generalSolvers, rejectingFactory.generalCloses)
        assertEquals(acceptingFactory.generalSolvers, acceptingFactory.generalCloses)
        assertEquals(rejectingFactory.persistentSolversCreated, rejectingFactory.persistentCloses)
        assertEquals(acceptingFactory.persistentSolversCreated, acceptingFactory.persistentCloses)
    }

    @Test
    fun `finite MPS enumeration decline is unknown`() {
        val factory = TerminalRecordingFactory()
        val policy = TerminalPolicy { _, _ -> false }
        val result = run(
            continuous,
            optimize = false,
            context = LpSolveContext(factory, policy),
            allSolutions = true,
        )

        assertEquals(FiniteSolveVerdict.UNKNOWN, result.verdict)
        assertEquals(0L, result.solutions)
        assertTrue(factory.generalSolves >= 1)
        assertTrue(policy.observedSuccessful(LpCertifier.RATIONAL))
        assertEquals(factory.generalSolvers, factory.generalCloses)
        assertEquals(factory.persistentSolversCreated, factory.persistentCloses)
    }

    @Test
    fun `sequential LP solve contexts stay isolated`() {
        val rejectingFactory = TerminalRecordingFactory()
        val rejectingPolicy = TerminalPolicy { _, _ -> false }
        val acceptingFactory = TerminalRecordingFactory()
        val first = run(
            continuous,
            optimize = false,
            context = LpSolveContext(rejectingFactory, rejectingPolicy),
        )
        val middle = run(
            continuous,
            optimize = false,
            context = LpSolveContext(acceptingFactory, ProductionLpCertificationPolicy),
        )
        val last = run(
            continuous,
            optimize = false,
            context = LpSolveContext(rejectingFactory, rejectingPolicy),
        )

        assertEquals(FiniteSolveVerdict.UNKNOWN, first.verdict)
        assertEquals(FiniteSolveVerdict.SAT, middle.verdict)
        assertEquals(FiniteSolveVerdict.UNKNOWN, last.verdict)
        assertTrue(rejectingFactory.generalSolves >= 2)
        assertTrue(acceptingFactory.generalSolves >= 1)
        assertEquals(rejectingFactory.generalSolvers, rejectingFactory.generalCloses)
        assertEquals(acceptingFactory.generalSolvers, acceptingFactory.generalCloses)
        assertEquals(rejectingFactory.persistentSolversCreated, rejectingFactory.persistentCloses)
        assertEquals(acceptingFactory.persistentSolversCreated, acceptingFactory.persistentCloses)
    }

    @Test
    fun `finite MPS optimizer decline without incumbent is unknown`() {
        val factory = TerminalRecordingFactory()
        val policy = TerminalPolicy { _, _ -> false }
        val declined = run(continuous, optimize = true, context = LpSolveContext(factory, policy))
        val accepted = run(continuous, optimize = true)

        assertEquals(FiniteSolveVerdict.UNKNOWN, declined.verdict)
        assertEquals(0L, declined.solutions)
        assertEquals(FiniteSolveVerdict.OPTIMAL, accepted.verdict)
        assertTrue(factory.generalSolves >= 1)
        assertTrue(policy.observedSuccessful(LpCertifier.RATIONAL))
        assertEquals(factory.generalSolvers, factory.generalCloses)
        assertEquals(factory.persistentSolversCreated, factory.persistentCloses)
    }

    @Test
    fun `finite MPS optimizer keeps incumbent non optimal after a later decline`() {
        val factory = TerminalRecordingFactory()
        val policy = TerminalPolicy { _, successful -> successful && factory.generalSolvers == 1 }
        val result = run(mixed, optimize = true, context = LpSolveContext(factory, policy))
        val accepted = run(mixed, optimize = true)

        assertEquals(FiniteSolveVerdict.BEST_FOUND, result.verdict)
        assertNotNull(result.bestSample)
        assertEquals(FiniteSolveVerdict.OPTIMAL, accepted.verdict)
        assertTrue(factory.generalSolvers >= 2)
        assertTrue(policy.observedSuccessful(LpCertifier.RATIONAL))
        assertEquals(factory.generalSolvers, factory.generalCloses)
        assertEquals(factory.persistentSolversCreated, factory.persistentCloses)
    }

    @Test
    fun `a feasible continuous incumbent without an objective proof stays best found`() {
        val factory = TerminalRecordingFactory()
        val policy = TerminalPolicy { certifier, successful ->
            successful && certifier != LpCertifier.INTEGER && certifier != LpCertifier.SAFE_OBJECTIVE &&
                certifier != LpCertifier.RATIONAL
        }

        val result = run(continuous, optimize = true, context = LpSolveContext(factory, policy))

        assertEquals(FiniteSolveVerdict.BEST_FOUND, result.verdict)
        assertEquals(0.5, assertNotNull(result.bestSample).reals.single())
        assertTrue(policy.observedSuccessful(LpCertifier.EXACT_BASIS))
        assertEquals(factory.generalSolvers, factory.generalCloses)
        assertEquals(factory.persistentSolversCreated, factory.persistentCloses)
    }

    @Test
    fun `a bound alone cannot accept a continuous satisfaction leaf`() {
        val factory = TerminalRecordingFactory()
        val policy = TerminalPolicy { certifier, successful -> successful && certifier == LpCertifier.INTEGER }

        val result = run(continuous, optimize = false, context = LpSolveContext(factory, policy))

        assertEquals(FiniteSolveVerdict.UNKNOWN, result.verdict)
        assertEquals(0L, result.solutions)
        assertTrue(policy.observedSuccessful(LpCertifier.INTEGER))
        assertEquals(factory.generalSolvers, factory.generalCloses)
        assertEquals(factory.persistentSolversCreated, factory.persistentCloses)
    }

    @Test
    fun `cancellation during proof fallback stays unknown without leaking context`() {
        var cancelled = false
        val token = Cancellation { cancelled }
        val factory = TerminalRecordingFactory()
        val policy = TerminalPolicy { _, successful ->
            if (successful) cancelled = true
            false
        }
        val declined = run(
            continuous,
            optimize = false,
            context = LpSolveContext(factory, policy),
            cancellation = token,
        )
        val accepted = run(continuous, optimize = false)

        assertEquals(FiniteSolveVerdict.UNKNOWN, declined.verdict)
        assertEquals(FiniteSolveVerdict.SAT, accepted.verdict)
        assertTrue(factory.generalSolves >= 1)
        assertTrue(factory.cancellations.all { it === token })
        assertTrue(LpCertifier.RATIONAL to false in policy.attempts)
        assertFalse(policy.observedSuccessful(LpCertifier.RATIONAL))
        assertEquals(factory.generalSolvers, factory.generalCloses)
        assertEquals(factory.persistentSolversCreated, factory.persistentCloses)
    }
}
