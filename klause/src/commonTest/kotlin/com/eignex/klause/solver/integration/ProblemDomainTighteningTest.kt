package com.eignex.klause.solver.integration

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.*
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Root-level deductions from the bake are folded into [BakedProblem.intDomains] by [Problem.bake], so
 *  every solver sees the tightened domains rather than the loosely-declared input. */
class ProblemDomainTighteningTest {
    @Test
    fun `bound tightenings become the baked problem's domains`() {
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
    fun `caller-supplied domain array is not mutated by the bake`() {
        val input = arrayOf(IntDomain(-1_000_000, 1_000_000))
        Problem(0, 1, input, listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7))).bake()
        assertEquals(-1_000_000, input[0].min)
        assertEquals(1_000_000, input[0].max)
    }

    @Test
    fun `a fired cancellation makes the bake a sound no-op`() {
        // With an already-fired cancellation the all-factors fixpoint bails before firing any
        // factor: domains stay as declared and the bake is sound (Implied, not Unsat).
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
            assertEquals(-1_000_000, cancelled.rootIntDomain(v).min, "var $v min unchanged")
            assertEquals(1_000_000, cancelled.rootIntDomain(v).max, "var $v max unchanged")
        }
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
            ).bake()
        assertIs<PropagationResult.Unsat>(p.baked)
        assertEquals(0, p.rootIntDomain(0).min)
        assertEquals(10, p.rootIntDomain(0).max)
    }
}
