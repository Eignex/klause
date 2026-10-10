package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSearchEngineTest {

    @Test
    fun `satisfaction resumes after projection preparation expires`() {
        var expired = false
        val factors = Array<Factor>(512) { fid ->
            val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false)))
            object : Factor by clause, Invariant {
                override val boolVars: IntArray
                    get() {
                        if (fid == 255) expired = true
                        return clause.boolVars
                    }
            }
        }
        val problem = Problem(1, 0, emptyArray(), factors + Clause(intArrayOf(Lit.make(0, true))))
        expired = false
        val search = LocalSearchEngine(LocalSearchModel.open(problem), greedyRepairOnRestart = false)
        val warm = WarmState()
        val params = LocalSearchParams(maxFlips = 2L, initialAssignment = Sample(booleanArrayOf(true), LongArray(0)))

        search.resumableSolve(params, warm).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L))
            assertEquals(Long.MAX_VALUE, warm.bestCostSeen())
            assertEquals(0.0, handle.stats.ls.moves.sum)

            val result = assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))

            assertTrue(result.assignment.bools[0])
        }
    }

    @Test
    fun `satisfaction resumes initial scoring without repeating completed factors`() {
        var expired = false
        val initialized = IntArray(512)
        val factors = Array<Factor>(initialized.size) { _ ->
            val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false)))
            object : Factor by clause, Invariant {
                override fun initialize(state: LocalSearchState, factorId: Int) {
                    initialized[factorId]++
                    if (factorId == 255) expired = true
                }
            }
        }
        val problem = Problem(1, 0, emptyArray(), factors + Clause(intArrayOf(Lit.make(0, true))))
        val search = LocalSearchEngine(LocalSearchModel.open(problem), greedyRepairOnRestart = false)
        val warm = WarmState()
        val params = LocalSearchParams(maxFlips = 2L, initialAssignment = Sample(booleanArrayOf(false), LongArray(0)))

        search.resumableSolve(params, warm).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L))
            assertEquals(256, initialized.sum())
            assertEquals(Long.MAX_VALUE, warm.bestCostSeen())
            assertEquals(0.0, handle.stats.ls.moves.sum)
            expired = false

            val result = assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))

            assertTrue(result.assignment.bools[0])
            assertTrue(initialized.all { it == 1 })
        }
    }

    @Test
    fun `optimization does not publish a partially scored assignment`() {
        var expired = false
        val initialized = IntArray(512)
        val factors = Array<Factor>(initialized.size) { _ ->
            val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false)))
            object : Factor by clause, Invariant {
                override fun initialize(state: LocalSearchState, factorId: Int) {
                    initialized[factorId]++
                    if (factorId == 255) expired = true
                }
            }
        }
        val problem = Problem(
            1, 0, emptyArray(),
            factors + arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true))), Clause(intArrayOf(Lit.make(0, false)))),
        )
        val search = LocalSearchEngine(LocalSearchModel.open(problem), greedyRepairOnRestart = false)
        val found = mutableListOf<Sample>()

        search.resumable(
            LinearObjective(boolWeights = longArrayOf(1)),
            LocalSearchParams(maxFlips = 1L),
        ).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L) { found += it.sample })
            assertEquals(256, initialized.sum())
            assertTrue(found.isEmpty())
            expired = false

            val result = assertIs<MinimizeResult.Unknown>(
                handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { found += it.sample },
            )

            assertTrue(found.isEmpty())
            assertEquals(1.0, result.stats.ls.incumbentViolation)
        }
    }

    // x + k = 2.5 and x ≤ 1 over a continuous x in [0, 10] and an integer k in [0, 5]: k ≥ 2 and x = 2.5 - k.
    private fun mixedProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 2.5),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(0.0),
        realUpper = doubleArrayOf(10.0),
    )

    private fun engine(completion: CandidateCompletion) =
        LocalSearchEngine(LocalSearchModel.of(mixedProblem().bake()), completion = completion)

    @Test
    fun `cancellation during completion preserves the candidate`() {
        val initial = Sample(BooleanArray(0), longArrayOf(2), reals = doubleArrayOf(0.5))
        val search = engine { candidate, cancellation ->
            if (cancellation()) Completion.Undecided() else Completion.Witness(candidate)
        }
        var polls = 0

        search.resumableSolve(LocalSearchParams(maxFlips = 1L, initialAssignment = initial)).use { handle ->
            val paused = handle.runSlice(Cancellation { ++polls >= 3 }, Long.MAX_VALUE, -1L)

            assertNull(paused)
            assertFalse(handle.isDone)
            assertEquals(0.0, handle.stats.ls.moves.sum)
            val result = assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))
            assertEquals(initial, result.assignment)
        }
    }

    @Test
    fun `optimization resumes a candidate whose completion was cancelled`() {
        val initial = Sample(BooleanArray(0), longArrayOf(2), reals = doubleArrayOf(0.5))
        var expired = false
        var calls = 0
        val search = engine { candidate, _ ->
            if (++calls == 1) {
                expired = true
                Completion.Undecided()
            } else {
                Completion.Witness(candidate)
            }
        }
        val found = mutableListOf<Sample>()

        search.resumable(
            LinearObjective(intCoefficients = longArrayOf(1)),
            LocalSearchParams(maxFlips = 1L, initialAssignment = initial),
        ).use { handle ->
            assertNull(handle.runSlice(Cancellation { expired }, Long.MAX_VALUE, -1L) { found += it.sample })
            assertEquals(0.0, handle.stats.ls.moves.sum)
            expired = false
            val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { found += it.sample }

            assertEquals(initial, assertIs<MinimizeResult.BestFound>(result).sample)
            assertEquals(listOf(initial), found)
        }
    }

    @Test
    fun `local search runs a nonlinear factor over wide domains`() {
        val wide = IntDomain(0, 1L shl 40)
        val maxOf = ArrayMinMax(result = 0, xs = intArrayOf(1, 2), max = true)
        val model = Problem(0, 3, Array(3) { wide }, arrayOf<Factor>(maxOf))

        assertTrue(localSearchSupports(LocalSearchModel.of(model.bake())))
    }

    private fun wideSchedule(): Problem = Problem(
        0,
        2,
        Array(2) { IntDomain(0, 1L shl 40) },
        arrayOf<Factor>(Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(1, 1), capacity = 1)),
    )

    @Test
    fun `a schedule over wide domains is searched`() {
        val result = LocalSearchSolver(wideSchedule().bake()).solve(LocalSearchParams(maxFlips = 1_000, randomSeed = 1))

        val starts = assertIs<SolveResult.Sat>(result).assignment.ints
        assertTrue(abs(starts[0] - starts[1]) >= 2, "starts=${starts.toList()}")
    }

    @Test
    fun `a schedule searched inside a window never refutes the model`() {
        assertFalse(LocalSearchModel.of(wideSchedule().bake()).refutesModel)
    }

    @Test
    fun `a model with continuous columns is declined without a completion`() {
        val result = LocalSearchSolver(mixedProblem().bake()).solve(LocalSearchParams(maxFlips = 100, randomSeed = 1))

        assertEquals(TerminationReason.Unsupported, assertIs<SolveResult.Unknown>(result).reason)
    }

    @Test
    fun `a candidate over continuous columns satisfies its rows within tolerance`() {
        val result = engine { candidate, _ ->
            Completion.Witness(
                candidate,
            )
        }.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 2), null)

        val sample = assertIs<SolveResult.Sat>(result).assignment
        val x = sample.approximateRealValue(0)
        val k = sample.ints[0]
        assertTrue(abs(x + k - 2.5) <= 1e-6 && x <= 1.0 + 1e-6, "x=$x k=$k")
    }

    @Test
    fun `search goes on past a refuted candidate`() {
        var calls = 0
        val result = engine { candidate, _ ->
            calls++
            if (calls == 1) Completion.Refuted(intArrayOf(0)) else Completion.Witness(candidate)
        }.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 2), null)

        assertIs<SolveResult.Sat>(result)
        assertEquals(2, calls)
    }

    @Test
    fun `the work a completion reports is charged to the search budget`() {
        var calls = 0
        val result = engine { _, _ ->
            calls++
            Completion.Refuted(work = 2_000L)
        }.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 2), null)

        assertIs<SolveResult.Unknown>(result)
        assertTrue(calls in 1..3, "calls=$calls")
    }

    @Test
    fun `decided candidates are counted in the stats`() {
        var calls = 0
        val result = engine { candidate, _ ->
            calls++
            if (calls == 1) Completion.Refuted() else Completion.Witness(candidate)
        }.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 2), null)

        val ls = assertIs<SolveResult.Sat>(result).stats.ls
        assertEquals(2.0, ls.completions.sum)
        assertEquals(1.0, ls.completionsRefuted.sum)
    }
}
