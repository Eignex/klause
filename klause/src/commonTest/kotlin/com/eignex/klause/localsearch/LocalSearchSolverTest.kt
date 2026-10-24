package com.eignex.klause.localsearch
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.*
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSearchSolverTest {

    @Test
    fun `slice boundaries should preserve the seeded satisfaction walk`() {
        val problem = Problem(
            4,
            0,
            emptyArray(),
            Array<Factor>(2) { i -> Clause(intArrayOf(Lit.make(2 * i, true), Lit.make(2 * i + 1, true))) },
        ).bake()
        val params = LocalSearchParams(
            maxFlips = 20L,
            randomSeed = 3L,
            initialAssignment = Sample(BooleanArray(4), LongArray(0)),
        )
        val solver = LocalSearchSolver(problem, greedyRepairOnRestart = false)
        val expected = assertIs<SolveResult.Sat>(solver.solve(params))

        for (slice in longArrayOf(1L, 3L, 9L)) {
            val actual = solver.resumableSolve(params).use { handle ->
                var result: SolveResult? = null
                repeat(20) { if (result == null) result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, slice) }
                assertIs<SolveResult.Sat>(result)
            }

            assertEquals(expected.assignment, actual.assignment)
            assertEquals(expected.stats.ls.moves, actual.stats.ls.moves)
        }
    }

    @Test
    fun `cancellation should pause the walk without exhausting it`() {
        val problem = Problem(
            80,
            0,
            emptyArray(),
            Array<Factor>(40) { i -> Clause(intArrayOf(Lit.make(2 * i, true), Lit.make(2 * i + 1, true))) },
        ).bake()
        val params = LocalSearchParams(
            maxFlips = 200L,
            randomSeed = 3L,
            initialAssignment = Sample(BooleanArray(80), LongArray(0)),
        )
        val solver = LocalSearchSolver(problem, greedyRepairOnRestart = false)
        val expected = assertIs<SolveResult.Sat>(solver.solve(params))
        solver.resumableSolve(params.copy(cancellation = Cancellation { true })).use { handle ->
            assertNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
            val paused = handle.runSlice(Cancellation { true }, Long.MAX_VALUE, -1L)
            assertNull(paused)
            assertTrue(handle.stats.ls.moves.sum > 0.0)
            assertTrue(!handle.isDone)

            val actual = assertIs<SolveResult.Sat>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))

            assertEquals(expected.assignment, actual.assignment)
            assertEquals(expected.stats.ls.moves, actual.stats.ls.moves)
        }
    }

    @Test
    fun `cancellation during a slice should preserve the remaining work allowance`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            Array<Factor>(8) { mask -> Clause(IntArray(3) { v -> Lit.make(v, mask and (1 shl v) != 0) }) },
        ).bake()
        val params = LocalSearchParams(maxFlips = 2_000L, randomSeed = 3L)
        val solver = LocalSearchSolver(
            problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 7),
            greedyRepairOnRestart = false,
        )
        val expected = assertIs<SolveResult.Unknown>(solver.solve(params))
        solver.resumableSolve(params).use { handle ->
            assertNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
            val before = handle.stats.ls.moves.sum
            var polls = 0
            val paused = handle.runSlice(Cancellation { ++polls >= 2 }, Long.MAX_VALUE, -1L)
            assertNull(paused)
            assertTrue(handle.stats.ls.moves.sum >= before)
            assertTrue(handle.stats.ls.moves.sum < params.maxFlips)

            val actual = assertIs<SolveResult.Unknown>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))

            assertEquals(expected.stats.ls.moves, actual.stats.ls.moves)
            assertEquals(expected.stats.search.restarts, actual.stats.search.restarts)
            assertEquals(expected.stats.ls.incumbentViolation, actual.stats.ls.incumbentViolation)
        }
    }

    @Test
    fun `an exhausted resumed walk should keep its terminal verdict`() {
        val problem = Problem(
            4,
            0,
            emptyArray(),
            Array<Factor>(2) { i -> Clause(intArrayOf(Lit.make(2 * i, true), Lit.make(2 * i + 1, true))) },
        ).bake()
        val params = LocalSearchParams(
            maxFlips = 1L,
            randomSeed = 3L,
            initialAssignment = Sample(BooleanArray(4), LongArray(0)),
        )

        LocalSearchSolver(problem, greedyRepairOnRestart = false).resumableSolve(params).use { handle ->
            assertNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 0L))
            val result = assertIs<SolveResult.Unknown>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))

            assertEquals(TerminationReason.BudgetExhausted, result.reason)
            assertEquals(1.0, result.stats.ls.moves.sum)
            assertTrue(handle.isDone)
            assertEquals(result, handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 100L))
        }
    }

    @Test
    fun `minimize should report an objective past the 64-bit range without wrapping`() {
        val wide = 1L shl 62
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(-wide, wide)),
            arrayOf<Factor>(Linear(longArrayOf(1), intArrayOf(0), LinearOp.EQ, 1L shl 61)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(8L))
        val params = LocalSearchParams(maxFlips = 1_000, randomSeed = 1)

        val result = LocalSearchSolver(problem.bake()).minimize(objective, params)

        assertEquals(1.8446744073709552e19, assertIs<MinimizeResult.BestFound>(result).objective)
    }

    @Test
    fun `a gradient view that disagrees with the linear objective should only guide moves`() {
        // The view reads p = x0·x1 alone; the linear objective p + x0 is the rewrite presolve left behind.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(0, 20)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(2), LinearOp.GE, 1)),
        )
        val sweep = assertNotNull(DefinitionalSweep.infer(arrayOf(Product(a = 0, b = 1, result = 2)), numIntVars = 3))
        val gradient = sweep.functionalObjective(intArrayOf(2), longArrayOf(1L), constant = 0L, minimize = true)
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 0L, 1L))

        val incumbents = LocalSearchSolver(problem.bake(), definitionalSweep = sweep, perMoveInvariants = true)
            .improvements(objective, LocalSearchParams(maxFlips = 2_000, randomSeed = 3, lsObjective = gradient))
            .filterIsInstance<MinimizeResult.WithSample>()
            .toList()

        assertTrue(incumbents.isNotEmpty())
        for (r in incumbents) assertEquals(objective.evaluate(r.sample), r.objectiveValue, "sample ${r.sample}")
    }

    @Test
    fun `a satisfy run should start from a supplied assignment`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 20), IntDomain(0, 20)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 7),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
            ),
        )
        val start = Sample(BooleanArray(0), longArrayOf(4, 3))

        val result = LocalSearchSolver(problem.bake())
            .solve(LocalSearchParams(maxFlips = 1, randomSeed = 1, initialAssignment = start))

        assertEquals(listOf(4L, 3L), assertIs<SolveResult.Sat>(result).assignment.ints.toList())
    }

    // Choose ≥ 4 of 12 booleans to minimise their weights: the optimum picks the four cheapest.
    private fun problem() = Problem(
        numBoolVars = 12,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(Cardinality(IntArray(12) { Lit.make(it, true) }, min = 4, max = 12)),
    )

    private val weights = longArrayOf(7L, 3L, 5L, 9L, 1L, 8L, 2L, 6L, 12L, 4L, 11L, 10L)
    private val optimum = 10.0 // 1 (var4) + 2 (var6) + 3 (var1) + 4 (var9)

    // The four dearest weights are at indices 8, 10, 11, 3; pinning them true forces a worst-case
    // feasible selection, which the pooled optimum (those four false) cannot satisfy.
    private val dearest = listOf(8, 10, 11, 3)
    private val pinnedOptimum = 42.0 // 12 + 11 + 10 + 9

    private fun optimalSample(): Sample {
        // The four cheapest weights are at indices 4, 6, 1, 9.
        val cheapest = weights.indices.sortedBy { weights[it] }.take(4).toSet()
        return Sample(BooleanArray(12) { it in cheapest }, LongArray(0))
    }

    private fun objective() = LinearObjective(boolWeights = weights)

    private fun exchangeHolding(sample: Sample, objective: LinearObjective) =
        IncumbentExchange.minimizing<Sample>().apply { offer(sample, objective.evaluate(sample)) }

    @Test
    fun `each improving incumbent should be offered to the shared exchange`() {
        val objective = objective()
        val exchange = IncumbentExchange.minimizing<Sample>()
        val result = LocalSearchSolver(problem().bake()).minimize(
            objective,
            LocalSearchParams(maxFlips = 300L, randomSeed = 1L, pooledIncumbents = exchange),
        )
        val sample = assertNotNull(result.assignment)
        val standing = assertNotNull(exchange.current())
        assertEquals(objective.evaluate(sample), standing.objective)
    }

    @Test
    fun `a pooled optimum should be adopted on restart and reported as the result`() {
        val objective = objective()
        val result = LocalSearchSolver(problem().bake()).minimize(
            objective,
            LocalSearchParams(
                maxFlips = 300L,
                randomSeed = 1L,
                pooledIncumbents = exchangeHolding(optimalSample(), objective),
            ),
        )
        val sample = assertNotNull(result.assignment)
        assertEquals(optimum, objective.evaluate(sample))
    }

    @Test
    fun `a pooled assignment that violates the run's pins should not be adopted`() {
        val objective = objective()
        val result = LocalSearchSolver(problem().bake()).minimize(
            objective,
            LocalSearchParams(
                maxFlips = 300L,
                randomSeed = 1L,
                assumptions = Assumptions(bools = dearest.associateWith { true }),
                pooledIncumbents = exchangeHolding(optimalSample(), objective),
            ),
        )
        val sample = assertNotNull(result.assignment)
        assertTrue(dearest.all { sample.bools[it] })
        assertTrue(
            objective.evaluate(sample) >= pinnedOptimum,
            "a pin-violating foreign assignment is never imported under assumptions",
        )
    }

    @Test
    fun `minimize should respect a bool assumption`() {
        val problem = Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val solver = LocalSearchSolver(problem.bake())
        val obj = LinearObjective(boolWeights = longArrayOf(-1L, -1L, -1L, -1L))
        val sample = solver.minimize(
            obj,
            LocalSearchParams(
                randomSeed = 3L,
                maxFlips = 2_000L,
                assumptions = Assumptions(bools = mapOf(2 to false)),
            ),
        ).assignment
        assertNotNull(sample)
        assertEquals(listOf(true, true, false, true), (0 until 4).map { sample.bools[it] })
    }

    @Test
    fun `assumptions should compose with hard constraints`() {
        val clauses = listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))))
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = clauses)
        val solver = LocalSearchSolver(problem.bake())
        val sample = solver.sample(
            LocalSearchParams(
                randomSeed = 9L,
                maxFlips = 1_000,
                assumptions = Assumptions(bools = mapOf(0 to false)),
            ),
        ).assignment
        assertNotNull(sample)
        assertEquals(false, sample.bools[0])
        assertEquals(true, sample.bools[1])
    }
}
