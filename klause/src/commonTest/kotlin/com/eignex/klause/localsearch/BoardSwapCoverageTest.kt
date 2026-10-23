package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.SatisfiedStructured
import com.eignex.klause.localsearch.movesource.freshState
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardSwapCoverageTest {
    @Test
    fun `bounded picks should cover every pair of each board`() {
        val state = state()
        val sink = MoveSink()
        val source = SatisfiedStructured.structural(1)
        val pairs = mutableSetOf<Set<Int>>()

        repeat(32) {
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
        for (size in listOf(2, 5)) {
            val resumed = state(size)
            val strategy = SourceDrivenStrategy(
                sources = listOf(ConfiguredSource(SatisfiedStructured.structural(1))),
                feasibleDescent = FeasibleDescent.SelfOwned,
            )
            var polls = 0

            resumed.boardCoverage.request(resumed)
            strategy.pickMove(resumed, Cancellation { ++polls >= 3 })
            val resumedMoves = MoveSink()
            val pairs = mutableSetOf<Set<Int>>()
            repeat(32) {
                resumedMoves.clear()
                SatisfiedStructured.structural(1).generate(resumed, resumedMoves)
                for (move in resumedMoves.list) {
                    val ids = (move as Move.Compound).parts.map { (it as Move.IntSet).varId }.toSet()
                    assertTrue(pairs.add(ids))
                }
            }

            assertEquals(size * (size - 1), pairs.size)
            assertFalse(resumed.boardCoverage.pending)
        }
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

        val pairs = mutableSetOf<Set<Int>>()
        repeat(32) {
            sink.clear()
            source.generate(state, sink)
            for (move in sink.list) {
                pairs.add((move as Move.Compound).parts.map { (it as Move.IntSet).varId }.toSet())
            }
        }

        assertEquals(20, pairs.size)
    }

    @Test
    fun `recomputation should discard completed coverage`() {
        val state = state()
        val source = SatisfiedStructured.structural(1)
        val sink = MoveSink()
        repeat(32) {
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

        repeat(32) {
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
    fun `suspended walks should exhaust improving board swaps before reporting a local optimum`() {
        for (sliced in listOf(false, true)) {
            val initial = state()
            val policy = object : RestartPolicy {
                override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = false
                override fun restart(state: LocalSearchState, bestSoFar: Sample?) = Unit
                override fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) {
                    assertEquals(0.0, objective)
                }
            }
            val strategy = SourceDrivenStrategy(
                sources = listOf(ConfiguredSource(SatisfiedStructured.structural(1))),
                acceptance = AcceptanceRule.GreedyDescent,
                feasibleAcceptance = AcceptanceRule.GreedyDescent,
                feasibleDescent = FeasibleDescent.SelfOwned,
                feasibleResampleCap = 32,
            )
            val engine = LocalSearchEngine(initial.model, strategy = strategy, restartPolicy = policy)
            engine.resumable(
                LinearObjective(intCoefficients = LongArray(10) { if (it == 9) 1L else 0L }),
                LocalSearchParams(maxFlips = 64L, randomSeed = 7L, initialAssignment = initial.assignment.snapshot()),
            ).use { handle ->
                val counted = assertIs<InstructionSlicedSearch>(handle)
                var result: MinimizeResult? = null
                repeat(if (sliced) 256 else 1) {
                    if (!handle.isDone) result = counted.runInstructionSlice(
                        Cancellation.Never, Long.MAX_VALUE, if (sliced) 1L else Long.MAX_VALUE,
                    ) {}
                }
                assertEquals(0.0, assertIs<MinimizeResult.BestFound>(result).objective)
                assertEquals(64L, handle.stats.ls.moves.sum.toLong())
            }
        }
    }

    @Test
    fun `maintained board outputs should never become partial swaps`() {
        val problem = Problem(
            0, 7, Array(7) { IntDomain(0, 4) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3, 4), 0, 5), Product(5, 6, 0)),
        )
        val state = freshState(problem, 7L)
        repeat(5) { state.assignment.setInt(it, it.toLong()) }
        state.assignment.setInt(5, 0)
        state.assignment.setInt(6, 1)
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars))
            .network(problem.numIntVars, problem.numBoolVars)
        state.recompute()
        val sink = MoveSink()
        sink.setInvariants(state.invariants)
        val moves = mutableListOf<Move>()

        repeat(3) {
            sink.clear()
            SatisfiedStructured.structural(1).generate(state, sink)
            moves.addAll(sink.list)
        }

        assertEquals(6, moves.size)
        for (move in moves) {
            val parts = (move as Move.Compound).parts.map { it as Move.IntSet }
            assertTrue(parts.none { it.varId == 0 })
            assertEquals(0L, state.netDelta(move))
        }
    }

    @Test
    fun `a restart retaining its assignment should begin fresh board coverage`() {
        val initial = state()
        var completed = false
        val fresh = mutableListOf<Move>()
        val source = SatisfiedStructured.structural(1)
        val policy = object : RestartPolicy {
            override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = false
            override fun restart(state: LocalSearchState, bestSoFar: Sample?) = Unit
            override fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) {
                completed = true
            }
        }
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(source)),
            acceptance = AcceptanceRule.GreedyDescent,
            feasibleAcceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
            feasibleResampleCap = 32,
            perturbation = { state ->
                if (completed) {
                    val sink = MoveSink()
                    source.generate(state, sink)
                    fresh.addAll(sink.list)
                }
                null
            },
        )
        val engine = LocalSearchEngine(initial.model, strategy = strategy, restartPolicy = policy)

        engine.resumable(
            LinearObjective(),
            LocalSearchParams(maxFlips = 34L, randomSeed = 7L, initialAssignment = initial.assignment.snapshot()),
        ).use { handle -> handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }

        assertEquals(4, fresh.size)
    }

    @Test
    fun `implicit ownership reseeding should discard retained board progress`() {
        val state = state()
        val sink = MoveSink()
        val source = SatisfiedStructured.structural(1)
        source.generate(state, sink)
        sink.clear()
        source.generate(state, sink)
        state.seedImplicitFeasible()
        sink.clear()
        sink.setOwners(state.seeding.ownerInt)

        val pairs = mutableSetOf<Set<Int>>()
        repeat(32) {
            sink.clear()
            source.generate(state, sink)
            for (move in sink.list) {
                pairs.add((move as Move.Compound).parts.map { (it as Move.IntSet).varId }.toSet())
            }
        }

        assertEquals(20, pairs.size)
    }

    @Test
    fun `a frozen board should never consume searched board pair progress`() {
        val state = state()
        state.assumptions = Assumptions(ints = (0 until 5).associateWith { it.toLong() })
        val sink = MoveSink(state.assumptions)

        val pairs = mutableSetOf<Set<Int>>()
        repeat(32) {
            sink.clear()
            SatisfiedStructured.structural(1).generate(state, sink)
            for (move in sink.list) {
                val ids = (move as Move.Compound).parts.map { (it as Move.IntSet).varId }.toSet()
                assertTrue(ids.all { it >= 5 })
                assertTrue(pairs.add(ids))
            }
        }

        assertEquals(10, pairs.size)
    }

    @Test
    fun `definition scoring probes should retain unchanged board coverage`() {
        val problem = Problem(
            0, 11, Array(11) { IntDomain(0, if (it == 10) 16L else 4L) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1, 2, 3, 4), 0, 5),
                AllDifferent(intArrayOf(5, 6, 7, 8, 9), 0, 5),
                Product(0, 1, 10),
            ),
        )
        val state = freshState(problem, 7L)
        repeat(10) { state.assignment.setInt(it, (it % 5).toLong()) }
        state.assignment.setInt(10, 0)
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars))
            .network(problem.numIntVars, problem.numBoolVars)
        state.shaping.objective = LinearObjective(intCoefficients = LongArray(11) { if (it == 10) 1L else 0L })
        state.recompute()
        val before = state.assignment.snapshot()
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(SatisfiedStructured.structural(1))),
            acceptance = AcceptanceRule.GreedyDescent,
            feasibleAcceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
        )

        state.boardCoverage.request(state)
        repeat(32) { assertNull(strategy.pickMove(state)) }

        assertEquals(before, state.assignment.snapshot())
        assertEquals(0L, state.cost)
        assertFalse(state.boardCoverage.pending)
    }

    @Test
    fun `incomplete coverage should preserve the existing restart boundary`() {
        val initial = state()
        val events = mutableListOf<String>()
        val policy = object : RestartPolicy {
            override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = false
            override fun restart(state: LocalSearchState, bestSoFar: Sample?) = Unit
            override fun onFeasibleStall(state: LocalSearchState, sample: Sample, objective: Double) {
                events.add("stall")
            }
            override fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) {
                events.add("optimum")
            }
        }
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(SatisfiedStructured.structural(1))),
            acceptance = AcceptanceRule.GreedyDescent,
            feasibleAcceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
            feasibleResampleCap = 2,
        )
        val engine = LocalSearchEngine(
            initial.model, strategy = strategy, restartPolicy = policy, seedImplicitOnRestart = true,
        )

        engine.resumable(
            LinearObjective(),
            LocalSearchParams(maxFlips = 6L, randomSeed = 7L, initialAssignment = initial.assignment.snapshot()),
        ).use { handle -> handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }

        assertEquals(listOf("stall", "stall"), events)
    }

    @Test
    fun `a sampled miss should enable coverage within the same resample window`() {
        val initial = state()
        val requested = mutableListOf<Boolean>()
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(SatisfiedStructured.structural(1))),
            acceptance = AcceptanceRule.GreedyDescent,
            feasibleAcceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
            feasibleResampleCap = 2,
            perturbation = { state ->
                requested.add(state.boardCoverage.requested)
                null
            },
        )

        LocalSearchEngine(initial.model, strategy = strategy).resumable(
            LinearObjective(),
            LocalSearchParams(maxFlips = 2L, randomSeed = 7L, initialAssignment = initial.assignment.snapshot()),
        ).use { handle -> handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }

        assertEquals(listOf(false, true), requested)
    }

    @Test
    fun `annealing should preserve sampled structured generation`() {
        val initial = state()
        val requested = mutableListOf<Boolean>()
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(SatisfiedStructured.structural(1))),
            acceptance = AcceptanceRule.Metropolis,
            feasibleDescent = FeasibleDescent.SelfOwned,
            perturbation = { state ->
                requested.add(state.boardCoverage.requested)
                null
            },
        )

        LocalSearchEngine(initial.model, strategy = strategy).resumable(
            LinearObjective(),
            LocalSearchParams(maxFlips = 2L, randomSeed = 7L, initialAssignment = initial.assignment.snapshot()),
        ).use { handle -> handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }

        assertEquals(listOf(false, false), requested)
    }

    @Test
    fun `disabled retained generation should preserve sampled candidates and random draws`() {
        val sampled = state()
        val deferred = state()
        val sampledSink = MoveSink()
        val deferredSink = MoveSink()

        repeat(3) {
            sampledSink.clear()
            deferredSink.clear()
            SatisfiedStructured.sampled(1).generate(sampled, sampledSink)
            SatisfiedStructured.structural(1).generate(
                deferred, deferredSink, Cancellation.Never, retainCoverage = false,
            )

            assertEquals(sampledSink.list, deferredSink.list)
            assertEquals(sampled.rng.nextLong(), deferred.rng.nextLong())
        }
    }

    @Test
    fun `retained draws should preserve the one board proposal scope`() {
        val problem = Problem(
            0, 15, Array(15) { IntDomain(0, 4) },
            Array<Factor>(3) { board -> AllDifferent(IntArray(5) { board * 5 + it }, 0, 5) },
        )
        val state = freshState(problem, 7L)
        repeat(15) { state.assignment.setInt(it, (it % 5).toLong()) }
        state.recompute()
        val sink = MoveSink()

        val boards = mutableSetOf<Int>()
        repeat(32) {
            sink.clear()
            SatisfiedStructured.structural(1).generate(state, sink)
            assertTrue(sink.list.size <= 4)
            val batchBoards = sink.list.map { move ->
                ((move as Move.Compound).parts.first() as Move.IntSet).varId / 5
            }.toSet()
            assertTrue(batchBoards.size <= 1)
            boards.addAll(batchBoards)
        }
        assertEquals(setOf(0, 1, 2), boards)
    }

    @Test
    fun `retained board coverage should leave other elected globals eligible`() {
        val problem = Problem(
            0, 6, Array(6) { if (it < 2) IntDomain(0, 1) else IntDomain(0, 3) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), 0, 2), Circuit(intArrayOf(2, 3, 4, 5))),
        )
        val state = freshState(problem, 7L)
        state.seedImplicitFeasible()
        state.recompute()
        val sink = MoveSink()
        sink.setOwners(state.seeding.ownerInt)
        val moves = mutableListOf<Move>()

        repeat(32) {
            sink.clear()
            SatisfiedStructured.structural(1).generate(state, sink)
            moves.addAll(sink.list)
        }

        assertTrue(moves.any { move ->
            (move as Move.Compound).parts.any { (it as Move.IntSet).varId >= 2 }
        })
    }

    @Test
    fun `cyclic pair coverage should include every unordered pair once`() {
        for (size in listOf(2, 3, 4, 5, 7, 8)) {
            val problem = Problem(
                0, size, Array(size) { IntDomain(0, (size - 1).toLong()) },
                arrayOf<Factor>(AllDifferent(IntArray(size) { it }, 0, size)),
            )
            val state = freshState(problem, 7L)
            repeat(size) { state.assignment.setInt(it, it.toLong()) }
            state.recompute()
            val sink = MoveSink()
            val pairs = mutableSetOf<Set<Int>>()
            val count = size * (size - 1) / 2

            repeat((count + 3) / 4) {
                sink.clear()
                SatisfiedStructured.structural(1).generate(state, sink)
                assertTrue(sink.list.size <= 4)
                for (move in sink.list) {
                    val ids = (move as Move.Compound).parts.map { (it as Move.IntSet).varId }.toSet()
                    assertTrue(pairs.add(ids))
                }
            }

            assertEquals(count, pairs.size)
            assertFalse(state.boardCoverage.pending)
        }
    }

    @Test
    fun `configuration filtered coverage should not report a board optimum`() {
        val problem = Problem(
            0, 3, Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), 0, 3)),
        )
        val initial = freshState(problem, 7L)
        repeat(3) { initial.assignment.setInt(it, it.toLong()) }
        initial.recompute()
        var optima = 0
        val policy = object : RestartPolicy {
            override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = false
            override fun restart(state: LocalSearchState, bestSoFar: Sample?) = Unit
            override fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) {
                optima++
            }
        }
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(SatisfiedStructured.structural(1))),
            acceptance = AcceptanceRule.GreedyDescent,
            feasibleAcceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
            configurationChecking = true,
            perturbation = { state ->
                state.intConfChange[2] = false
                null
            },
        )
        val engine = LocalSearchEngine(initial.model, strategy = strategy, restartPolicy = policy)

        engine.resumable(
            LinearObjective(intCoefficients = longArrayOf(0, 0, 1)),
            LocalSearchParams(maxFlips = 1L, randomSeed = 7L, initialAssignment = initial.assignment.snapshot()),
        ).use { handle -> handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }

        assertEquals(0, optima)
    }

    @Test
    fun `an unchanged move should retain board coverage progress`() {
        val state = state()
        val source = SatisfiedStructured.structural(1)
        val sink = MoveSink()
        source.generate(state, sink)
        val pairs = sink.list.mapTo(mutableSetOf()) { move ->
            (move as Move.Compound).parts.map { (it as Move.IntSet).varId }.toSet()
        }
        state.apply(Move.IntSet(0, state.assignment.intValue(0)))

        repeat(32) {
            sink.clear()
            source.generate(state, sink)
            for (move in sink.list) {
                val ids = (move as Move.Compound).parts.map { (it as Move.IntSet).varId }.toSet()
                assertTrue(pairs.add(ids))
            }
        }
        sink.clear()
        source.generate(state, sink)

        assertTrue(sink.list.isEmpty())
        assertEquals(20, pairs.size)
    }

    private fun state(size: Int = 5): LocalSearchState {
        val problem = Problem(
            0, size * 2, Array(size * 2) { IntDomain(0, (size - 1).toLong()) },
            Array<Factor>(2) { board -> AllDifferent(IntArray(size) { board * size + it }, 0, size) },
        )
        return freshState(problem, 7L).also { state ->
            repeat(size * 2) { state.assignment.setInt(it, (it % size).toLong()) }
            state.recompute()
        }
    }
}
