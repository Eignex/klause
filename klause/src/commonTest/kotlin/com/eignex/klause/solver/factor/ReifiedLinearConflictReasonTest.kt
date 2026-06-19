package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.linear.Linear
import com.eignex.klause.solver.factor.linear.LinearOp
import com.eignex.klause.solver.factor.linear.ReifiedLinear
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.propagation.pinBoolAsDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #651: [ReifiedLinear] now overrides [Factor.conflictReason] with the indicator-aware linear
 * nogood — the bound atoms of the term vars plus the indicator literal `¬[auxBoolVar = current]`.
 * It extends `LinearSumFactor`, not [Linear], so it did not inherit [Linear]'s override and fell
 * through to the coarse default bool-pins reason (suppressed once an int decision is on the trail).
 * Tests: (1) the reason is a sound non-empty witness containing the indicator literal — every
 * literal false at conflict time; (2) full enumeration under CDCL learning matches brute force for
 * the LE, EQ, and single-term-EQ-unreachable paths, so the new nogood prunes no feasible tuple.
 */
class ReifiedLinearConflictReasonTest {

    @Test
    fun `body conflict reason is a sound witness containing the indicator literal`() {
        // aux ↔ (v0 ≥ 5). Decide aux=true at level 1, then squeeze v0 ≤ 4 → the body must hold
        // (GE 5) but cannot, so body propagation wipes v0's domain and propagate returns false.
        val factor = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(1),
            vars = intArrayOf(0),
            op = LinearOp.GE,
            bound = 5,
        )
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 9)),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        state.pinBoolAsDecision(0, true)
        assertTrue(state.tightenIntMax(0, 4), "squeeze v0 ≤ 4")
        assertFalse(factor.propagate(state, 0), "body GE 5 must be infeasible under v0 ≤ 4 with aux true")

        val reason = factor.conflictReason(state, 0)
        assertTrue(reason != null && reason.isNotEmpty(), "must yield a non-empty clause-form reason")
        for (lit in reason) {
            assertTrue(state.litFalse(lit), "every reason literal must be false at conflict time, lit=$lit")
        }
        assertTrue(
            Lit.make(0, false) in reason.toSet(),
            "reason must thread the indicator literal ¬[aux=true], got ${reason.toList()}",
        )
    }

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Int>> = BacktrackSolver(problem)
        .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
        .take(100_000)
        .map { it.bools.map { b -> if (b) 1 else 0 } + it.ints.toList() }
        .toHashSet()

    @Test
    fun `enumerate matches brute force for reified LE`() {
        // aux ↔ (v0 + v1 ≤ 2), both in [0, 3]. aux is free, so enumeration spans both polarities;
        // each conflicting branch learns off the new indicator-aware nogood.
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 1,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    ReifiedLinear(
                        auxBoolVar = 0,
                        coeffs = intArrayOf(1, 1),
                        vars = intArrayOf(0, 1),
                        op = LinearOp.LE,
                        bound = 2,
                    ),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (a in 0..1) {
                for (v0 in 0..3) {
                    for (v1 in 0..3) {
                        if ((a == 1) == (v0 + v1 <= 2)) brute.add(listOf(a, v0, v1))
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: reified LE must match brute force")
        }
    }

    @Test
    fun `enumerate matches brute force for reified EQ`() {
        // aux ↔ (v0 + v1 = 3), both in [0, 3] — a tight equality that drives many body conflicts.
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 1,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    ReifiedLinear(
                        auxBoolVar = 0,
                        coeffs = intArrayOf(1, 1),
                        vars = intArrayOf(0, 1),
                        op = LinearOp.EQ,
                        bound = 3,
                    ),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (a in 0..1) {
                for (v0 in 0..3) {
                    for (v1 in 0..3) {
                        if ((a == 1) == (v0 + v1 == 3)) brute.add(listOf(a, v0, v1))
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: reified EQ must match brute force")
        }
    }

    @Test
    fun `enumerate matches brute force for single-term EQ with unreachable target`() {
        // aux ↔ (2·v0 = 3), v0 in [0, 3]. 3 is not divisible by 2, so the body can never hold —
        // aux is forced false via the eqTargetUnreachable path (hole-aware reason).
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 1,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    ReifiedLinear(
                        auxBoolVar = 0,
                        coeffs = intArrayOf(2),
                        vars = intArrayOf(0),
                        op = LinearOp.EQ,
                        bound = 3,
                    ),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (v0 in 0..3) brute.add(listOf(0, v0)) // aux always false
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: unreachable EQ target forces aux false")
        }
    }

    @Test
    fun `enumerate should cover all projected models for int-bit channels with reified eq`() {
        // x in [0,3], bits c0/c1 in {0,1}, booleans b0/b1 with
        // b0 <-> (c0 == 1), b1 <-> (c1 == 1), and x - c0 - 2*c1 == 0.
        val nv = 3
        for (seed in 1L..5L) {
            var nextBool = 0
            var nextInt = nv
            val doms = ArrayList<IntDomain>().apply { repeat(nv) { add(IntDomain(0, 3)) } }
            val factors = ArrayList<Factor>()
            repeat(nv) { x ->
                val c0 = nextInt++
                val c1 = nextInt++
                doms.add(IntDomain(0, 1))
                doms.add(IntDomain(0, 1))
                val b0 = nextBool++
                val b1 = nextBool++
                factors.add(ReifiedLinear(b0, intArrayOf(1), intArrayOf(c0), LinearOp.EQ, 1))
                factors.add(ReifiedLinear(b1, intArrayOf(1), intArrayOf(c1), LinearOp.EQ, 1))
                factors.add(Linear(intArrayOf(1, -1, -2), intArrayOf(x, c0, c1), LinearOp.EQ, 0))
            }
            val problem = Problem(
                numBoolVars = nextBool,
                numIntVars = nextInt,
                intDomains = doms.toTypedArray(),
                factors = factors.toTypedArray(),
            )
            val projected = BacktrackSolver(problem).enumerate(
                BacktrackParams(
                    maxDecisions = 50_000_000L,
                    randomSeed = seed,
                    variableSelector = Vsids(),
                ),
            ).map { sample -> (0 until nv).map { sample.ints[it] } }.toHashSet()
            val expected = HashSet<List<Int>>()
            for (x0 in 0..3) for (x1 in 0..3) for (x2 in 0..3) expected.add(listOf(x0, x1, x2))
            assertEquals(expected, projected, "seed=$seed: enumerate must cover all 64 projected models")
        }
    }
}
