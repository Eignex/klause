package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.*
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProblemBakingTest {
    @Test
    fun `bake should turn bound tightenings into root domains`() {
        val p =
            Problem(
                0,
                1,
                arrayOf(IntDomain(-1_000_000, 1_000_000)),
                listOf(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                ),
            ).bake()
        assertEquals(0, p.rootIntDomain(0).min)
        assertEquals(1, p.rootIntDomain(0).max)
    }

    @Test
    fun `bake should not mutate the caller domain array`() {
        val input = arrayOf(IntDomain(-1_000_000, 1_000_000))
        Problem(0, 1, input, listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7))).bake()
        assertEquals(-1_000_000, input[0].min)
        assertEquals(1_000_000, input[0].max)
    }

    @Test
    fun `bake with fired cancellation should leave domains unchanged`() {
        val wide = { IntDomain(-1_000_000, 1_000_000) }
        val factors =
            listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.GE, 0),
            )
        val cancelled = Problem(0, 2, arrayOf(wide(), wide()), factors).bake(Cancellation { true })
        assertIs<PropagationResult.Implied>(cancelled.baked)
        for (v in 0..1) {
            assertEquals(-1_000_000, cancelled.rootIntDomain(v).min, "var $v")
            assertEquals(1_000_000, cancelled.rootIntDomain(v).max, "var $v")
        }
    }

    @Test
    fun `unsat bake should leave domains as declared`() {
        val p =
            Problem(
                0,
                1,
                arrayOf(IntDomain(0, 10)),
                listOf(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5),
                ),
            ).bake()
        assertIs<PropagationResult.Unsat>(p.baked)
        assertEquals(0, p.rootIntDomain(0).min)
        assertEquals(10, p.rootIntDomain(0).max)
    }

    @Test
    fun `seeded conflicting ints should decode to the conflict set`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)),
        )
        val r = p.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 1)))
        val u = assertIs<PropagationResult.Unsat>(r)
        assertTrue(0 in u.conflictInts)
        assertTrue(1 in u.conflictInts)
    }

    @Test
    fun `an irrelevant assumption should stay out of the conflict set`() {
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(2, true))),
            ),
        )
        val r = p.propagate(Assumptions(bools = mapOf(0 to false, 1 to false, 2 to true)))
        val u = assertIs<PropagationResult.Unsat>(r)
        assertTrue(0 in u.conflictBools)
        assertTrue(1 in u.conflictBools)
        assertEquals(false, 2 in u.conflictBools)
    }
}
