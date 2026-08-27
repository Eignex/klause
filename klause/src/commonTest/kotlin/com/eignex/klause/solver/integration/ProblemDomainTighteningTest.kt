package com.eignex.klause.solver.integration

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.RootBaker
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        assertEquals(0, p.requireFiniteIntDomains()[0].min)
        assertEquals(1, p.requireFiniteIntDomains()[0].max)
    }

    @Test
    fun `pinned vars collapse to singleton domains`() {
        val p =
            Problem(
                0,
                1,
                arrayOf(IntDomain(-1_000_000, 1_000_000)),
                listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 7)),
            ).bake()
        assertEquals(7, p.requireFiniteIntDomains()[0].min)
        assertEquals(7, p.requireFiniteIntDomains()[0].max)
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
            ).bake()
        for (v in 0..1) {
            assertEquals(0, p.requireFiniteIntDomains()[v].min, "var $v min")
            assertEquals(1, p.requireFiniteIntDomains()[v].max, "var $v max")
        }
    }

    @Test
    fun `interior holes from SAC probing are excluded from the domain`() {
        val base =
            Problem(
                0,
                1,
                arrayOf(IntDomain(0, 4)),
                listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 2)),
            ).bake()
        val p = RootBaker.reseed(base, BakeConfig(probeIntHoles = true))
        assertIs<PropagationResult.Implied>(p.baked)
        assertFalse(2 in p.requireFiniteIntDomains()[0])
        assertTrue(1 in p.requireFiniteIntDomains()[0])
        assertTrue(3 in p.requireFiniteIntDomains()[0])
    }

    @Test
    fun `cross-constraint bound propagation reaches a multi-hop fixpoint in the bake`() {
        // A three-link chain x0 >= x1 >= x2 >= x3 with only x0 <= 5 declared: the upper bound has to
        // hop x0 -> x1 -> x2 -> x3 across separate constraints, which only a propagation *fixpoint*
        // (re-queue a factor when a neighbour's bound moves), not a one-shot per-constraint sweep,
        // can reach. The bake folds that fixpoint into the problem's domains.
        val wide = { IntDomain(0, 1_000_000) }
        val p =
            Problem(
                0,
                4,
                arrayOf(wide(), wide(), wide(), wide()),
                listOf(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5), // x0 <= 5
                    Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, 0), // x1 - x0 <= 0
                    Linear(intArrayOf(1, -1), intArrayOf(2, 1), LinearOp.LE, 0), // x2 - x1 <= 0
                    Linear(intArrayOf(1, -1), intArrayOf(3, 2), LinearOp.LE, 0), // x3 - x2 <= 0
                ),
            ).bake()
        for (v in 0..3) assertEquals(5, p.requireFiniteIntDomains()[v].max, "var $v max should propagate to 5")
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
        val cancelled =
            Problem(0, 2, arrayOf(wide(), wide()), factors, cancellation = { true }).bake()
        assertIs<PropagationResult.Implied>(cancelled.baked)
        for (v in 0..1) {
            assertEquals(-1_000_000, cancelled.requireFiniteIntDomains()[v].min, "var $v min unchanged")
            assertEquals(1_000_000, cancelled.requireFiniteIntDomains()[v].max, "var $v max unchanged")
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
        assertEquals(0, p.requireFiniteIntDomains()[0].min)
        assertEquals(10, p.requireFiniteIntDomains()[0].max)
    }
}
