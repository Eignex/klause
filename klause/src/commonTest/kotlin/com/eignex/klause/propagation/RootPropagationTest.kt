package com.eignex.klause.propagation
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class RootPropagationTest {

    @Test
    fun `unchanged sparse sets should not repeat input assumptions`() {
        for (values in listOf(longArrayOf(0, 100_000), longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE))) {
            val problem = Problem(0, 1, arrayOf(intDomainFromSurvivors(values)), emptyArray())
            val assumptions = implied(problem.propagate()).toAssumptions()

            val result = implied(problem.propagate(assumptions))

            assertEquals(emptyList(), result.intSetKeys.toList())
        }
    }

    @Test
    fun `sparse signed word domains should capture survivors without walking gaps`() {
        val cases = listOf(
            longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE),
            longArrayOf(Long.MIN_VALUE, -1),
            longArrayOf(-1, Long.MAX_VALUE),
            longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE),
        )
        for (values in cases) {
            val problem = Problem(0, 1, arrayOf(intDomainFromSurvivors(values)), emptyArray())

            val result = implied(problem.propagate())

            assertEquals(listOf(0), result.intSetKeys.toList())
            assertEquals(values.toList(), result.intSetValues.toList())
            assertEquals(0, result.intHoleValues.size)
        }
    }

    @Test
    fun `captured sparse roots should reconstruct surviving values`() {
        val values = longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)
        val problem = Problem(0, 1, arrayOf(intDomainFromSurvivors(values)), emptyArray())
        val captured = implied(problem.propagate(Assumptions.None.withIntHole(0, 0)))

        val rebuilt = BakedProblem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
            factors = emptyList(),
            seedDeductions = captured,
        )

        val domain = rebuilt.rootIntDomain(0).values
        assertEquals(listOf(Long.MIN_VALUE, Long.MAX_VALUE), (0 until domain.size).map { domain.valueAt(it) })
    }

    @Test
    fun `sparse root pins should retract to the captured domain`() {
        val values = longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)
        val problem = Problem(0, 1, arrayOf(intDomainFromSurvivors(values)), emptyArray()).bake()
        val session = PropagationSession(problem)

        session.pinInt(0, Long.MAX_VALUE)
        session.popLast()

        assertEquals(problem.rootIntDomain(0), session.intDomain(0))
    }

    private fun boolProblem(numBoolVars: Int, vararg clauses: IntArray): Problem = Problem(
        numBoolVars = numBoolVars,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = clauses.map { Clause(it) },
    )

    private fun lit(v: Int, pos: Boolean) = Lit.make(v, pos)

    private fun implied(r: PropagationResult): PropagationResult.Implied =
        r as? PropagationResult.Implied ?: fail("expected Implied, got $r")

    @Test
    fun `a model stating bounds alone should propagate over the range they close`() {
        val p = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(10, 10), null, null),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3L),
            ),
        )

        val r = implied(p.propagate())

        assertEquals(3L, r.intMaxOrNullCompat(0))
        assertEquals(3L, r.intMaxOrNullCompat(1))
    }

    @Test
    fun `a clause conflict should report the factors that forced its false literals`() {
        val p = boolProblem(
            3,
            intArrayOf(lit(0, true)),
            intArrayOf(lit(0, false), lit(1, true)),
            intArrayOf(lit(1, false)),
            intArrayOf(lit(2, true)),
        )

        val result = assertIs<PropagationResult.Unsat>(p.propagate())

        assertEquals(setOf(0, 1, 2), result.conflictFactors.toSet())
    }

    @Test
    fun `an Implied result should be disjoint from input assumptions`() {
        val p = boolProblem(2, intArrayOf(lit(0, false), lit(1, true)))
        val r = implied(p.propagate(Assumptions(bools = mapOf(0 to true))))
        assertEquals(mapOf(1 to true), r.bools)
    }

    @Test
    fun `exactlyOne and a clause should cascade pins together`() {
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality(intArrayOf(lit(0, true), lit(1, true)), 1, 1),
                Clause(intArrayOf(lit(0, false), lit(2, true))),
            ),
        )
        val r = implied(p.propagate(Assumptions(bools = mapOf(1 to false))))
        assertEquals(mapOf(0 to true, 2 to true), r.bools)
    }
}
