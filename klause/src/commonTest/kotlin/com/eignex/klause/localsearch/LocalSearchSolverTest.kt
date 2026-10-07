package com.eignex.klause.localsearch
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.allDifferent
import com.eignex.klause.solver.*
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalSearchSolverTest {

    @Test
    fun `searches a linear model whose int values exceed the 32-bit range`() {
        val wide = 1L shl 62
        val target = 1L shl 40
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(-wide, wide)),
            arrayOf<Factor>(Linear(longArrayOf(3), intArrayOf(0), LinearOp.EQ, 3 * target)),
        )
        val result = LocalSearchSolver(problem.bake()).solve(LocalSearchParams(maxFlips = 1_000, randomSeed = 1))
        assertEquals(target, assertIs<SolveResult.Sat>(result).assignment.ints[0])
    }

    @Test
    fun `minimize keeps a wide-coefficient row exact`() {
        // 2^65·x ≤ 2^65·3 + 1 caps x at 3; minimizing -x drives x to that cap and no further.
        val wideCoefficient = bigIntOf(Long.MAX_VALUE) * bigIntOf(4)
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 10)),
            arrayOf(
                Linear(
                    intArrayOf(0),
                    arrayOf(wideCoefficient),
                    LinearOp.LE,
                    wideCoefficient * bigIntOf(3) + BIG_ONE,
                ),
            ),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1L))

        val result = LocalSearchSolver(problem.bake())
            .minimize(objective, LocalSearchParams(maxFlips = 2_000, randomSeed = 1))

        assertEquals(3L, assertIs<MinimizeResult.BestFound>(result).sample.ints[0])
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
    fun `declines a non-linear factor over a domain past the 32-bit range`() {
        val wide = 1L shl 62
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(-wide, wide), IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(Product(0, 1, 2)),
        )
        val result = LocalSearchSolver(problem.bake()).solve(LocalSearchParams(maxFlips = 100, randomSeed = 1))
        assertEquals(TerminationReason.Unsupported, assertIs<SolveResult.Unknown>(result).reason)
    }

    @Test
    fun `without per-move invariants every incumbent is scored by the linear objective`() {
        // p = x0·x1 is the definition the gradient view reads, but the model only bounds p, so p is free to
        // sit off it unless invariants keep it on the definition.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(0, 20)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(2), LinearOp.GE, 1)),
        )
        val sweep = assertNotNull(DefinitionalSweep.infer(arrayOf(Product(a = 0, b = 1, result = 2)), numIntVars = 3))
        val gradient = sweep.functionalObjective(intArrayOf(2), longArrayOf(1L), constant = 0L, minimize = true)
        val objective = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 1L))

        val incumbents = LocalSearchSolver(problem.bake())
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

    @Test
    fun `solves simple 3 sat instance`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        val problem = Problem(3, 0, emptyArray(), clauses)
        val solver = LocalSearchSolver(problem.bake())
        val sample = solver.enumerate(LocalSearchParams(maxFlips = 10_000, randomSeed = 7)).first()
        for (clause in clauses) {
            val sat = clause.literals.any { lit ->
                Lit.evaluate(lit, sample.bools[Lit.variable(lit)])
            }
            assertTrue(sat, "Clause ${clause.literals.toList()} unsatisfied by ${sample.bools.toList()}")
        }
    }

    @Test
    fun `samples cover all solutions on tiny problem`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
        )
        val problem = Problem(2, 0, emptyArray(), clauses)
        val solver = LocalSearchSolver(problem.bake(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 10))
        val samples = solver.samples(LocalSearchParams(maxFlips = 5_000, randomSeed = 1)).take(20).toList()
        assertEquals(2, samples.toSet().size, "Both distinct solutions should be sampled")
        for (s in samples) assertTrue(s.bools[0])
    }

    @Test
    fun `exactly one factor yields all three solutions`() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val solver = LocalSearchSolver(problem.bake())
        val samples = solver.samples(LocalSearchParams(maxFlips = 5_000, randomSeed = 13)).take(30).toList()
        assertEquals(3, samples.toSet().size, "ExactlyOne over 3 vars has exactly 3 distinct solutions")
        for (s in samples) assertEquals(1, s.bools.count { it })
    }

    private class ThreeDistinct : VariableSchema() {
        val a by intVar(min = 1, max = 3)
        val b by intVar(min = 1, max = 3)
        val c by intVar(min = 1, max = 3)
        val unique by constraint { allDifferent(a, b, c) }
    }

    @Test
    fun `constructor accepts a compiled problem`() {
        val compiled = ThreeDistinct().compile()
        val result = LocalSearchSolver(compiled).solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 0))
        assertTrue(result is SolveResult.Sat)
    }

    @Test
    fun `constructor accepts a schema and compiles it`() {
        val result = LocalSearchSolver(ThreeDistinct()).solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 0))
        assertTrue(result is SolveResult.Sat)
    }
}
