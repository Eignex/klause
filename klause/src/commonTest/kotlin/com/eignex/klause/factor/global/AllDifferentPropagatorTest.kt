package com.eignex.klause.factor.global

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllDifferentPropagatorTest {

    @Test
    fun `endpoint bounds filtering should remove fixed clashes without overflowing`() {
        for (endpoint in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val problem = Problem(
                0, 2, arrayOf(IntDomain(endpoint, endpoint), IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
                arrayOf(AllDifferent(intArrayOf(0, 1), boundsConsistent = true)),
            )
            val state = PropagationState(problem, Assumptions.None)
            state.currentFactor = 0

            assertTrue(problem.propagators[0].propagate(state, 0))
            assertFalse(endpoint in state.intDomains[1])
        }
    }

    @Test
    fun `wide excepted globals should reject a repeated fixed nonexcepted value`() {
        val problem = Problem(
            0, 3, arrayOf(IntDomain(1, 1), IntDomain(1, 1), IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
            arrayOf(AllDifferent(intArrayOf(0, 1, 2), exceptSet = longArrayOf(0))),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.currentFactor = 0

        assertFalse(problem.propagators[0].propagate(state, 0))
    }

    @Test
    fun `hall interval should prune the bounds of other vars`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 6)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(1, 3),
                IntDomain(1, 3),
                IntDomain(1, 3),
                IntDomain(2, 5),
            ),
            factors = arrayOf<Factor>(factor),
        )
        val session = PropagationSession(problem)
        val v3Domain = session.intDomain(3)
        assertEquals(4, v3Domain.min, "$v3Domain")
        assertEquals(5, v3Domain.max, "$v3Domain")
    }

    @Test
    fun `hall interval should detect pigeonhole infeasibility`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val baked = problem.propagate()
        assertTrue(baked is PropagationResult.Unsat, "$baked")
    }

    @Test
    fun `singleton-taken value should be punched out of the interior of other domains`() {
        val factor = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 6)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(3, 3), IntDomain(1, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val session = PropagationSession(problem)
        val d1 = session.intDomain(1)
        assertEquals(1, d1.min)
        assertEquals(5, d1.max)
        assertEquals(4, d1.values.size, "$d1")
        assertTrue(3 !in d1, "$d1")
    }

    @Test
    fun `hall interval with a spanning intruder should punch every interior value`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 8)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(3, 5),
                IntDomain(3, 5),
                IntDomain(3, 5),
                IntDomain(1, 7),
            ),
            factors = arrayOf<Factor>(factor),
        )
        val session = PropagationSession(problem)
        val d3 = session.intDomain(3)
        assertEquals(1, d3.min)
        assertEquals(7, d3.max)
        for (h in 3..5) assertTrue(h.toLong() !in d3, "$h in $d3")
        for (k in intArrayOf(1, 2, 6, 7)) {
            assertTrue(k.toLong() in d3, "$k not in $d3")
        }
    }

    private fun stateWithAD(factor: AllDifferent, domains: Array<IntDomain>): PropagationState {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = domains.size,
            intDomains = domains,
            factors = arrayOf<Factor>(factor),
        )
        return PropagationState(problem, Assumptions.None)
    }

    @Test
    fun `a non-contiguous Hall set should prune an interior value`() {
        val d01 = IntDomain(1, 3).excludeValue(2)
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 3)
        val state = stateWithAD(factor, arrayOf(d01, d01, IntDomain(1, 3)))
        assertTrue(state.problem.propagators[0].propagate(state, factorId = 0))
        val d2 = state.intDomains[2]
        assertEquals(2, d2.min)
        assertEquals(2, d2.max)
    }

    @Test
    fun `a non-contiguous Hall conflict should be detected despite enough total values`() {
        val d13 = IntDomain(1, 3).excludeValue(2)
        val d24 = IntDomain(2, 4).excludeValue(3)
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 1, domainSize = 4)
        val state = stateWithAD(factor, arrayOf(d13, d13, d13, d24))

        val feasible = state.problem.propagators[0].propagate(state, factorId = 0)

        assertFalse(feasible)
    }

    @Test
    fun `a shareable value should survive non-contiguous Hall pruning`() {
        val d13 = IntDomain(1, 3).excludeValue(2)
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 4, exceptSet = longArrayOf(0))
        val state = stateWithAD(factor, arrayOf(d13, d13, IntDomain(0, 3)))

        val feasible = state.problem.propagators[0].propagate(state, factorId = 0)

        assertTrue(feasible)
        assertEquals(IntDomain(0, 2).excludeValue(1), state.intDomains[2])
    }

    private fun problemOf(factor: Factor, vararg domains: IntDomain) = Problem(
        numBoolVars = 0,
        numIntVars = domains.size,
        intDomains = arrayOf(*domains),
        factors = arrayOf(factor),
    )

    private fun failPinnedPair(state: PropagationState, a: Int, b: Int, value: Long): Boolean {
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(a, value) && state.tightenIntMax(a, value)) { "pin $a failed" }
        check(state.tightenIntMin(b, value) && state.tightenIntMax(b, value)) { "pin $b failed" }
        return state.problem.propagators[0].propagate(state, 0)
    }

    @Test
    fun `interleaved sessions should keep independent alldifferent conflict reasons`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 10)
        val problem = problemOf(factor, IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9))

        val control = PropagationState(problem, Assumptions.None)
        assertFalse(failPinnedPair(control, a = 0, b = 1, value = 3))
        val controlReason = problem.propagators[0].conflictReason(control, 0)

        val a = PropagationState(problem, Assumptions.None)
        assertFalse(failPinnedPair(a, a = 0, b = 1, value = 3))
        val b = PropagationState(problem, Assumptions.None)
        assertFalse(failPinnedPair(b, a = 1, b = 2, value = 7))
        val interleavedReason = problem.propagators[0].conflictReason(a, 0)

        assertContentEquals(controlReason, interleavedReason)
    }

    @Test
    fun `a matching prune should cite only how its hall set is confined`() {
        val (state, problem) = alldiffAfter(boundsConsistent = false)

        val cited = citedBounds(state, problem, 2)

        assertEquals(
            setOf(Triple(0, AtomKind.LE, 1L), Triple(1, AtomKind.LE, 1L), Triple(2, AtomKind.GE, 1L)),
            cited,
        )
    }

    @Test
    fun `a bounds-consistent move should cite only the hall interval it crossed`() {
        val (state, problem) = alldiffAfter(boundsConsistent = true)

        val cited = citedBounds(state, problem, 2)

        assertEquals(setOf(Triple(0, AtomKind.LE, 1L), Triple(1, AtomKind.LE, 1L)), cited)
    }

    private fun alldiffAfter(boundsConsistent: Boolean): Pair<PropagationState, Problem> {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(
                AllDifferent(IntArray(4) { it }, domainMin = 0, domainSize = 6, boundsConsistent = boundsConsistent),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 1) && state.tightenIntMax(1, 1) && state.excludeIntValue(3, 4))
        state.currentFactor = 0
        check(state.factorAt(0).propagate(state, 0))
        return state to problem
    }

    private fun citedBounds(state: PropagationState, problem: Problem, v: Int) =
        state.reasonOf(state.intMinAntecedents[v])!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }.toSet()

    @Test
    fun `alldifferent matching deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0xAD01)
        repeat(300) { iter ->
            val n = 4
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, 4) },
                factors = arrayOf<Factor>(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = 5)),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "alldiff#$iter") { state ->
                (0 until 6).all { state.excludeIntValue(rng.nextInt(n), rng.nextInt(5).toLong()) }
            }
        }
    }

    @Test
    fun `bounds-consistent alldifferent deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0xAD02)
        repeat(300) { iter ->
            val n = 4
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, 4) },
                factors = arrayOf<Factor>(
                    AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = 5, boundsConsistent = true),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "alldiff-bounds#$iter") { state ->
                (0 until 5).all {
                    val v = rng.nextInt(n)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(5).toLong())
                        1 -> state.tightenIntMin(v, rng.nextInt(3).toLong())
                        else -> state.tightenIntMax(v, 2L + rng.nextInt(3))
                    }
                }
            }
        }
    }

    @Test
    fun `alldifferent except zero deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0xAD04)
        repeat(150) { iter ->
            val n = 4
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, 4) },
                factors = arrayOf<Factor>(
                    AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = 5, exceptSet = longArrayOf(0)),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "alldiff-except0#$iter") { state ->
                (0 until 6).all { state.excludeIntValue(rng.nextInt(n), 1L + rng.nextInt(4)) }
            }
        }
    }
}
