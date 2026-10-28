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
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.propagation.undoTo
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CumulativePropagatorTest {

    @Test
    fun `subwindow energy should exclude a ranged demand behind an early spectator`() {
        val problem = Problem(0, 6, arrayOf(
            IntDomain(0, 100), IntDomain(100, 102), IntDomain(100, 102), IntDomain(100, 102),
            IntDomain(1, 1), IntDomain(0, 1),
        ), arrayOf<Factor>(Cumulative(
            intArrayOf(0, 1, 2, 3), longArrayOf(1, 2, 2, 2), longArrayOf(1, 1, 1, 1), 1,
            resourceVars = intArrayOf(4, 4, 4, 5),
        )))
        val state = PropagationState(problem, Assumptions.None)

        assertTrue(problem.propagators[0].propagate(state, 0))

        assertEquals(IntDomain(0, 0), state.intDomains[5])
    }

    @Test
    fun `subwindow demand filtering should preserve holes and repeated demand aliases`() {
        val problem = Problem(0, 6, arrayOf(
            IntDomain(0, 100), IntDomain(100, 102), IntDomain(100, 102), IntDomain(100, 102),
            IntDomain(1, 1), IntDomain(0, 2).excludeValue(1),
        ), arrayOf<Factor>(Cumulative(
            intArrayOf(0, 1, 2, 3, 3), longArrayOf(1, 2, 2, 2, 2), longArrayOf(1, 1, 1, 2, 2), 1,
            resourceVars = intArrayOf(4, 4, 4, 5, 5),
        )))
        val state = PropagationState(problem, Assumptions.None)

        assertTrue(problem.propagators[0].propagate(state, 0))

        assertEquals(IntDomain(0, 0), state.intDomains[5])
    }

    @Test
    fun `unrepresentable window areas should decline demand filtering`() {
        val problem = Problem(0, 3, arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 1)),
            arrayOf<Factor>(Cumulative(
                intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(1, 1), Long.MAX_VALUE,
                resourceVars = intArrayOf(2, 2),
            )))
        val state = PropagationState(problem, Assumptions.None)

        assertTrue(problem.propagators[0].propagate(state, 0))

        assertEquals(IntDomain(0, 1), state.intDomains[2])
    }

    @Test
    fun `interrupted ranged demand windows should rebuild without retained partial windows`() {
        val n = 32
        val problem = Problem(0, n + 1, Array(n + 1) {
            if (it == n) IntDomain(0, 1) else IntDomain(it.toLong(), 64L + it)
        }, arrayOf<Factor>(Cumulative(
            IntArray(n) { it }, LongArray(n) { 1L }, LongArray(n) { 1L }, n.toLong(),
            resourceVars = IntArray(n) { n },
        )))
        val state = PropagationState(problem, Assumptions.None)
        state.beginRootFixpoint().forEach { }
        var polls = 0

        assertNull(state.advanceRootFixpoint(Cancellation { ++polls >= 10 }))

        assertTrue(state.rootFixpointPending)
        while (state.rootFixpointPending) assertNull(state.advanceRootFixpoint(Cancellation.Never))
        assertEquals(IntDomain(0, 1), state.intDomains[n])
    }

    @Test
    fun `subwindow reasons should remain sound with shared starts demands and presences`() {
        for (sharedStart in listOf(false, true)) {
            val problem = Problem(1, 6, arrayOf(
                IntDomain(0, 4), IntDomain(4, 6), IntDomain(4, 6), IntDomain(4, 6),
                IntDomain(1, 1), IntDomain(0, 1),
            ), arrayOf<Factor>(Cumulative(
                intArrayOf(0, 1, if (sharedStart) 1 else 2, 3), longArrayOf(1, 2, 2, 2),
                longArrayOf(1, 1, 1, 1), 1, presents = IntArray(4) { Lit.make(0, true) },
                resourceVars = intArrayOf(4, 4, 4, 5),
            )))

            PropagationReasonOracle.assertReasonsImply(problem, "subwindow-shared-$sharedStart") { state ->
                state.pinBool(0, true)
            }
        }
    }

    @Test
    fun `subwindow reasons should omit an early spectator and survive later tightening`() {
        val problem = Problem(0, 6, arrayOf(
            IntDomain(0, 4), IntDomain(4, 8), IntDomain(4, 6), IntDomain(4, 6),
            IntDomain(1, 1), IntDomain(0, 1),
        ), arrayOf<Factor>(Cumulative(
            intArrayOf(0, 1, 2, 3), longArrayOf(1, 2, 2, 2), longArrayOf(1, 1, 1, 1), 1,
            resourceVars = intArrayOf(4, 4, 4, 5),
        )))
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        val root = state.mark()
        state.currentLevel = 1
        state.currentFactor = 0
        assertTrue(state.tightenIntMax(1, 6))
        assertTrue(problem.propagators[0].propagate(state, 0))
        val antecedent = state.intMaxAntecedents[5]
        val before = assertNotNull(state.reasonOf(antecedent)).toList()
        assertTrue(state.tightenIntMin(1, 5))
        val after = assertNotNull(state.reasonOf(antecedent)).toList()

        assertEquals(before, after)
        assertFalse(after.any { state.atoms.intVar[Lit.variable(it)] == 0 })
        state.undoTo(root)
        assertEquals(IntDomain(0, 1), state.intDomains[5])
    }

    @Test
    fun `subwindow exclusions should follow narrowed participating windows`() {
        val problem = Problem(0, 6, arrayOf(
            IntDomain(0, 4), IntDomain(4, 8), IntDomain(4, 6), IntDomain(4, 6),
            IntDomain(1, 1), IntDomain(0, 1),
        ), arrayOf<Factor>(Cumulative(
            intArrayOf(0, 1, 2, 3), longArrayOf(1, 2, 2, 2), longArrayOf(1, 1, 1, 1), 1,
            resourceVars = intArrayOf(4, 4, 4, 5),
        )))

        PropagationReasonOracle.assertReasonsImply(problem, "subwindow-narrowed") { state ->
            state.tightenIntMax(1, 6)
        }
    }

    @Test
    fun `subwindow energy should omit unknown absent and zero duration tasks`() {
        for (presence in listOf<Boolean?>(null, false, true)) {
            val problem = Problem(1, 6, arrayOf(
                IntDomain(0, 4), IntDomain(4, 6), IntDomain(4, 6), IntDomain(4, 6),
                IntDomain(1, 1), IntDomain(0, 1),
            ), arrayOf<Factor>(Cumulative(
                intArrayOf(0, 1, 2, 3, 3), longArrayOf(1, 2, 2, 2, 0), longArrayOf(1, 1, 1, 1, 1), 1,
                presents = intArrayOf(Lit.make(0, true), Lit.make(0, true), Lit.make(0, true),
                    Lit.make(0, true), Lit.make(0, false)),
                resourceVars = intArrayOf(4, 4, 4, 5, 4),
            )))
            val state = PropagationState(problem, Assumptions.None)
            if (presence != null) assertTrue(state.pinBool(0, presence))

            assertTrue(problem.propagators[0].propagate(state, 0))

            assertEquals(if (presence == true) 0L else 1L, state.intDomains[5].max)
        }
    }

    @Test
    fun `interrupted cumulative windows should rebuild before completing their root`() {
        for (limit in listOf(4, 10, 30)) {
            val n = 48
            val problem = Problem(0, n, Array(n) { IntDomain(0, 100L + it) }, arrayOf<Factor>(
                Cumulative(IntArray(n) { it }, LongArray(n) { 1L }, LongArray(n) { 1L }, n.toLong()),
            ))
            val state = PropagationState(problem, Assumptions.None)
            state.beginRootFixpoint().forEach { }
            var polls = 0

            assertNull(state.advanceRootFixpoint(Cancellation { ++polls >= limit }))

            assertTrue(state.rootFixpointPending)
            assertFalse(state.runCancelled)
            assertEquals(limit, polls)
            while (state.rootFixpointPending) assertNull(state.advanceRootFixpoint(Cancellation.Never))
            for (v in 0 until n) assertEquals(IntDomain(0, 100L + v), state.intDomains[v])
        }
    }

    @Test
    fun `undo should discard an interrupted cumulative bound and preserve sibling filtering`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 4) }, arrayOf<Factor>(
            Cumulative(intArrayOf(0, 1, 2), longArrayOf(2, 2, 2), longArrayOf(1, 1, 1), 2),
        ))
        val state = PropagationState(problem, Assumptions.None)
        state.beginRootFixpoint().forEach { }
        while (state.rootFixpointPending) assertNull(state.advanceRootFixpoint(Cancellation.Never))
        state.undoLogging = true
        val root = state.mark()
        state.currentLevel = 1
        check(state.tightenIntMax(0, 0))
        check(state.tightenIntMax(1, 0))
        state.beginSeedFixpoint().forEach { }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = state.intDomains[2].min == 2L
            override fun workMeter(): WorkMeter = WorkMeter { }
        }

        assertNull(state.advanceRootFixpoint(token))

        assertTrue(state.rootFixpointPending)
        assertEquals(2L, state.intDomains[2].min)
        state.undoTo(root)
        state.currentLevel = 1
        check(state.tightenIntMin(0, 3))
        check(state.tightenIntMin(1, 3))
        assertNull(state.runToFixpoint(allFactors = false))
        assertFalse(state.runCancelled)
        assertEquals(IntDomain(0, 2), state.intDomains[2])
    }

    @Test
    fun `cumulative deductions over optional tasks should be implied by their reasons`() {
        val rng = Random(0xC0A1)
        repeat(100) { iter ->
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
    fun `energetic reasoning should cap a resource height that cannot fit the shared window`() {
        // Window energy 3 * 3 minus task 0's 2 * 2 leaves 5 over 3 units, so height1 <= 1.
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
        assertEquals(1, state.intDomains[3].max)
    }

    @Test
    fun `profile height pruning should beat the energetic area bound at a compulsory peak`() {
        // A height-3 peak under capacity 5 caps height0 at 2; the energetic bound alone gives 4.
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
        assertEquals(2, state.intDomains[4].max)
    }

    @Test
    fun `cumulative deductions over variable demands and capacity should be implied by their reasons`() {
        val rng = Random(0xC0C)
        repeat(60) { iter ->
            val n = 2
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
                (0 until 4).all {
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
    fun `a height bound should cite only the compulsory parts stacked on its peak`() {
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
    fun `time table domain wipeout should cite the tasks blocking every start`() {
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
    fun `profile-overload reason should be a sound witness that omits non-covering tasks`() {
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
        assertTrue(state.setInt(0, 0) && state.setInt(1, 0) && state.setInt(2, 8))
        state.currentFactor = 0
        assertFalse(state.factorAt(0).propagate(state, 0))

        val reason = state.factorAt(0).conflictReason(state, 0)
        assertTrue(reason != null && reason.isNotEmpty())
        for (lit in reason) {
            assertTrue(state.litFalse(lit), "lit=$lit")
        }
        assertEquals(2, reason.size)
    }

    @Test
    fun `overload check should detect energy infeasibility that time-tabling misses`() {
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
        assertTrue(baked is PropagationResult.Unsat, "$baked")
    }

    @Test
    fun `propagator should shave a start that would overlap a mandatory part`() {
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
        assertTrue(result is PropagationResult.Implied, "$result")
        assertEquals(4, result.ints[1])
    }

    @Test
    fun `edge-finding should tighten a start past where time-tabling can reach`() {
        // No task has a compulsory part; theta = {A, B} has envelope 8, so est(C) >= ceil(8 / 3) = 3.
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
        assertTrue(result is PropagationResult.Implied, "$result")
        assertEquals(3, result.intMinOrNullCompat(2))
    }

    @Test
    fun `edge-finding should tighten a latest start without compulsory parts`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(8, 10), IntDomain(8, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(
                Cumulative(
                    starts = intArrayOf(0, 1, 2),
                    durations = longArrayOf(2, 2, 2),
                    resources = longArrayOf(2, 2, 3),
                    capacity = 3,
                ),
            ),
        )

        val result = problem.propagate()

        assertIs<PropagationResult.Implied>(result)
        assertEquals(7, result.intMaxOrNullCompat(2))
    }

    @Test
    fun `edge-finding should not push a task that can run before the cluster`() {
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
        assertTrue(ok is PropagationResult.Implied, "$ok")
    }

    @Test
    fun `edge-finding should not push a task past a subset with no leftover energy`() {
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
    fun `zero-duration task should contribute no usage`() {
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
    fun `zero capacity with a positive task should be infeasible`() {
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
}
