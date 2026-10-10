package com.eignex.klause.propagation
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class RootPropagationTest {

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
    fun `a model that states bounds alone propagates over the range they close`() {
        // x + y <= 3 over x, y in [0, 10] declared as bounds, with no value set stated for either.
        val p = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(10, 10), null, null),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3L),
            ),
        )

        val r = implied(p.propagate())

        assertEquals(3L, r.intMaxOrNullCompat(0), "the bound closes the range the row prunes against")
        assertEquals(3L, r.intMaxOrNullCompat(1))
    }

    @Test
    fun `a clause conflict reports the factors that forced its false literals`() {
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
    fun `Implied result is disjoint from input assumptions`() {
        val p = boolProblem(2, intArrayOf(lit(0, false), lit(1, true)))
        val r = implied(p.propagate(Assumptions(bools = mapOf(0 to true))))
        assertEquals(mapOf(1 to true), r.bools)
    }

    @Test
    fun `exactlyOne with two-literal cascade pins via Cardinality and Clause together`() {
        // exactlyOne(x0, x1), and (!x0 v x2). Pin x1=false → x0 must be true → x2 must be true.
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
