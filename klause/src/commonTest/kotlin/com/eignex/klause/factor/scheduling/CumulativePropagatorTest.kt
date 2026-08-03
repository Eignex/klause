package com.eignex.klause.factor.scheduling

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.FactorPropagationOracle
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.scheduling.internals.CumulativeThetaTree
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.StructuralKey
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CumulativePropagatorTest {

    // --- Energetic reasoning (#4) ---

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
    fun `cumulative with variable heights never over-prunes`() {
        // Brute-force oracle over small random instances with variable resource demands — the regime
        // the old propagator skipped entirely (it needed a fully-fixed snapshot). Kept under the
        // BruteForceSolver 2^18 cap.
        val rng = Random(0xC0FFEE)
        repeat(300) { iter ->
            val tasks = 2 + rng.nextInt(2) // 2 or 3 tasks
            val starts = IntArray(tasks) { it }
            val resourceVars = IntArray(tasks) { tasks + it }
            val durations = LongArray(tasks) { 1L + rng.nextInt(2) } // 1 or 2
            val resourceUbs = LongArray(tasks) { 3L }
            val capacity = (2 + rng.nextInt(2)).toLong() // 2 or 3
            val doms = ArrayList<IntDomain>()
            repeat(tasks) { doms.add(IntDomain(0, 2)) } // start domains
            repeat(tasks) { doms.add(IntDomain(0, 2)) } // height domains
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2 * tasks,
                intDomains = doms.toTypedArray(),
                factors = arrayOf<Factor>(
                    Cumulative(
                        starts = starts,
                        durations = durations,
                        resources = resourceUbs,
                        capacity = capacity,
                        resourceVars = resourceVars,
                    ),
                ),
            )
            FactorPropagationOracle.assertSound(problem, "cumulative-varH#$iter")
        }
    }

    // --- From CumulativeConflictReasonTest ---

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
        assertFalse(
            problem.propagators[0].propagate(state, 0),
            "tasks 0 and 1 double-book capacity at t=0 → infeasible",
        )

        val reason = problem.propagators[0].conflictReason(state, 0)
        assertTrue(reason != null && reason.isNotEmpty(), "must yield a non-empty clause-form reason")
        for (lit in reason) {
            assertTrue(state.litFalse(lit), "every reason literal must be false at conflict time, lit=$lit")
        }
        // Sharp: only the two stacking tasks' upper-start bounds are cited (each `¬[start ≤ 0]`),
        // never task 2's bounds — that is the whole point of the pointwise explanation.
        assertEquals(2, reason.size, "pointwise reason cites only the two tasks covering the overload")
    }

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Int>> = BacktrackSolver(problem.bake())
        .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
        .take(100_000)
        .map { it.ints.map { v -> v.toInt() } }
        .toHashSet()

    @Test
    fun `enumerate matches brute force under learning`() {
        // 3 unit-demand tasks, duration 2, capacity 1, starts in [0, 5]. Capacity 1 forces the
        // occupied intervals [s, s+2) to be pairwise disjoint, i.e. pairwise |s_i − s_j| ≥ 2. The
        // tight domain makes the propagator overload and shave often, exercising the sharp reasons.
        val span = 5
        val dur = 2L
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(
                    IntDomain(0, span.toLong()),
                    IntDomain(0, span.toLong()),
                    IntDomain(0, span.toLong()),
                ),
                factors = arrayOf<Factor>(
                    Cumulative(
                        starts = intArrayOf(0, 1, 2),
                        durations = longArrayOf(dur, dur, dur),
                        resources = longArrayOf(1, 1, 1),
                        capacity = 1,
                    ),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (s0 in 0..span) {
                for (s1 in 0..span) {
                    for (s2 in 0..span) {
                        val starts = intArrayOf(s0, s1, s2)
                        var ok = true
                        for (a in 0..2) {
                            for (b in a + 1..2) {
                                if (abs(starts[a] - starts[b]) < dur) ok = false
                            }
                        }
                        if (ok) brute.add(listOf(s0, s1, s2))
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: cumulative enumerate must match brute force")
        }
    }

    @Test
    fun `enumerate matches brute force with variable resources`() {
        // The mspsp-shaped path: capacity 1, 0/1 resource vars (a task "uses" the resource only when
        // its var is 1). Two tasks, duration 2, starts in [0, 3], resource vars in [0, 1]. Infeasible
        // exactly when both use the resource (r=1) and their [s, s+2) intervals overlap (|s0−s1| < 2).
        // ints = [s0, s1, r0, r1]. This drives the variable-resource sharp reason (`¬[r_k ≥ 1]` cited).
        val span = 3
        val dur = 2L
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 4,
                intDomains = arrayOf(
                    IntDomain(0, span.toLong()),
                    IntDomain(0, span.toLong()),
                    IntDomain(0, 1),
                    IntDomain(0, 1),
                ),
                factors = arrayOf<Factor>(
                    Cumulative(
                        starts = intArrayOf(0, 1),
                        durations = longArrayOf(dur, dur),
                        resources = longArrayOf(1, 1),
                        capacity = 1,
                        resourceVars = intArrayOf(2, 3),
                    ),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (s0 in 0..span) {
                for (s1 in 0..span) {
                    for (r0 in 0..1) {
                        for (r1 in 0..1) {
                            val overlap = abs(s0 - s1) < dur
                            if (!(overlap && r0 == 1 && r1 == 1)) brute.add(listOf(s0, s1, r0, r1))
                        }
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: var-resource cumulative must match brute force")
        }
    }

    // --- From CumulativeTest (CP tests) ---

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
    fun `propagator fails when forced overlap exceeds capacity`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = longArrayOf(2, 2),
            resources = longArrayOf(2, 2),
            capacity = 2,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 0, 1 to 0)))
        assertTrue(result is PropagationResult.Unsat, "double-booking at capacity must fail; got $result")
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
    fun `propagator rejects a pin that would force overlap with a mandatory part`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1),
            durations = longArrayOf(4, 2),
            resources = longArrayOf(1, 1),
            capacity = 1,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 6)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions(ints = mapOf(1 to 2)))
        assertTrue(result is PropagationResult.Unsat, "overlap with mandatory part must fail; got $result")
    }

    @Test
    fun `BacktrackSolver finds a feasible 3-task unary schedule`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = longArrayOf(2, 2, 2),
            resources = longArrayOf(1, 1, 1),
            capacity = 1,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
        val solver = BacktrackSolver(problem.bake())
        val sample = solver.sample(BacktrackParams()).assignment
        assertNotNull(sample, "BacktrackSolver should find a feasible schedule")
        val starts = sample.ints
        val occ = IntArray(8)
        for (i in 0 until 3) {
            for (t in starts[i] until starts[i] + 2) {
                if (t in occ.indices) occ[t.toInt()]++
            }
        }
        for (t in occ.indices) assertTrue(occ[t] <= 1, "unary capacity broken at t=$t in $starts")
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
    fun `edge-finding is silent when no deduction applies`() {
        val factor = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = longArrayOf(1, 1, 1),
            resources = longArrayOf(1, 1, 1),
            capacity = 3,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate()
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        assertEquals(null, result.intMinOrNullCompat(0))
        assertEquals(null, result.intMinOrNullCompat(1))
        assertEquals(null, result.intMinOrNullCompat(2))
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
    fun `backtrack learning enumerates exactly the brute-force solution set`() {
        // Each instance triggers wide time-tabling pushes (push > duration) under conflict-driven
        // learning, exercising the window-scoped tightening reason. Small durations against a tight
        // capacity make most shaves span several slots, so the analyzer learns clauses built from
        // [Cumulative.windowOverloadReason]; an over-strong (unsound) reason would drop a feasible
        // leaf, so equality with brute force is the soundness check.
        data class Inst(val durs: IntArray, val res: IntArray, val cap: Int, val lo: Int, val hi: Int)
        val instances = listOf(
            // 4 unit tasks, unary capacity, shared window [0,3] → the 24 distinct-slot permutations.
            Inst(intArrayOf(1, 1, 1, 1), intArrayOf(1, 1, 1, 1), cap = 1, lo = 0, hi = 3),
            // 3 unit tasks demanding 2 of a capacity-3 resource: any overlap (2+2 > 3) is forbidden,
            // so again distinct slots, over a wider window [0,4].
            Inst(intArrayOf(1, 1, 1), intArrayOf(2, 2, 2), cap = 3, lo = 0, hi = 4),
            // duration-2 tasks, unary, window [0,5]: feasible iff pairwise non-overlapping; pushes of
            // 3+ (> the duration 2) occur as tasks serialise, hitting the wide-push branch.
            Inst(intArrayOf(2, 2, 2), intArrayOf(1, 1, 1), cap = 1, lo = 0, hi = 5),
            // mixed durations and demands under capacity 2 over [0,4].
            Inst(intArrayOf(2, 1, 2), intArrayOf(1, 2, 1), cap = 2, lo = 0, hi = 4),
        )
        for ((idx, inst) in instances.withIndex()) {
            val k = inst.durs.size
            val brute = HashSet<List<Int>>()
            fun feasible(starts: IntArray): Boolean {
                val occ = HashMap<Int, Int>()
                for (i in 0 until k) {
                    for (t in starts[i] until starts[i] + inst.durs[i]) {
                        val u = (occ[t] ?: 0) + inst.res[i]
                        if (u > inst.cap) return false
                        occ[t] = u
                    }
                }
                return true
            }
            fun rec(i: Int, acc: IntArray) {
                if (i == k) {
                    if (feasible(acc)) brute.add(acc.toList())
                    return
                }
                for (v in inst.lo..inst.hi) {
                    acc[i] = v
                    rec(i + 1, acc)
                }
            }
            rec(0, IntArray(k))

            val problem = Problem(
                numBoolVars = 0,
                numIntVars = k,
                intDomains = Array(k) { IntDomain(inst.lo.toLong(), inst.hi.toLong()) },
                factors = arrayOf<Factor>(
                    Cumulative(
                        starts = IntArray(k) { it },
                        durations = LongArray(inst.durs.size) { inst.durs[it].toLong() },
                        resources = LongArray(inst.res.size) { inst.res[it].toLong() },
                        capacity = inst.cap.toLong(),
                    ),
                ),
            )
            // CDCL so conflict analysis + clause learning (hence the window reasons) actually run;
            // no restarts so enumeration completeness is simple to reason about.
            val params = BacktrackParams(randomSeed = 1, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem.bake()).enumerate(params).take(100_000)
                .map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "instance #$idx: cumulative backtrack solution set must equal brute force")
        }
    }

    @Test
    fun `single task never overloads`() {
        val factor = Cumulative(
            starts = intArrayOf(0),
            durations = longArrayOf(2),
            resources = longArrayOf(1),
            capacity = 1,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate()
        assertTrue(result is PropagationResult.Implied, "single task is always feasible; got $result")
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

    // --- From CumulativeThetaTreeTest ---

    @Test fun `empty tree returns no envelope`() {
        val t = CumulativeThetaTree(n = 4, capacity = 3)
        assertEquals(CumulativeThetaTree.NO_ENV, t.envOfTheta())
        assertEquals(0L, t.energyOfTheta())
        assertFalse(t.isActive(0))
    }

    @Test fun `single task envelope matches the formula`() {
        val t = CumulativeThetaTree(n = 1, capacity = 2)
        t.activate(id = 0, est = 5, taskEnergy = 6L)
        assertTrue(t.isActive(0))
        assertEquals(2L * 5 + 6, t.envOfTheta())
        assertEquals(6L, t.energyOfTheta())
    }

    @Test fun `deactivate restores the empty envelope`() {
        val t = CumulativeThetaTree(n = 2, capacity = 2)
        t.activate(0, est = 0, taskEnergy = 4L)
        t.activate(1, est = 3, taskEnergy = 6L)
        t.deactivate(0)
        t.deactivate(1)
        assertEquals(CumulativeThetaTree.NO_ENV, t.envOfTheta())
        assertEquals(0L, t.energyOfTheta())
    }

    @Test fun `two tasks left anchor wins the envelope`() {
        // Tasks: a est=0 e=10, b est=5 e=2, capacity=1.
        // env(a) = 0 + 10 = 10
        // env(b) = 5 + 2 = 7
        // env(theta) = max(env(a) + e(b), env(b)) = max(12, 7) = 12
        val t = CumulativeThetaTree(n = 2, capacity = 1)
        t.setLeafOrder(intArrayOf(0, 1))
        t.activate(0, est = 0, taskEnergy = 10L)
        t.activate(1, est = 5, taskEnergy = 2L)
        assertEquals(12L, t.envOfTheta())
        assertEquals(12L, t.energyOfTheta())
    }

    @Test fun `two tasks right anchor wins the envelope`() {
        // Tasks: a est=0 e=1, b est=100 e=5, capacity=10.
        // env(a) = 0 + 1 = 1
        // env(b) = 1000 + 5 = 1005
        // env(theta) = max(1 + 5, 1005) = 1005
        val t = CumulativeThetaTree(n = 2, capacity = 10)
        t.setLeafOrder(intArrayOf(0, 1))
        t.activate(0, est = 0, taskEnergy = 1L)
        t.activate(1, est = 100, taskEnergy = 5L)
        assertEquals(1005L, t.envOfTheta())
    }

    @Test fun `deactivate matches never-activated state`() {
        // Build a tree of three tasks, then deactivate one. Result should match a tree
        // that was built with only the other two from the start.
        val full = CumulativeThetaTree(n = 3, capacity = 4)
        full.setLeafOrder(intArrayOf(0, 1, 2))
        full.activate(0, est = 1, taskEnergy = 5L)
        full.activate(1, est = 4, taskEnergy = 3L)
        full.activate(2, est = 8, taskEnergy = 2L)
        full.deactivate(1)

        val twoOnly = CumulativeThetaTree(n = 3, capacity = 4)
        twoOnly.setLeafOrder(intArrayOf(0, 1, 2))
        twoOnly.activate(0, est = 1, taskEnergy = 5L)
        twoOnly.activate(2, est = 8, taskEnergy = 2L)

        assertEquals(twoOnly.envOfTheta(), full.envOfTheta())
        assertEquals(twoOnly.energyOfTheta(), full.energyOfTheta())
    }

    @Test fun `leaf ordering matters for the envelope`() {
        // Same task set, different leaf orderings: the recurrence anchors at the
        // leftmost EST in the subtree, so EST-ascending leaf order is the one that
        // gives the correct envelope.
        val aEst = 0L
        val aE = 10L
        val bEst = 5L
        val bE = 4L
        val capacity = 1L

        val ordered = CumulativeThetaTree(n = 2, capacity = capacity)
        ordered.setLeafOrder(intArrayOf(0, 1))
        ordered.activate(0, aEst, aE)
        ordered.activate(1, bEst, bE)
        // env = max(env(a) + e(b), env(b)) = max((0+10)+4, 5+4) = max(14, 9) = 14
        assertEquals(14L, ordered.envOfTheta())

        val swapped = CumulativeThetaTree(n = 2, capacity = capacity)
        swapped.setLeafOrder(intArrayOf(1, 0))
        swapped.activate(0, aEst, aE)
        swapped.activate(1, bEst, bE)
        // Now L holds b (est=5, e=4), R holds a (est=0, e=10).
        // env = max((1*5+4) + 10, (1*0+10)) = max(19, 10) = 19
        // Different — and wrong as a cumulative envelope. Documenting the contract:
        // setLeafOrder is the caller's responsibility.
        assertEquals(19L, swapped.envOfTheta())
    }

    @Test fun `non-power-of-two task count works`() {
        // n=5 → leafBase=8, three padding leaves should stay inert.
        val t = CumulativeThetaTree(n = 5, capacity = 2)
        t.setLeafOrder(intArrayOf(0, 1, 2, 3, 4))
        t.activate(0, est = 0, taskEnergy = 1L)
        t.activate(1, est = 1, taskEnergy = 1L)
        t.activate(2, est = 2, taskEnergy = 1L)
        t.activate(3, est = 3, taskEnergy = 1L)
        t.activate(4, est = 4, taskEnergy = 1L)
        // For five unit-energy tasks at est 0..4, left-anchored at est=0 with all energies:
        // env = max chain → 0*2 + 1 + 1 + 1 + 1 + 1 = 5
        // also candidate: anchor at est=4 → 4*2 + 1 = 9
        // and anchor at est=3 with last two → 3*2 + 2 = 8
        // and anchor at est=2 with last three → 2*2 + 3 = 7
        // anchor at est=1 with last four → 1*2 + 4 = 6
        // anchor at est=0 with all five → 0 + 5 = 5
        // → max is 9.
        assertEquals(9L, t.envOfTheta())
        assertEquals(5L, t.energyOfTheta())
    }

    @Test fun `reactivation overwrites the prior contribution`() {
        val t = CumulativeThetaTree(n = 2, capacity = 1)
        t.setLeafOrder(intArrayOf(0, 1))
        t.activate(0, est = 0, taskEnergy = 100L)
        t.activate(0, est = 10, taskEnergy = 1L) // overwrite — was the same id
        t.activate(1, est = 20, taskEnergy = 1L)
        // Now: task 0 est=10 e=1, task 1 est=20 e=1.
        // env(0) = 10 + 1 = 11; env(1) = 20 + 1 = 21; env(theta) = max(11 + 1, 21) = 21.
        assertEquals(21L, t.envOfTheta())
        assertEquals(2L, t.energyOfTheta())
    }

    @Test fun `envIfActivated matches activate then deactivate`() {
        val t = CumulativeThetaTree(n = 4, capacity = 3)
        t.setLeafOrder(intArrayOf(0, 1, 2, 3))
        t.activate(0, est = 1, taskEnergy = 4L)
        t.activate(2, est = 6, taskEnergy = 5L)

        val predicted = t.envIfActivated(id = 3, est = 4, taskEnergy = 2L)
        t.activate(3, est = 4, taskEnergy = 2L)
        val actual = t.envOfTheta()
        t.deactivate(3)

        assertEquals(predicted, actual)
    }

    /** Brute-force reference: scan every non-empty subset of active tasks. O(2^n) so
     *  small inputs only. */
    private fun bruteEnv(capacity: Int, ests: IntArray, energies: LongArray, active: BooleanArray): Long {
        val n = ests.size
        var best = CumulativeThetaTree.NO_ENV
        val activeIdx = (0 until n).filter { active[it] }
        val m = activeIdx.size
        if (m == 0) return best
        for (mask in 1 until (1 shl m)) {
            var est = Int.MAX_VALUE
            var e = 0L
            for (b in 0 until m) {
                if (mask and (1 shl b) != 0) {
                    val id = activeIdx[b]
                    if (ests[id] < est) est = ests[id]
                    e += energies[id]
                }
            }
            val env = capacity.toLong() * est + e
            if (env > best) best = env
        }
        return best
    }

    @Test fun `randomized envelopes match brute force`() {
        val rng = Random(0x7C0FEE)
        repeat(200) {
            val n = 1 + rng.nextInt(7) // up to 7 tasks: 2^7 = 128 subsets
            val capacity = 1 + rng.nextInt(5)
            val ests = IntArray(n) { rng.nextInt(20) }
            val energies = LongArray(n) { rng.nextLong(1, 10) }
            val active = BooleanArray(n) { rng.nextBoolean() }

            // Build leaf positions = argsort of ests (ascending; ties broken by id).
            val order = (0 until n).sortedWith(compareBy({ id -> ests[id] }, { id -> id }))
            val leafPos = IntArray(n)
            for ((leafIdx, id) in order.withIndex()) leafPos[id] = leafIdx

            val tree = CumulativeThetaTree(n = n, capacity = capacity.toLong())
            tree.setLeafOrder(leafPos)
            for (id in 0 until n) if (active[id]) tree.activate(id, ests[id].toLong(), energies[id])

            val expected = bruteEnv(capacity, ests, energies, active)
            val got = tree.envOfTheta()
            assertEquals(
                expected,
                got,
                "mismatch: n=$n cap=$capacity ests=${ests.toList()} e=${energies.toList()} active=${active.toList()}",
            )

            val expectedE = (0 until n).filter { id -> active[id] }.sumOf { id -> energies[id] }
            assertEquals(expectedE, tree.energyOfTheta())
        }
    }

    // --- From SchedulingBoundsEventTest ---

    private class ExcludeOnFix(val src: Int, val dst: Int) :
        Factor,
        Propagator {
        override val boolVars: IntArray = IntArray(0)
        override val intVars: IntArray = intArrayOf(src, dst)

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val d = state.intDomains[src]
            return if (d.min == d.max) state.excludeIntValue(dst, d.min) else true
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ExcludeOnFix(intMap[src], intMap[dst])

        override fun structuralKey(): StructuralKey = error("test double has no structural key")

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        override fun asPropagator(): Propagator = this
        override fun asInvariant(): Invariant = object : Invariant {}
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
                .asPropagator().initialIntEventWatches,
            intArrayOf(0, 1),
        )
        assertBoundOnly(
            Diffn(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3), widths = longArrayOf(1, 1), heights = longArrayOf(1, 1))
                .asPropagator().initialIntEventWatches,
            intArrayOf(0, 1, 2, 3),
        )
        assertBoundOnly(
            Cumulative.unary(
                starts = intArrayOf(0, 1, 2),
                durations = longArrayOf(2, 1, 1),
            ).asPropagator().initialIntEventWatches,
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
        assertBoundOnly(reified.asPropagator().initialIntEventWatches, intArrayOf(1, 2))
    }

    @Test
    fun `disjunctive with interior holes punched mid-search enumerates exactly brute force`() {
        // 3 tasks (durations 2,1,1) over starts 0..3 must not overlap; a co-constraint carves var3's
        // fixed value out of starts 0 and 1 — punching interior holes the bound-only filter ignores.
        val durs = longArrayOf(2, 1, 1)
        for (seed in 1L..5L) {
            val factors = listOf<Factor>(
                Cumulative.unary(starts = intArrayOf(0, 1, 2), durations = durs),
                ExcludeOnFix(src = 3, dst = 0),
                ExcludeOnFix(src = 3, dst = 1),
            )
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 3) }, factors)
            val brute = HashSet<List<Int>>()
            for (s0 in 0..3) {
                for (s1 in 0..3) {
                    for (s2 in 0..3) {
                        for (c in 0..3) {
                            val s = intArrayOf(s0, s1, s2)
                            var ok = true
                            for (i in 0..2) {
                                for (j in i + 1..2) {
                                    val noOverlap = s[i] + durs[i] <= s[j] || s[j] + durs[j] <= s[i]
                                    if (!noOverlap) ok = false
                                }
                            }
                            if (ok && s0 != c && s1 != c) brute.add(listOf(s0, s1, s2, c))
                        }
                    }
                }
            }
            val found = BacktrackSolver(problem.bake())
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                .take(100_000).map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "seed=$seed: disjunctive + interior holes must match brute force")
        }
    }
}
