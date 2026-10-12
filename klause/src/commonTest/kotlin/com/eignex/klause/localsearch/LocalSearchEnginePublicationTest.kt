package com.eignex.klause.localsearch

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.meta.alns.Alns
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSearchEnginePublicationTest {
    private val factor = object : Factor by Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))), Propagator {
        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val first = state.boolValues[0] ?: return true
            val second = state.boolValues[1] ?: return true
            return first == second
        }
    }
    private val source = Problem(2, 0, emptyArray(), arrayOf<Factor>(factor))
    private val invalid = Sample(booleanArrayOf(false, true), LongArray(0))
    private val valid = Sample(booleanArrayOf(true, true), LongArray(0))
    private val objective = LinearObjective(boolWeights = longArrayOf(1, 1))
    private val params = LocalSearchParams(maxFlips = 1L, randomSeed = 7L, initialAssignment = invalid)

    @Test
    fun `zero score does not publish an invalid propagation only candidate`() {
        for (operation in listOf("solve", "minimize", "sample", "alns")) {
            val solver = LocalSearchSolver(source.bake(), greedyRepairOnRestart = false)

            when (operation) {
                "solve" -> assertIs<SolveResult.Unknown>(solver.solve(params))
                "minimize" -> assertIs<MinimizeResult.Unknown>(solver.minimize(objective, params))
                "sample" -> assertTrue(solver.samples(params).toList().isEmpty())
                "alns" -> assertIs<MinimizeResult.Unknown>(
                    Alns(inner = solver, maxIterations = 0).minimize(objective, params),
                )
            }
        }
    }

    @Test
    fun `a valid propagation only candidate remains publishable`() {
        for (operation in listOf("solve", "minimize", "sample")) {
            val solver = LocalSearchSolver(source.bake(), greedyRepairOnRestart = false)
            val pinned = params.copy(initialAssignment = valid)

            val sample = when (operation) {
                "solve" -> assertIs<SolveResult.Sat>(solver.solve(pinned)).assignment
                "minimize" -> assertIs<MinimizeResult.BestFound>(solver.minimize(objective, pinned)).sample
                else -> solver.samples(pinned).first()
            }

            assertEquals(valid, sample)
        }
    }

    @Test
    fun `candidate rejection permits a valid restart alternative`() {
        val restart = object : RestartPolicy {
            override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = false
            override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                state.assignment.setBool(0, true)
                state.assignment.setBool(1, true)
                state.recompute()
            }
        }
        val solver = LocalSearchSolver(source.bake(), restartPolicy = restart, greedyRepairOnRestart = false)

        val result = solver.solve(params.copy(maxFlips = 2L))

        assertEquals(valid, assertIs<SolveResult.Sat>(result).assignment)
    }

    @Test
    fun `candidate checks preserve scoped assumptions without retaining rejection`() {
        val solver = LocalSearchSolver(source.bake(), greedyRepairOnRestart = false)
        val invalidScope = params.copy(assumptions = Assumptions(bools = mapOf(1 to true)))
        assertIs<SolveResult.Unknown>(solver.solve(invalidScope))

        val scoped = params.copy(initialAssignment = valid, assumptions = Assumptions(bools = mapOf(0 to true)))
        val result = solver.solve(scoped)

        assertEquals(valid, assertIs<SolveResult.Sat>(result).assignment)
    }

    @Test
    fun `uncovered constraints are checked before caller completion`() {
        var calls = 0
        val solver = LocalSearchSolver(
            source.bake(), greedyRepairOnRestart = false,
            completion = CandidateCompletion { point, _ ->
                calls++
                Completion.Witness(point)
            },
        )

        assertIs<SolveResult.Unknown>(solver.solve(params))
        assertEquals(0, calls)
        assertIs<SolveResult.Sat>(solver.solve(params.copy(initialAssignment = valid)))
        assertEquals(1, calls)
    }

    @Test
    fun `completion cannot replace verified discrete coordinates`() {
        val solver = LocalSearchSolver(
            source.bake(), greedyRepairOnRestart = false,
            completion = CandidateCompletion { _, _ -> Completion.Witness(invalid) },
        )

        val result = solver.solve(params.copy(initialAssignment = valid))

        assertIs<SolveResult.Unknown>(result)
    }

    @Test
    fun `cancellation from a completion hook withholds its witness`() {
        var cancelled = false
        val solver = LocalSearchSolver(
            source.bake(), greedyRepairOnRestart = false,
            completion = CandidateCompletion { point, _ ->
                cancelled = true
                Completion.Witness(point)
            },
        )

        val result = solver.solve(params.copy(initialAssignment = valid, cancellation = Cancellation { cancelled }))

        assertIs<SolveResult.Unknown>(result)
    }

    @Test
    fun `finite source checks preserve exact real completion`() {
        val problem = Problem(
            2, 0, emptyArray(), arrayOf<Factor>(factor), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        ).bake()
        val exact = BigFraction.of(bigIntOf(1), bigIntOf(3))
        val completed = Sample(
            booleanArrayOf(true, true), LongArray(0), doubleArrayOf(exact.toDouble()), exactReals = listOf(exact),
        )
        val solver = LocalSearchSolver(
            problem, greedyRepairOnRestart = false,
            completion = CandidateCompletion { _, _ -> Completion.Witness(completed) },
        )

        val result = solver.solve(params.copy(initialAssignment = completed))

        assertEquals(exact, assertIs<SolveResult.Sat>(result).assignment.exactReals?.get(0))
    }

    @Test
    fun `cancellation during full assignment checking withholds the witness`() {
        var cancelled = false
        val interrupting = object : Factor by factor, Propagator {
            override fun propagate(state: PropagationState, factorId: Int): Boolean {
                if (state.boolValues[0] != null && state.boolValues[1] != null) cancelled = true
                return true
            }
        }
        val problem = source.withFactors(arrayOf(interrupting)).bake()
        val solver = LocalSearchSolver(problem, greedyRepairOnRestart = false)

        val result = solver.solve(params.copy(initialAssignment = valid, cancellation = Cancellation { cancelled }))

        assertTrue(cancelled)
        assertIs<SolveResult.Unknown>(result)
    }

    @Test
    fun `a cancelled candidate check resumes without publishing partial state`() {
        var cancelled = false
        var interrupt = true
        val interrupting = object : Factor by factor, Propagator {
            override fun propagate(state: PropagationState, factorId: Int): Boolean {
                if (interrupt && state.boolValues[0] != null && state.boolValues[1] != null) cancelled = true
                return true
            }
        }
        val solver = LocalSearchSolver(source.withFactors(arrayOf(interrupting)).bake(), greedyRepairOnRestart = false)
        val scoped = params.copy(initialAssignment = valid)
        solver.resumableSolve(scoped).use { handle ->
            assertNull(handle.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1L))
            assertTrue(cancelled)
            assertFalse(handle.isDone)
            interrupt = false
            cancelled = false

            val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L)

            assertEquals(valid, assertIs<SolveResult.Sat>(result).assignment)
        }
    }

    @Test
    fun `standalone and portfolio enforce the same propagation only source`() {
        val problem = source.bake()
        val solver = LocalSearchSolver(problem, greedyRepairOnRestart = false)
        val worker = PortfolioWorker.of("ls", 0, solver.session(), params.copy(initialAssignment = valid))

        val standalone = assertIs<SolveResult.Sat>(solver.solve(params.copy(initialAssignment = valid))).assignment
        val paired = Portfolio.thompson(listOf(worker)).use {
            assertIs<SolveResult.Sat>(it.solve(Cancellation.Never)).assignment
        }
        val complete = assertIs<SolveResult.Sat>(BacktrackSolver(problem).solve(BacktrackParams())).assignment

        for (sample in listOf(standalone, paired, complete)) assertEquals(sample.boolValue(0), sample.boolValue(1))
    }

    @Test
    fun `covered custom invariants do not invoke publication propagation`() {
        var fullChecks = 0
        val covered = object : Factor by factor, Propagator, Invariant {
            override fun propagate(state: PropagationState, factorId: Int): Boolean {
                if (state.boolValues[0] != null && state.boolValues[1] != null) fullChecks++
                return true
            }
            override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
                state.assignment.boolValue(0) != state.assignment.boolValue(1)
        }
        val solver = LocalSearchSolver(source.withFactors(arrayOf(covered)).bake(), greedyRepairOnRestart = false)

        val result = solver.solve(params.copy(initialAssignment = valid))

        assertIs<SolveResult.Sat>(result)
        assertEquals(0, fullChecks)
    }
}
