package com.eignex.klause.localsearch
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.*
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
    fun `slice boundaries preserve the seeded satisfaction walk`() {
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
    fun `cancellation pauses the walk without exhausting it`() {
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
    fun `cancellation during a slice preserves the remaining work allowance`() {
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
        var polls = 0

        solver.resumableSolve(params).use { handle ->
            val paused = handle.runSlice(Cancellation { ++polls >= 3 }, Long.MAX_VALUE, -1L)
            assertNull(paused)
            assertTrue(handle.stats.ls.moves.sum > 0.0)

            val actual = assertIs<SolveResult.Unknown>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))

            assertEquals(expected.stats.ls.moves, actual.stats.ls.moves)
            assertEquals(expected.stats.search.restarts, actual.stats.search.restarts)
            assertEquals(expected.stats.ls.incumbentViolation, actual.stats.ls.incumbentViolation)
        }
    }

    @Test
    fun `an exhausted resumed walk keeps its terminal verdict`() {
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
    fun `minimize reports an objective past the 64-bit range without wrapping`() {
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
    fun `a gradient view that disagrees with the linear objective only guides moves`() {
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
    fun `a satisfy run starts from a supplied assignment`() {
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
}
