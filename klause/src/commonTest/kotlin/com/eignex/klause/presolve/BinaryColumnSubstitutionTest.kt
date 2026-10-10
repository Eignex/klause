package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinaryColumnSubstitutionTest {

    @Test
    fun `a declared binary row becomes a clause with a feasible reconstruction`() {
        val source = Linear(longArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 1)
        val model = problem(3, listOf(object : Factor by source, Propagator by source.propagatorProjection() {}))

        val result = checkNotNull(substitute(model))

        assertEquals(3, result.columns)
        assertEquals(1, result.problem.factors.count { it is Clause })
        val sample = result.reconstruct(Sample(booleanArrayOf(true, false, false), longArrayOf(0, 0, 0)))
        assertTrue(satisfies(model, sample.bools, sample.ints))
    }

    @Test
    fun `binary channel reconstruction preserves either indicator polarity`() {
        for (bound in listOf(0, 1)) {
            val model = Problem(
                numBoolVars = 1,
                numIntVars = 1,
                intDomains = binary(1),
                factors = listOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, bound)),
            )

            val result = checkNotNull(substitute(model))
            assertEquals(model.numBoolVars, result.problem.numBoolVars)
            val assignments = BacktrackSolver(result.problem).enumerate(BacktrackParams(randomSeed = 0L))
                .map(result.reconstruct).map { it.bools.single() to it.ints.single() }.toSet()

            assertEquals(setOf(true to bound.toLong(), false to (1L - bound)), assignments)
        }
    }

    @Test
    fun `multiple binary indicators retain their shared integer meaning`() {
        val model = Problem(
            numBoolVars = 2,
            numIntVars = 1,
            intDomains = binary(1),
            factors = listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
            ),
        )

        val result = checkNotNull(substitute(model))
        val assignments = BacktrackSolver(result.problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map(result.reconstruct).map { it.bools.toList() to it.ints.single() }.toSet()

        assertEquals(setOf(listOf(true, false) to 1L, listOf(false, true) to 0L), assignments)
    }

    @Test
    fun `signed binary rows preserve source assignments with reused and fresh literals`() {
        for (bound in listOf(0, 1)) {
            val model = Problem(
                numBoolVars = 1,
                numIntVars = 2,
                intDomains = binary(2),
                factors = listOf(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, bound),
                    Linear(longArrayOf(3, -2), intArrayOf(0, 1), LinearOp.LE, 1L),
                ),
            )
            val result = checkNotNull(substitute(model))
            val expected = buildSet {
                for (indicator in listOf(false, true)) {
                    for (x in 0L..1L) for (y in 0L..1L) {
                        if (satisfies(model, booleanArrayOf(indicator), longArrayOf(x, y))) {
                            add(listOf(indicator) to listOf(x, y))
                        }
                    }
                }
            }

            val assignments = BacktrackSolver(result.problem).enumerate(BacktrackParams(randomSeed = 0L))
                .map(result.reconstruct).map { it.bools.toList() to it.ints.toList() }.toList()

            assertEquals(expected, assignments.toSet())
            assertEquals(expected.size, assignments.size)
        }
    }

    @Test
    fun `columns sharing an indicator preserve cardinality multiplicity and source assignments`() {
        for (bound in listOf(0, 1)) {
            val model = Problem(
                numBoolVars = 1,
                numIntVars = 2,
                intDomains = binary(2),
                factors = listOf(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, bound),
                    Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1L),
                ),
            )
            val result = checkNotNull(substitute(model))

            val assignments = BacktrackSolver(result.problem).enumerate(BacktrackParams(randomSeed = 0L))
                .map(result.reconstruct).map { it.bools.toList() to it.ints.toList() }.toList()

            val expected = if (bound == 0) {
                setOf(listOf(true) to listOf(1L, 0L), listOf(false) to listOf(0L, 1L))
            } else {
                emptySet()
            }
            assertEquals(expected, assignments.toSet())
            assertEquals(expected.size, assignments.size)
        }
    }

    @Test
    fun `a binary indicator cannot replace an objective integer column`() {
        val model = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = binary(1),
            factors = listOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)),
        )

        assertNull(substitute(model, objectiveIntVars = setOf(0)))
    }

    private fun binary(n: Int) = Array<IntDomain>(n) { IntDomain(0, 1) }

    private fun problem(numIntVars: Int, factors: List<Factor>, domains: Array<IntDomain> = binary(numIntVars)) =
        Problem(numBoolVars = 0, numIntVars = numIntVars, intDomains = domains, factors = factors)

    private fun substitute(problem: Problem, objectiveIntVars: Set<Int> = emptySet()) =
        BinaryColumnSubstitution.substitute(problem.bake(), objectiveIntVars, BakeConfig.NONE)

    /** Whether the total assignment ([bools], [ints]) satisfies every factor of [problem]. */
    private fun satisfies(problem: Problem, bools: BooleanArray, ints: LongArray): Boolean {
        var a = Assumptions.None
        for (b in 0 until problem.numBoolVars) a = a.withBool(b, bools[b])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    @Test
    fun `a weighted row over binary columns becomes a pseudo-boolean with positive weights`() {
        val model = problem(2, listOf(Linear(longArrayOf(3, -2), intArrayOf(0, 1), LinearOp.LE, 1)))

        val result = substitute(model)

        // −3x₀ + 2x₁ ≥ −1 normalises to 3·¬x₀ + 2x₁ ≥ 2.
        val pb = result?.problem?.factors?.filterIsInstance<PseudoBoolean>()?.single()
        assertEquals(listOf(3L, 2L), pb?.weights?.toList())
        assertEquals(2L, pb?.bound)
    }

    @Test
    fun `a column the objective reads is not substituted`() {
        val model = problem(2, listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))

        assertNull(substitute(model, objectiveIntVars = setOf(1)))
    }

    @Test
    fun `a column read by a factor that is not a linear row stays in the integer lane`() {
        val model = problem(
            2,
            listOf(
                Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Product(a = 0, b = 1, result = 1),
            ),
        )

        assertNull(substitute(model))
    }

    @Test
    fun `composed preparation preserves source objective after Boolean zero extension`() {
        val model = problem(
            5,
            listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
            ),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 0, 2), constant = 11)
        val first = PresolvePipeline.run(model, objective, PresolveConfig.parse("binary-columns"), false)
        val adjusted = checkNotNull(first.objective)
        val second = PresolvePipeline.run(first.problem, adjusted, PresolveConfig.NONE, false)
        val chain = first.mapping.then(second.mapping)

        val values = BacktrackSolver(second.problem.bake()).enumerate().map { sample ->
            chain.requireObjectivePreserved(objective, adjusted, sample)
            objective.evaluateLong(chain.reconstructFrom(second.problem, sample))
        }.toSet()

        assertEquals(setOf(11L, 13L), values)
        assertEquals(first.problem.numBoolVars, adjusted.boolWeights.size)
        assertTrue(adjusted.boolWeights.all { it == 0L })
    }
}
