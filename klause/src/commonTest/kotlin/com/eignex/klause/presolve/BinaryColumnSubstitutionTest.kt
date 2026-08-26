package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.values
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `{0, 1}`-column-for-literal substitution: which columns leave the integer lane, which rows it lowers
 * to which pseudo-Boolean factor, and — the contract that makes the transform usable at all — that a
 * solution of the substituted problem reconstructs to exactly one solution of the original, with the
 * integer values the literals carry.
 */
class BinaryColumnSubstitutionTest {

    private fun binary(n: Int) = Array<IntDomain>(n) { IntDomain(0, 1) }

    private fun problem(numIntVars: Int, factors: List<Factor>, domains: Array<IntDomain> = binary(numIntVars)) =
        Problem(numBoolVars = 0, numIntVars = numIntVars, intDomains = domains, factors = factors)

    private fun substitute(problem: Problem, objectiveIntVars: Set<Int> = emptySet()) =
        BinaryColumnSubstitution.substitute(problem, objectiveIntVars, BakeConfig.NONE)

    /** Whether the total assignment ([bools], [ints]) satisfies every factor of [problem]. */
    private fun satisfies(problem: Problem, bools: BooleanArray, ints: LongArray): Boolean {
        var a = Assumptions.None
        for (b in 0 until problem.numBoolVars) a = a.withBool(b, bools[b])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    /** Every `{0, 1}` tuple over [problem]'s integer columns that satisfies it. */
    private fun intSolutions(problem: Problem): Set<List<Long>> {
        val n = problem.numIntVars
        val out = HashSet<List<Long>>()
        for (mask in 0 until (1 shl n)) {
            val ints = LongArray(n) { ((mask shr it) and 1).toLong() }
            if (satisfies(problem, BooleanArray(0), ints)) out.add(ints.toList())
        }
        return out
    }

    @Test
    fun `an at-least-one row over binary columns becomes a clause`() {
        val model = problem(3, listOf(Linear(longArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 1)))

        val result = substitute(model)

        assertEquals(3, result?.columns)
        assertEquals(1, result?.problem?.factors?.count { it is Clause })
    }

    @Test
    fun `an at-most-one row over binary columns becomes an at-most-one cardinality over the same polarity`() {
        val model = problem(3, listOf(Linear(longArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 1)))

        val result = substitute(model)

        val card = result?.problem?.factors?.filterIsInstance<Cardinality>()?.single()
        assertEquals(0, card?.min)
        assertEquals(1, card?.max)
        assertEquals(listOf(0, 1, 2), card?.literals?.map { Lit.variable(it) })
        assertTrue(card?.literals?.all { Lit.isPositive(it) } == true)
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
    fun `a column a row shares with a general integer column stays in the integer lane`() {
        val model = problem(
            2,
            listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
            domains = arrayOf(IntDomain(0, 1), IntDomain(0, 5)),
        )

        assertNull(substitute(model))
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
    fun `reconstruct maps the substituted problem's solutions one-to-one onto the original's`() {
        val model = problem(
            3,
            listOf(
                Linear(longArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 1),
                Linear(longArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 1),
            ),
        )
        val result = requireNotNull(substitute(model))

        val lifted = ArrayList<List<Long>>()
        val numBools = result.problem.numBoolVars
        for (mask in 0 until (1 shl numBools)) {
            val bools = BooleanArray(numBools) { ((mask shr it) and 1) == 1 }
            val ints = LongArray(result.problem.numIntVars) { result.problem.requireFiniteIntDomains()[it].min }
            if (!satisfies(result.problem, bools, ints)) continue
            val recon = result.reconstruct(Sample(bools, ints))
            assertTrue(
                satisfies(model, BooleanArray(0), recon.ints),
                "reconstructed ${recon.ints.toList()} is not a solution",
            )
            lifted.add(recon.ints.toList())
        }

        assertEquals(intSolutions(model), lifted.toSet())
        assertEquals(intSolutions(model).size, lifted.size, "the lift must not collapse or duplicate solutions")
    }

    @Test
    fun `the substituted column is pinned so search never branches on it`() {
        val model = problem(2, listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))

        val result = requireNotNull(substitute(model))

        assertTrue(result.problem.requireFiniteIntDomains().all { it.values.size == 1 })
    }
}
