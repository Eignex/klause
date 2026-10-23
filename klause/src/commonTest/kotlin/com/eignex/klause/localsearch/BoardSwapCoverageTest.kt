package com.eignex.klause.localsearch

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.SatisfiedStructured
import com.eignex.klause.localsearch.movesource.freshState
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardSwapCoverageTest {
    @Test
    fun `bounded picks should cover every pair of each board`() {
        val state = state()
        val sink = MoveSink()
        val source = SatisfiedStructured.structural(1)
        val pairs = mutableSetOf<Set<Int>>()

        repeat(5) {
            sink.clear()
            source.generate(state, sink)
            assertTrue(sink.list.size <= 4)
            for (move in sink.list) {
                val parts = (move as Move.Compound).parts.map { it as Move.IntSet }
                assertTrue(pairs.add(parts.map { it.varId }.toSet()))
                assertEquals(0L, state.netDelta(move))
            }
        }

        assertFalse(state.boardCoverage.pending)
        assertEquals(20, pairs.size)
    }

    @Test
    fun `a cancelled pick should retain the ungraded pairs for resume`() {
        val resumed = state()
        val uninterrupted = state()
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(SatisfiedStructured.structural(1))),
            feasibleDescent = FeasibleDescent.SelfOwned,
        )
        var polls = 0

        strategy.pickMove(resumed, Cancellation { ++polls >= 3 })
        val resumedMoves = MoveSink()
        val expectedMoves = MoveSink()
        SatisfiedStructured.structural(1).generate(resumed, resumedMoves)
        SatisfiedStructured.structural(1).generate(uninterrupted, expectedMoves)

        assertEquals(expectedMoves.list, resumedMoves.list)
    }

    @Test
    fun `independent states should retain independent board progress`() {
        val first = state()
        val second = state()
        val source = SatisfiedStructured.structural(1)
        val sink = MoveSink()
        source.generate(first, sink)
        val initial = sink.list
        sink.clear()
        source.generate(first, sink)
        assertTrue(initial != sink.list)
        sink.clear()

        source.generate(second, sink)

        assertEquals(initial, sink.list)
    }

    @Test
    fun `assignment changes should restart coverage at the new assignment`() {
        val state = state()
        val source = SatisfiedStructured.structural(1)
        val sink = MoveSink()
        source.generate(state, sink)
        state.apply(sink.list.first())
        sink.clear()

        source.generate(state, sink)

        val first = sink.list.first() as Move.Compound
        assertEquals(listOf(Move.IntSet(0, 0), Move.IntSet(1, 1)), first.parts)
    }

    @Test
    fun `recomputation should discard completed coverage`() {
        val state = state()
        val source = SatisfiedStructured.structural(1)
        val sink = MoveSink()
        repeat(5) {
            sink.clear()
            source.generate(state, sink)
        }
        assertFalse(state.boardCoverage.pending)
        state.recompute()
        sink.clear()

        source.generate(state, sink)

        assertTrue(sink.list.isNotEmpty())
        assertTrue(state.boardCoverage.pending)
    }

    @Test
    fun `board proposals should preserve pins and foreign ownership atomically`() {
        val state = state()
        state.assumptions = Assumptions(ints = mapOf(0 to 0L))
        val sink = MoveSink(state.assumptions)
        sink.setOwners(IntArray(10) { if (it == 1) 99 else if (it < 5) 0 else 1 })
        val source = SatisfiedStructured.structural(1)
        val moves = mutableListOf<Move>()

        repeat(6) {
            sink.clear()
            source.generate(state, sink)
            moves.addAll(sink.list)
        }
        assertFalse(state.boardCoverage.pending)

        assertEquals(13, moves.size)
        for (move in moves) {
            val parts = (move as Move.Compound).parts.map { it as Move.IntSet }
            assertTrue(parts.none { it.varId == 0 || it.varId == 1 })
            assertEquals(0L, state.netDelta(move))
        }
        assertEquals(MoveSink.NO_PROPOSER, sink.proposer)
    }

    @Test
    fun `suspended walks should find a late board improvement before reporting a local optimum`() {
        for (sliced in listOf(false, true)) {
            val initial = state()
            var optima = 0
            val policy = object : RestartPolicy {
                override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = false
                override fun restart(state: LocalSearchState, bestSoFar: Sample?) = state.restart()
                override fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) {
                    optima++
                }
            }
            val strategy = SourceDrivenStrategy(
                sources = listOf(ConfiguredSource(SatisfiedStructured.structural(1))),
                acceptance = AcceptanceRule.GreedyDescent,
                feasibleAcceptance = AcceptanceRule.GreedyDescent,
                feasibleDescent = FeasibleDescent.SelfOwned,
                feasibleResampleCap = 0,
            )
            val engine = LocalSearchEngine(initial.model, strategy = strategy, restartPolicy = policy)
            engine.resumable(
                LinearObjective(intCoefficients = LongArray(10) { if (it == 9) 1L else 0L }),
                LocalSearchParams(maxFlips = 5L, randomSeed = 7L, initialAssignment = initial.assignment.snapshot()),
            ).use { handle ->
                val counted = assertIs<InstructionSlicedSearch>(handle)
                var result: MinimizeResult? = null
                repeat(if (sliced) 128 else 1) {
                    if (!handle.isDone) result = counted.runInstructionSlice(
                        Cancellation.Never, Long.MAX_VALUE, if (sliced) 1L else Long.MAX_VALUE,
                    ) {}
                }
                assertEquals(0.0, assertIs<MinimizeResult.BestFound>(result).objective)
                assertEquals(5L, handle.stats.ls.moves.sum.toLong())
            }
            assertEquals(0, optima)
        }
    }

    private fun state(): LocalSearchState {
        val problem = Problem(
            0, 10, Array(10) { IntDomain(0, 4) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1, 2, 3, 4), domainMin = 0, domainSize = 5),
                AllDifferent(intArrayOf(5, 6, 7, 8, 9), domainMin = 0, domainSize = 5),
            ),
        )
        return freshState(problem, 7L).also { state ->
            repeat(10) { state.assignment.setInt(it, (it % 5).toLong()) }
            state.recompute()
        }
    }
}
