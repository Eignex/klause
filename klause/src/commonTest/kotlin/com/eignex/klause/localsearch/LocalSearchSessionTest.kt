package com.eignex.klause.localsearch

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.count.SampleQuality
import com.eignex.klause.count.SamplingConfig
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchStream
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSearchSessionTest {

    private fun weightLearningProblem(): Problem = Problem(
        numBoolVars = 6,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Cardinality.exactlyOne(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
            Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
        ),
    )

    @Test
    fun `optimization resumes the state prepared after its slice expires`() {
        var expired = false
        var initializations = 0
        var primed = false
        val restart = FixedCadenceRestart()
        val policy = object : RestartPolicy by restart {
            override fun reset() {
                restart.reset()
                initializations++
            }

            override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
                restart.restart(state, bestSoFar)
                if (!primed) {
                    expired = true
                    primed = true
                }
            }
        }
        val session = LocalSearchSolver(weightLearningProblem().bake(), restartPolicy = policy).session()
        val objective = LinearObjective(boolWeights = LongArray(6) { 1L })

        session.resumable(objective, LocalSearchParams(maxInstructions = 5L, randomSeed = 1L)).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L) {})
            assertEquals(0.0, handle.stats.ls.moves.sum)
            expired = false
            val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {}

            assertIs<MinimizeResult.Unknown>(result)
            assertEquals(5.0, handle.stats.ls.moves.sum)
            assertEquals(1, initializations)
        }
    }

    @Test
    fun `sliced optimization preserves the unsliced walk and learned weights`() {
        val objective = LinearObjective(boolWeights = LongArray(6) { 1L })
        val params = LocalSearchParams(maxFlips = 12L, randomSeed = 7L)
        val uninterrupted = LocalSearchSolver(weightLearningProblem().bake()).session()
        val expected = uninterrupted.minimize(objective, params)
        val sliced = LocalSearchSolver(weightLearningProblem().bake()).session()

        sliced.resumable(objective, params).use { handle ->
            val counted = assertIs<InstructionSlicedSearch>(handle)
            repeat(3) {
                assertNull(counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 3L) {})
                assertFalse(handle.isDone)
            }
            val result = assertNotNull(counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 3L) {})

            assertEquals(expected.stats.ls.moves, result.stats.ls.moves)
            assertEquals(expected.stats.ls.incumbentViolation, result.stats.ls.incumbentViolation)
            assertTrue(handle.isDone)
            assertTrue(
                assertNotNull(uninterrupted.warmState.factorWeights)
                    .contentEquals(assertNotNull(sliced.warmState.factorWeights)),
            )
        }
    }

    @Test
    fun `optimization handle keeps the session assumption stack`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray())
        val session = LocalSearchSolver(problem.bake()).session()
        session.push(Assumptions(bools = mapOf(0 to true)))

        session.resumable(LinearObjective(boolWeights = longArrayOf(1)), LocalSearchParams(maxFlips = 1L))
            .use { handle ->
                val result = assertIs<MinimizeResult.BestFound>(
                    handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {},
                )

                assertTrue(result.sample.bools[0])
                assertEquals(1.0, result.objective)
            }
    }

    @Test
    fun `optimization stops when a sibling spends the shared allowance`() {
        val budget = NodeBudget(10L)
        val session = LocalSearchSolver(weightLearningProblem().bake()).session()
        val params = LocalSearchParams(maxFlips = 100L, nodeBudget = budget, randomSeed = 7L)

        session.resumable(LinearObjective(boolWeights = LongArray(6) { 1L }), params).use { handle ->
            val counted = assertIs<InstructionSlicedSearch>(handle)
            assertNull(counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 3L) {})
            budget.spendMoves(5L)
            val spent = budget.spent
            val result = counted.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 3L) {}

            assertIs<MinimizeResult.Unknown>(result)
            assertEquals(3.0, handle.stats.ls.moves.sum)
            assertEquals(spent, budget.spent)
        }
    }

    @Test
    fun `early stream close retains published warm state and releases the solver`() {
        val factories = listOf<(LocalSearchSession, LocalSearchParams) -> SearchStream<*>>(
            { session, params -> session.openSamples(params) },
            { session, params -> session.openSamples(SamplingConfig(quality = SampleQuality.ACCURATE, seed = 1L), params) },
            { session, params -> session.openImprovements(LinearObjective(boolWeights = longArrayOf(1, 2)), params) },
        )
        for ((index, open) in factories.withIndex()) {
            val problem = Problem(2, 0, emptyArray(), arrayOf<Factor>(Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))))
            val solver = LocalSearchSolver(problem.bake())
            val session = solver.session()
            val params = LocalSearchParams(maxFlips = 2L, randomSeed = 1L)
            val stream = open(session, params)
            assertTrue(stream.hasNext())
            stream.next()
            assertFailsWith<IllegalStateException> { session.reset() }
            assertFailsWith<IllegalStateException> { session.push(Assumptions.None) }
            assertFailsWith<IllegalStateException> { solver.session().solve(params) }
            val captured = if (index == 1) null else assertNotNull(session.warmState.factorWeights).copyOf()
            stream.close()
            stream.close()
            assertFalse(stream.hasNext())
            if (captured != null) assertTrue(captured.contentEquals(session.warmState.factorWeights))
            assertIs<SolveResult.Sat>(solver.session().solve(params))
            session.reset()
        }
    }

    @Test
    fun `closing a session closes its pending walk`() {
        val solver = LocalSearchSolver(weightLearningProblem().bake())
        val session = solver.session()
        val handle = session.resumable(LinearObjective(boolWeights = LongArray(6) { 1L }), LocalSearchParams(maxFlips = 10L))
        assertNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 0L) {})
        session.close()
        assertFailsWith<IllegalStateException> { session.reset() }
        assertFailsWith<IllegalStateException> { handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }
        solver.session().resumable(LinearObjective(), LocalSearchParams(maxFlips = 1L)).close()
    }

    @Test
    fun `reset clears warm state`() {
        val problem = weightLearningProblem()
        val session = LocalSearchSession(LocalSearchSolver(problem.bake(), strategy = Cbls()))
        session.sample(LocalSearchParams(maxFlips = 1_000L, randomSeed = 2L))
        assertNotNull(session.warmState.factorWeights)
        session.reset()
        assertNull(session.warmState.factorWeights)
    }

    @Test
    fun `warm weights survive across two minimize calls`() {
        val problem = weightLearningProblem()
        val session = LocalSearchSession(LocalSearchSolver(problem.bake(), strategy = Cbls()))
        val obj = LinearObjective(boolWeights = LongArray(6) { 1L })
        session.minimize(obj, LocalSearchParams(maxFlips = 1_000L, randomSeed = 5L))
        val firstWeights = session.warmState.factorWeights!!.copyOf()
        session.minimize(obj, LocalSearchParams(maxFlips = 1_000L, randomSeed = 6L))
        val secondWeights = session.warmState.factorWeights!!
        val allOnes = DoubleArray(problem.numFactors) { 1.0 }
        assertTrue(
            !secondWeights.contentEquals(allOnes),
            "second call should have learned weights, not reset to defaults",
        )
        assertTrue(
            firstWeights.size == secondWeights.size,
            "weight array shape must match across calls",
        )
    }

    @Test
    fun `reset clears bestCostSeen alongside other warm fields`() {
        val problem = weightLearningProblem()
        val solver = LocalSearchSolver(problem.bake())
        val session = LocalSearchSession(solver)
        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 3L))
        assertTrue(session.warmStateView.bestCostSeen() < Long.MAX_VALUE)
        session.reset()
        assertEquals(
            Long.MAX_VALUE,
            session.warmStateView.bestCostSeen(),
            "reset should restore the bestCost watermark to its empty default",
        )
    }

    @Test
    fun `bare solver call does not touch the session warm state`() {
        val problem = weightLearningProblem()
        val solver = LocalSearchSolver(problem.bake(), strategy = Cbls())
        val session = LocalSearchSession(solver)
        solver.sample(LocalSearchParams(maxFlips = 1_000L, randomSeed = 9L))
        assertNull(session.warmState.factorWeights, "bare solver call must not write to session warm state")
    }
}
