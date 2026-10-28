package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.ConflictReasonOracle
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
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
    fun `a high demand subset should conflict without compulsory parts`() {
        val problem = Problem(0, 4, arrayOf(
            IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(100, 100),
        ), arrayOf<Factor>(Cumulative(
            intArrayOf(0, 1, 2, 3), longArrayOf(2, 2, 2, 1), longArrayOf(2, 2, 2, 1), 3,
        )))

        val session = PropagationSession(PropagationProblem(problem), cumulativeUnarySubsets = true)

        assertTrue(session.isUnsatAtRoot)
    }

    @Test
    fun `a subset start bound should reach a linked row`() {
        val problem = Problem(0, 6, arrayOf(
            IntDomain(0, 5), IntDomain(0, 4), IntDomain(0, 3), IntDomain(0, 6),
            IntDomain(100, 100), IntDomain(0, 10),
        ), arrayOf(
            Cumulative(intArrayOf(4, 0, 1, 2, 3), longArrayOf(1, 1, 2, 3, 2), longArrayOf(1, 2, 2, 2, 2), 3),
            Linear(intArrayOf(1, 1), intArrayOf(3, 5), LinearOp.LE, 10),
        ))

        val session = PropagationSession(PropagationProblem(problem), cumulativeUnarySubsets = true)

        assertEquals(IntDomain(0, 4), session.intDomain(5))
    }

    @Test
    fun `the general capacity should still enforce low demand tasks`() {
        val problem = Problem(0, 5, arrayOf(
            IntDomain(0, 0), IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 0), IntDomain(0, 0),
        ), arrayOf<Factor>(Cumulative(
            intArrayOf(0, 1, 2, 3, 4), longArrayOf(2, 2, 2, 2, 2), longArrayOf(2, 2, 2, 1, 1), 3,
        )))

        val session = PropagationSession(PropagationProblem(problem), cumulativeUnarySubsets = true)

        assertTrue(session.isUnsatAtRoot)
    }

    @Test
    fun `subset deductions should exclude zero load tasks`() {
        val problem = Problem(0, 6, arrayOf(
            IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4), IntDomain(1, 1),
            IntDomain(1, 1), IntDomain(100, 100),
        ), arrayOf<Factor>(Cumulative(
            intArrayOf(0, 1, 2, 3, 4, 5), longArrayOf(2, 2, 2, 0, 2, 1), longArrayOf(2, 2, 2, 2, 0, 1), 3,
        )))

        val session = PropagationSession(PropagationProblem(problem), cumulativeUnarySubsets = true)

        assertFalse(session.isUnsatAtRoot)
    }

    @Test
    fun `subset projection should decline optional and live attributes`() {
        for (kind in listOf("presence", "duration", "demand", "capacity")) {
            val factor = Cumulative(
                intArrayOf(0, 1, 2, 3), longArrayOf(2, 2, 2, 1), longArrayOf(2, 2, 2, 1), 3,
                presents = if (kind == "presence") IntArray(4) { Lit.make(0, true) } else intArrayOf(),
                durationVars = if (kind == "duration") intArrayOf(4, 4, 4, 5) else intArrayOf(),
                resourceVars = if (kind == "demand") intArrayOf(4, 4, 4, 5) else intArrayOf(),
                capacityVar = if (kind == "capacity") 6 else -1,
            )

            val subset = factor.highDemandUnarySubset()

            assertNull(subset, kind)
        }
    }

    @Test
    fun `subset filtering should decline wide and unsafe time windows`() {
        for ((offset, upper) in listOf(0L to 10000L, (1L shl 40) + 1 to 6L)) {
            val problem = Problem(0, 5, arrayOf(
                IntDomain(offset, offset + 4), IntDomain(offset, offset + 4), IntDomain(offset, offset + 4),
                IntDomain(offset, offset + upper), IntDomain(100, 100),
            ), arrayOf<Factor>(Cumulative(
                intArrayOf(0, 1, 2, 3, 4), longArrayOf(2, 2, 2, 2, 1), longArrayOf(2, 2, 2, 2, 1), 3,
            )))

            val session = PropagationSession(PropagationProblem(problem), cumulativeUnarySubsets = true)

            assertFalse(session.isUnsatAtRoot)
            assertEquals(offset, session.intDomain(3).min)
        }
    }

    @Test
    fun `subset reasons should imply their bounds through aliases and holes`() {
        for (lower in listOf(false, true)) for (offset in listOf(-4L, 0L)) for (alias in listOf(false, true)) {
            val highMax = offset + if (lower) 3 else 4
            val candidateMax = offset + if (lower) 4 else 3
            val problem = Problem(0, 5, arrayOf(
                IntDomain(offset, highMax), IntDomain(offset, highMax), IntDomain(offset, highMax),
                IntDomain(offset, candidateMax), IntDomain(offset + 20, offset + 20),
            ), arrayOf<Factor>(Cumulative(
                intArrayOf(if (alias) 0 else 4, 2, 0, 1, 3), LongArray(5) { 1 }, longArrayOf(1, 2, 2, 2, 2), 3,
            )))

            PropagationReasonOracle.assertReasonsImply(problem, "subset $lower $offset $alias") { state ->
                state.cumulativeUnarySubsets = true
                (0 until 3).all {
                    if (lower) state.tightenIntMax(it, offset + 2) else state.tightenIntMin(it, offset + 2)
                } && state.excludeIntValue(3, offset + 1)
            }
        }
    }

    @Test
    fun `subset reasons should retain historical start bounds`() {
        val problem = Problem(0, 5, arrayOf(
            IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 8), IntDomain(100, 100),
        ), arrayOf<Factor>(Cumulative(
            intArrayOf(4, 0, 1, 2, 3), longArrayOf(1, 2, 2, 2, 2), longArrayOf(1, 2, 2, 2, 2), 3,
        )))
        val state = PropagationState(problem, Assumptions.None)
        state.cumulativeUnarySubsets = true
        state.undoLogging = true
        state.currentLevel = 1
        check((0 until 3).all { state.tightenIntMax(it, 4) })
        state.currentFactor = 0
        check(state.factorAt(0).propagate(state, 0))
        val recorded = state.intMinAntecedents[3]
        state.currentFactor = -1
        state.currentLevel = 2
        check(state.tightenIntMax(0, 2))

        val reason = requireNotNull(state.reasonOf(recorded))

        val bounds = reason.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            "${state.atoms.intVar[atom]} ${state.atoms.kind[atom]} ${state.atoms.threshold[atom]}"
        }
        assertTrue("0 LE 4" in bounds)
        assertFalse("0 LE 2" in bounds)
    }

    @Test
    fun `undo should discard interrupted subset deductions and filter a sibling`() {
        val problem = Problem(0, 5, arrayOf(
            IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 8), IntDomain(100, 100),
        ), arrayOf<Factor>(Cumulative(
            intArrayOf(4, 0, 1, 2, 3), longArrayOf(1, 2, 2, 2, 2), longArrayOf(1, 2, 2, 2, 2), 3,
        )))
        val state = PropagationState(problem, Assumptions.None)
        state.cumulativeUnarySubsets = true
        state.beginRootFixpoint().forEach { }
        while (state.rootFixpointPending) assertNull(state.advanceRootFixpoint(Cancellation.Never))
        state.undoLogging = true
        val root = state.mark()
        state.currentLevel = 1
        check((0 until 3).all { state.tightenIntMax(it, 4) })
        state.beginSeedFixpoint().forEach { }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = state.intDomains[3].min == 6L
            override fun workMeter(): WorkMeter = WorkMeter { }
        }

        assertNull(state.advanceRootFixpoint(token))

        assertTrue(state.rootFixpointPending)
        assertEquals(6L, state.intDomains[3].min)
        state.undoTo(root)
        state.currentLevel = 1
        check((0 until 3).all { state.tightenIntMin(it, 4) })
        assertNull(state.runToFixpoint(allFactors = false))
        assertFalse(state.runCancelled)
        assertEquals(IntDomain(0, 3), state.intDomains[3])
    }

    @Test
    fun `unrepresentable energy windows should leave demand bounds unchanged`() {
        for ((duration, capacity) in listOf(2L to Long.MAX_VALUE, Long.MAX_VALUE to 1L)) {
            val problem = Problem(0, 3, arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 1)),
                arrayOf<Factor>(Cumulative(
                    intArrayOf(0, 1), longArrayOf(duration, duration), longArrayOf(1, 1), capacity,
                    resourceVars = intArrayOf(2, 2),
                )))
            val state = PropagationState(problem, Assumptions.None)

            assertTrue(problem.propagators[0].propagate(state, 0))

            assertEquals(IntDomain(0, 1), state.intDomains[2])
        }
    }

    @Test
    fun `capacity ceilings should not overflow when the prefix surface fits its window`() {
        val problem = Problem(0, 3, arrayOf(
            IntDomain(0, 0), IntDomain(Long.MAX_VALUE - 1L, Long.MAX_VALUE - 1L), IntDomain(0, 1),
        ), arrayOf<Factor>(Cumulative(
            intArrayOf(0, 1), longArrayOf(Long.MAX_VALUE, 1), longArrayOf(1, 0), 1, capacityVar = 2,
        )))
        val state = PropagationState(problem, Assumptions.None)

        assertTrue(problem.propagators[0].propagate(state, 0))

        assertEquals(IntDomain(1, 1), state.intDomains[2])
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
