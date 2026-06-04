package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Root-level deductions from the bake are folded into [Problem.intDomains], so every
 *  consumer sees the tightened domains rather than the loosely-declared input. */
class ProblemDomainTighteningTest {
    @Test
    fun `bound tightenings become the problem's domains`() {
        val p =
            Problem(
                0,
                1,
                arrayOf(IntDomain(-1_000_000, 1_000_000)),
                listOf(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                ),
            )
        assertEquals(0, p.intDomains[0].min)
        assertEquals(1, p.intDomains[0].max)
    }

    @Test
    fun `pinned vars collapse to singleton domains`() {
        val p =
            Problem(
                0,
                1,
                arrayOf(IntDomain(-1_000_000, 1_000_000)),
                listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7)),
            )
        assertEquals(7, p.intDomains[0].min)
        assertEquals(7, p.intDomains[0].max)
    }

    @Test
    fun `partition-style sum over wide domains derives indicator bounds`() {
        // x + y = 1 with x, y >= 0 over wide domains: both tighten to [0..1].
        val p =
            Problem(
                0,
                2,
                arrayOf(IntDomain(-1_000_000, 1_000_000), IntDomain(-1_000_000, 1_000_000)),
                listOf(
                    Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0),
                    Linear(intArrayOf(1), intArrayOf(1), LinearOp.GE, 0),
                ),
            )
        for (v in 0..1) {
            assertEquals(0, p.intDomains[v].min, "var $v min")
            assertEquals(1, p.intDomains[v].max, "var $v max")
        }
    }

    @Test
    fun `interior holes from SAC probing are excluded from the domain`() {
        val p =
            Problem(
                0,
                1,
                arrayOf(IntDomain(0, 4)),
                listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 2)),
                probeIntHoles = true,
            )
        assertIs<PropagationResult.Implied>(p.baked)
        assertFalse(2 in p.intDomains[0])
        assertTrue(1 in p.intDomains[0])
        assertTrue(3 in p.intDomains[0])
    }

    @Test
    fun `caller-supplied domain array is not mutated`() {
        val input = arrayOf(IntDomain(-1_000_000, 1_000_000))
        Problem(0, 1, input, listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7)))
        assertEquals(-1_000_000, input[0].min)
        assertEquals(1_000_000, input[0].max)
    }

    @Test
    fun `unsat bake leaves domains as declared`() {
        val p =
            Problem(
                0,
                1,
                arrayOf(IntDomain(0, 10)),
                listOf(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5),
                ),
            )
        assertIs<PropagationResult.Unsat>(p.baked)
        assertEquals(0, p.intDomains[0].min)
        assertEquals(10, p.intDomains[0].max)
    }
}
