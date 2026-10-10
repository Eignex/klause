package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.ConflictReasonOracle
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.scheduling.internals.CumulativeThetaTree
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.solver.Sample
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CumulativePropagatorTest {

    @Test
    fun `cumulative deductions over optional tasks are implied by their reasons`() {
        val rng = Random(0xC0A1)
        repeat(400) { iter ->
            val n = 3
            val problem = Problem(
                numBoolVars = n,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, 5) },
                factors = arrayOf<Factor>(
                    Cumulative(
                        starts = IntArray(n) { it },
                        durations = LongArray(n) { 2L + rng.nextInt(2) },
                        resources = LongArray(n) { 1L + rng.nextInt(2) },
                        capacity = 2L,
                        presents = IntArray(n) { Lit.make(it, true) },
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "cumulative#$iter") { state ->
                (0 until n).all { b -> rng.nextInt(3) != 0 || state.pinBool(b, rng.nextBoolean()) } &&
                    (0 until 3).all {
                        val v = rng.nextInt(n)
                        if (rng.nextBoolean()) {
                            state.tightenIntMax(v, rng.nextInt(4).toLong())
                        } else {
                            state.tightenIntMin(v, 1L + rng.nextInt(4))
                        }
                    }
            }
        }
    }

    @Test
    fun `energetic reasoning caps a resource height that cannot fit the shared window`() {
        // Two tasks both start at 0. Task 0 (dur 2, height pinned 2) and task 1 (dur 3, height
        // ∈ [0,3]) share the window [0,3] under capacity 3. Energetic area: 3·3 = 9; task 0 commits
        // 2·2 = 4, leaving 5 for task 1 across its 3 units ⇒ height1 ≤ ⌊5/3⌋ = 1. Time-tabling alone
        // never touches the (variable) height. Layout: s0=0, s1=1, r0=2, r1=3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 0), IntDomain(2, 2), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Cumulative(
                    starts = intArrayOf(0, 1),
                    durations = longArrayOf(2, 3),
                    resources = longArrayOf(2, 3),
                    capacity = 3,
                    resourceVars = intArrayOf(2, 3),
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentFactor = 0
        assertTrue(problem.propagators[0].propagate(state, 0))
        assertEquals(1, state.intDomains[3].max, "task 1 height must be capped at 1 by energetic reasoning")
    }

    @Test
    fun `profile height pruning beats the energetic area bound at a compulsory peak`() {
        // Task 0 spans [0,4) with variable height; three unit tasks pinned at 0 stack a height-3
        // compulsory peak in [0,1). Under capacity 5 the peak forces height0 ≤ 5−3 = 2, whereas the
        // energetic bound (energy 3 spread over the length-4 window) would only give ≤ 4.
        // Layout: starts 0..3, heights (resource vars) 4..7.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 8,
            intDomains = arrayOf(
                IntDomain(0, 0),
                IntDomain(0, 0),
                IntDomain(0, 0),
                IntDomain(0, 0),
                IntDomain(0, 5),
                IntDomain(1, 1),
                IntDomain(1, 1),
                IntDomain(1, 1),
            ),
            factors = arrayOf<Factor>(
                Cumulative(
                    starts = intArrayOf(0, 1, 2, 3),
                    durations = longArrayOf(4, 1, 1, 1),
                    resources = longArrayOf(5, 1, 1, 1),
                    capacity = 5,
                    resourceVars = intArrayOf(4, 5, 6, 7),
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentFactor = 0
        assertTrue(problem.propagators[0].propagate(state, 0))
        assertEquals(2, state.intDomains[4].max, "the compulsory peak must cap task 0's height at 2")
    }

    @Test
    fun `cumulative deductions over variable demands and capacity are implied by their reasons`() {
        val rng = Random(0xC0C)
        repeat(200) { iter ->
            val n = 3
            // Starts 0..2, durations 3..5, heights 6..8, capacity 9.
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 3 * n + 1,
                intDomains = Array(3 * n + 1) {
                    when {
                        it < n -> IntDomain(0, 4)
                        it < 2 * n -> IntDomain(1, 3)
                        it < 3 * n -> IntDomain(1, 2)
                        else -> IntDomain(1, 3)
                    }
                },
                factors = arrayOf<Factor>(
                    Cumulative(
                        starts = IntArray(n) { it },
                        durations = LongArray(n) { 1L },
                        resources = LongArray(n) { 1L },
                        capacity = 3,
                        durationVars = IntArray(n) { n + it },
                        resourceVars = IntArray(n) { 2 * n + it },
                        capacityVar = 3 * n,
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "cumulative-vars#$iter") { state ->
                (0 until 5).all {
                    val v = rng.nextInt(3 * n + 1)
                    val d = state.intDomains[v]
                    when (rng.nextInt(3)) {
                        0 -> state.tightenIntMin(v, d.min + rng.nextInt(2))
                        1 -> state.tightenIntMax(v, d.max - rng.nextInt(2))
                        else -> state.excludeIntValue(v, d.min + rng.nextInt((d.max - d.min + 1).toInt()))
                    }
                }
            }
        }
    }

    @Test
    fun `a height bound cites only the compulsory parts stacked on its peak`() {
        // Task 0 spans [0, 4) with variable height; tasks 1 and 2 stack height 3 at time 0, and task 3, at time 6,
        // plays no part. Capacity 5 leaves task 0 at most 2: the reason is task 0 covering time 0, both stacked
        // starts, and task 1's height (task 2's is the root's).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 8,
            intDomains = Array(8) { if (it < 4) IntDomain(0, 9) else IntDomain(1, 5) },
            factors = arrayOf<Factor>(
                Cumulative(
                    starts = intArrayOf(0, 1, 2, 3),
                    durations = longArrayOf(4, 1, 1, 1),
                    resources = longArrayOf(1, 1, 1, 1),
                    capacity = 5,
                    resourceVars = intArrayOf(4, 5, 6, 7),
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 0) && state.tightenIntMax(1, 0) && state.tightenIntMax(2, 0))
        check(state.tightenIntMin(5, 2) && state.tightenIntMin(6, 1) && state.tightenIntMin(3, 6))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        assertEquals(2, state.intDomains[4].max)
        val cited = state.reasonOf(state.intMaxAntecedents[4])!!.map { state.atoms.intVar[Lit.variable(it)] }.toSet()
        assertEquals(setOf(0, 1, 2, 5), cited)
    }

    @Test
    fun `time table domain wipeout cites the tasks blocking every start`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 6), IntDomain(0, 6)),
            factors = arrayOf<Factor>(
                Cumulative(
                    starts = intArrayOf(0, 1, 2),
                    durations = longArrayOf(5, 1, 1),
                    resources = longArrayOf(2, 3, 3),
                    capacity = 4,
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        assertTrue(state.setInt(1, 3) && state.setInt(2, 5))
        state.currentFactor = 0

        assertFalse(state.factorAt(0).propagate(state, 0))

        val reason = assertNotNull(state.factorAt(0).conflictReason(state, 0))
        for (starts in listOf(longArrayOf(4, 3, 0), longArrayOf(0, 6, 5))) {
            val solution = Sample(BooleanArray(0), starts)
            assertTrue(reason.any { ConflictReasonOracle.litTrueUnder(problem, state, it, solution) })
        }
    }

    @Test
    fun `profile-overload reason is a sound witness that omits non-covering tasks`() {
        // 3 tasks, duration 2, resource 2, capacity 2, starts in [0, 10]. Pin tasks 0 and 1 to t=0:
        // their compulsory parts [0,2) stack to level 4 > 2 at t=0 → profile overload. Task 2 is
        // pinned far away (t=8), so it does not cover the overloaded point and must not be cited.
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = longArrayOf(2, 2, 2),
            resources = longArrayOf(2, 2, 2),
            capacity = 2,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        assertTrue(state.setInt(0, 0) && state.setInt(1, 0) && state.setInt(2, 8), "pin the three starts")
        state.currentFactor = 0
        assertFalse(
            state.factorAt(0).propagate(state, 0),
            "tasks 0 and 1 double-book capacity at t=0 → infeasible",
        )

        val reason = state.factorAt(0).conflictReason(state, 0)
        assertTrue(reason != null && reason.isNotEmpty(), "must yield a non-empty clause-form reason")
        for (lit in reason) {
            assertTrue(state.litFalse(lit), "every reason literal must be false at conflict time, lit=$lit")
        }
        // Sharp: only the two stacking tasks' upper-start bounds are cited (each `¬[start ≤ 0]`),
        // never task 2's bounds — that is the whole point of the pointwise explanation.
        assertEquals(2, reason.size, "pointwise reason cites only the two tasks covering the overload")
    }

    @Test
    fun `overload check detects energy infeasibility that time-tabling misses`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = longArrayOf(3, 3, 3),
            resources = longArrayOf(1, 1, 1),
            capacity = 1,
        )
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val baked = p.propagate()
        assertTrue(
            baked is PropagationResult.Unsat,
            "overload check should mark this as Unsat, got $baked",
        )
    }

    @Test
    fun `propagator shaves a start that would overlap a mandatory part`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = longArrayOf(4, 2),
            resources = longArrayOf(1, 1),
            capacity = 1,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate()
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        assertEquals(4, result.ints[1], "task 1 must be pinned to t=4 after time-tabling shaves earlier starts")
    }

    @Test
    fun `edge-finding tightens a start past where time-tabling can reach`() {
        // A, B: duration 2, resource 2, start ∈ [0, 2]. Neither has a compulsory part
        // (lst=2, ect=2). C: duration 2, resource 3, start ∈ [0, 10]. Capacity 3.
        //
        // Time-tabling builds no mandatory profile (no compulsory parts exist) and the
        // overload check passes (energy 4+4+6=14 ≤ 3·12=36). But Θ = {A,B} has envelope
        // C·est(Ω)+e(Ω) maximised at Ω={A,B} → 0+8 = 8. With τ=lct(Θ)=4 and C with c=3:
        //   detection: 8 + 6 > 3·4 = 12  ✓
        //   update:    est(C) ≥ ⌈(8 − (3−3)·4) / 3⌉ = ⌈8/3⌉ = 3.
        // C's wide upper bound (10) keeps the problem feasible after the deduction so
        // the result is Implied(intMin=3 for C), not Unsat.
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = longArrayOf(2, 2, 2),
            resources = longArrayOf(2, 2, 3),
            capacity = 3,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 10)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate()
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        assertEquals(
            3,
            result.intMinOrNullCompat(2),
            "edge-finding should push C's start min from 0 to 3",
        )
    }

    @Test
    fun `edge-finding does not push a task that can run before the cluster`() {
        // Regression guard for the unsound env(Θ)+e_i detection. Capacity 1. Task 0 is fixed
        // at t=1 (dur 1, res 1) → busy [1, 2). Task 1 (dur 1, res 1, dom [0, 3]) can legitimately
        // run at t=0, before task 0. The flat detection would force task 1 after task 0
        // (start ≥ 2); the sound env(Θ ∪ {i}) insertion must leave t=0 feasible.
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = longArrayOf(1, 1),
            resources = longArrayOf(1, 1),
            capacity = 1,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 1), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val ok = problem.propagate(Assumptions(ints = mapOf(1 to 0)))
        assertTrue(ok is PropagationResult.Implied, "task 1 at t=0 (before task 0) must stay feasible; got $ok")
    }

    @Test
    fun `edge-finding does not push a task past a subset with no leftover energy`() {
        // Capacity 13. B (d5, r10) is fixed at 5 and C (d4, r11) at 1; A (d16, r2) fits beside both from
        // t=1. Θ = {B, C} precedes A, but neither subset of Θ has energy left over once A runs beside it,
        // so A's start must stay at 1.
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = longArrayOf(16, 5, 4),
            resources = longArrayOf(2, 10, 11),
            capacity = 13,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 30), IntDomain(1, 30), IntDomain(1, 30)),
            factors = arrayOf<Factor>(factor),
        )

        val result = problem.propagate(Assumptions(ints = mapOf(1 to 5L, 2 to 1L)))

        assertIs<PropagationResult.Implied>(result)
        assertEquals(null, result.intMinOrNullCompat(0))
    }

    @Test
    fun `zero-duration task contributes no usage`() {
        // A duration-0 task occupies no time, so it never loads the resource — feasible even
        // when its resource demand exceeds capacity.
        val factor = Cumulative(
            starts = intArrayOf(0),
            durations = longArrayOf(0),
            resources = longArrayOf(5),
            capacity = 1,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
        assertTrue(problem.propagate() is PropagationResult.Implied)
    }

    @Test
    fun `zero capacity with a positive task is infeasible`() {
        val factor = Cumulative(
            starts = intArrayOf(0),
            durations = longArrayOf(1),
            resources = longArrayOf(1),
            capacity = 0,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
        assertTrue(problem.propagate() is PropagationResult.Unsat)
    }

    @Test fun `reactivation overwrites the prior contribution`() {
        val t = CumulativeThetaTree(n = 2, capacity = 1)
        t.setLeafOrder(intArrayOf(0, 1))
        t.activate(0, est = 0, taskEnergy = 100L)
        t.activate(0, est = 10, taskEnergy = 1L)
        t.activate(1, est = 20, taskEnergy = 1L)
        // After the reactivation: task 0 est=10 e=1, task 1 est=20 e=1.
        // env(0) = 10 + 1 = 11; env(1) = 20 + 1 = 21; env(theta) = max(11 + 1, 21) = 21.
        assertEquals(21L, t.envOfTheta())
        assertEquals(2L, t.energyOfTheta())
    }

    private fun assertBoundOnly(watches: IntArray?, vars: IntArray) {
        val pairs = watches!!.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet()
        val expected = vars.toHashSet().flatMap { v ->
            listOf(v to IntEvent.LB_RAISED, v to IntEvent.UB_LOWERED)
        }.toSet()
        assertEquals(expected, pairs)
        assertFalse(
            watches.any { IntEvent.kindOf(it) == IntEvent.VALUE_REMOVED || IntEvent.kindOf(it) == IntEvent.FIXED },
        )
    }

    @Test
    fun `cumulative diffn disjunctive subscribe to only bound events`() {
        assertBoundOnly(
            Cumulative(
                starts = intArrayOf(0, 1),
                durations = longArrayOf(2, 2),
                resources = longArrayOf(1, 1),
                capacity = 1,
            )
                .propagatorProjection().initialIntEventWatches,
            intArrayOf(0, 1),
        )
        assertBoundOnly(
            Diffn(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3), widths = longArrayOf(1, 1), heights = longArrayOf(1, 1))
                .propagatorProjection().initialIntEventWatches,
            intArrayOf(0, 1, 2, 3),
        )
        assertBoundOnly(
            Cumulative.unary(
                starts = intArrayOf(0, 1, 2),
                durations = longArrayOf(2, 1, 1),
            ).propagatorProjection().initialIntEventWatches,
            intArrayOf(0, 1, 2),
        )
        // Reified linear's int reasoning is interval-based (linearSumRange + propagateLinearBounds);
        // it subscribes its term vars to LB/UB only — the indicator bool keeps its Boolean wakeup.
        val reified = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(1, 1),
            vars = intArrayOf(1, 2),
            op = LinearOp.LE,
            bound = 3,
        )
        assertBoundOnly(reified.propagatorProjection().initialIntEventWatches, intArrayOf(1, 2))
    }

}
