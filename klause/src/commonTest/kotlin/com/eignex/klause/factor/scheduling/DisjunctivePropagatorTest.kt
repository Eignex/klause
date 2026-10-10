package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.ConflictReasonOracle
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisjunctivePropagatorTest {

    @Test
    fun `disjunctive deductions over optional tasks are implied by their reasons`() {
        val rng = Random(0xD15)
        repeat(400) { iter ->
            val n = 3
            val problem = Problem(
                numBoolVars = n,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, 6) },
                factors = arrayOf<Factor>(
                    Cumulative.unary(
                        starts = IntArray(n) { it },
                        durations = LongArray(n) { 2L + rng.nextInt(2) },
                        presents = IntArray(n) { Lit.make(it, true) },
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "disjunctive#$iter") { state ->
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
    fun `disjunctive deductions over duration variables are implied by their reasons`() {
        val rng = Random(0xD16)
        repeat(300) { iter ->
            val n = 3
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2 * n,
                intDomains = Array(2 * n) { if (it < n) IntDomain(0, 6) else IntDomain(1, 3) },
                factors = arrayOf<Factor>(
                    Cumulative.unary(
                        starts = IntArray(n) { it },
                        durations = LongArray(n) { 1L },
                        durationVars = IntArray(n) { n + it },
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "disjunctive-durations#$iter") { state ->
                (0 until n).all {
                    val d = 1L + rng.nextInt(3)
                    state.tightenIntMin(n + it, d) && state.tightenIntMax(n + it, d)
                } &&
                    (0 until 3).all {
                        val v = rng.nextInt(n)
                        when (rng.nextInt(3)) {
                            0 -> state.excludeIntValue(v, rng.nextInt(7).toLong())
                            1 -> state.tightenIntMin(v, 1L + rng.nextInt(4))
                            else -> state.tightenIntMax(v, rng.nextInt(5).toLong())
                        }
                    }
            }
        }
    }

    @Test
    fun `an edge-finding bound cites only the cluster it follows`() {
        // Tasks 0 and 1 must both finish by 5; task 2 cannot fit among them, so it starts at 4 or later. Only the
        // cluster's deadlines are cited: task 2's own earliest start is the root's, and task 3, pushed to 7, plays
        // no part.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 9) },
            factors = arrayOf<Factor>(
                Cumulative.unary(starts = intArrayOf(0, 1, 2, 3), durations = longArrayOf(2, 2, 2, 2)),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 3) && state.tightenIntMax(1, 3) && state.tightenIntMax(2, 4))
        check(state.tightenIntMin(3, 7))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMinAntecedents[2])!!.map { state.atoms.intVar[Lit.variable(it)] }.toSet()
        assertEquals(setOf(0, 1), cited)
    }

    @Test
    fun `energetic-window conflict reason cites only the tasks packed into the overloaded window`() {
        // Four unit tasks over starts [0,9] (globally schedulable). A decision squeezes tasks 0,1,2
        // to start ≤ 1, packing three unit jobs into the length-2 window [0,2) — an edge-finding
        // overload with no compulsory overlap, so the mutual-precedence/profile paths miss it. The
        // sharp reason must cite only tasks 0,1,2, never the idle task 3 (start ≥ 5), and be entailed.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(
                Cumulative.unary(starts = intArrayOf(0, 1, 2, 3), durations = longArrayOf(1, 1, 1, 1)),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 1))
        check(state.tightenIntMax(1, 1))
        check(state.tightenIntMax(2, 1))
        check(state.tightenIntMin(3, 5)) // idle task tightened so a coarse reason would cite it
        state.currentFactor = 0
        assertFalse(state.factorAt(0).propagate(state, 0))
        val reason = state.factorAt(0).conflictReason(state, 0)!!
        val citedVars = reason.map { state.atoms.intVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(citedVars.all { it in setOf(0, 1, 2) }, "reason must cite only the packed tasks, got $citedVars")
        assertTrue(3 !in citedVars, "idle task 3 must not appear in the sharp reason")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "disjunctive-energetic")
    }

    @Test
    fun `mutual-precedence conflict reason is a sound nogood citing only the two tasks`() {
        // Three duration-3 tasks over starts [0,9] (globally schedulable at 0,3,6). A decision
        // squeezes task 0 and task 1 both to start ≤ 2: each would then have to run strictly after
        // the other — a contradiction implied by just those two starts. The sharp reason must cite
        // only vars 0 and 1, never the idle task 2 (tightened to start≥6), and must be entailed.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(Cumulative.unary(starts = intArrayOf(0, 1, 2), durations = longArrayOf(3, 3, 3))),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 2))
        check(state.tightenIntMax(1, 2))
        check(state.tightenIntMin(2, 6)) // idle task tightened so a coarse reason would cite it
        state.currentFactor = 0
        assertFalse(state.factorAt(0).propagate(state, 0))
        val reason = state.factorAt(0).conflictReason(state, 0)!!
        val citedVars = reason.map { state.atoms.intVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(
            citedVars.all { it == 0 || it == 1 },
            "reason must cite only the two conflicting tasks, got $citedVars",
        )
        assertTrue(2 !in citedVars, "idle task 2 must not appear in the sharp reason")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "disjunctive-precedence")
    }

    @Test
    fun `overload conflict reason is a sound nonempty bound-atom witness`() {
        // Two unit-resource tasks, durations 3 and 3, starts in [0, 5]. A level-1 decision
        // squeezes both starts' max down to 2, so each task occupies a window [est, 2+3) = [0, 5)
        // of length 5 while their combined energy is 6 > 5 — an energetic overload.
        val factor = Cumulative.unary(starts = intArrayOf(0, 1), durations = longArrayOf(3, 3))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        assertTrue(state.tightenIntMax(0, 2) && state.tightenIntMax(1, 2), "squeeze starts to [0, 2]")
        state.currentFactor = 0
        assertTrue(!state.factorAt(0).propagate(state, 0), "the squeezed window must overload (energy 6 > 5)")

        val reason = state.factorAt(0).conflictReason(state, 0)
        assertTrue(reason != null && reason.isNotEmpty(), "overload must yield a non-empty clause-form reason")
        for (lit in reason) {
            assertTrue(state.litFalse(lit), "every reason literal must be false at conflict time, lit=$lit")
        }
    }

    @Test
    fun `pairwise detectable precedence pushes the earliest start`() {
        val factor = Cumulative.unary(starts = intArrayOf(0, 1), durations = longArrayOf(3, 1))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        val unsatPin = problem.propagate(Assumptions(ints = mapOf(1 to 2)))
        assertTrue(unsatPin is PropagationResult.Unsat, "pinning task 1 at t=2 must fail; got $unsatPin")
        val okPin = problem.propagate(Assumptions(ints = mapOf(1 to 3)))
        assertTrue(okPin is PropagationResult.Implied, "pinning task 1 at t=3 should succeed; got $okPin")
    }

    @Test
    fun `edge-finding pins a task forced to come last by an energetic overflow`() {
        // Three duration-2 tasks. Tasks 0 and 1 have dom [0, 3], task 2 has dom [0, 4].
        // Total demand = 6 time units; the tight cluster {0, 1} alone fits in [0, 5]
        // (est=0, lct=5, sum_dur=4 — slack 1). Adding task 2 (dur 2) into the union
        // makes est + dur_2 + sum_dur({0,1}) = 0 + 2 + 4 = 6 > lct({0,1}) = 5 — edge-
        // finding fires and pushes start_2.min ≥ est({0,1}) + sum_dur({0,1}) = 4. Since
        // dom_2 = [0, 4], task 2 collapses to the singleton {4} → Implied.ints[2] = 4.
        // Pairwise detectable precedences alone cannot derive this because no single
        // pair triggers (est_i + dur_i ≤ lst_j for every i, j pair in [0, 3] dom).
        val factor = Cumulative.unary(starts = intArrayOf(0, 1, 2), durations = longArrayOf(2, 2, 2))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "expected propagation success; got $result")
        assertEquals(4, result.ints[2], "edge-finding should pin task 2's start to 4; implied=${result.ints}")
    }

    @Test
    fun `pure pairwise infeasibility is caught`() {
        val factor = Cumulative.unary(starts = intArrayOf(0, 1), durations = longArrayOf(1, 1))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(5, 5), IntDomain(5, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Unsat, "two tasks pinned at same time must fail; got $result")
    }
}
