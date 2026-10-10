package com.eignex.klause.factor.global

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllDifferentPropagatorTest {

    @Test
    fun `bounds-consistent alldifferent subscribes to exactly the bound events`() {
        val ad = AllDifferent(intArrayOf(5, 7), domainMin = 0, domainSize = 10, boundsConsistent = true)
        val watches = ad.propagatorProjection().initialIntEventWatches
        assertTrue(watches != null, "bounds-consistent alldifferent must opt into typed events")
        val pairs = watches.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet()
        assertEquals(
            setOf(
                5 to IntEvent.LB_RAISED,
                5 to IntEvent.UB_LOWERED,
                7 to IntEvent.LB_RAISED,
                7 to IntEvent.UB_LOWERED,
            ),
            pairs,
        )
        assertFalse(
            watches.any { IntEvent.kindOf(it) == IntEvent.VALUE_REMOVED || IntEvent.kindOf(it) == IntEvent.FIXED },
            "bounds consistency reads only min/max, so it must not subscribe to interior or fixed events",
        )
    }

    @Test
    fun `hall interval prunes other vars bounds via propagation`() {
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
        assertEquals(
            4,
            v3Domain.min,
            "v3's min should be tightened to 4 (Hall set [1,3] forbids 2,3 for v3); got $v3Domain",
        )
        assertEquals(
            5,
            v3Domain.max,
            "v3's max should remain 5; got $v3Domain",
        )
    }

    @Test
    fun `hall interval detects infeasibility - pigeonhole over interval`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val baked = problem.propagate()
        assertTrue(
            baked is PropagationResult.Unsat,
            "expected Unsat from Hall pigeonhole; got $baked",
        )
    }

    @Test
    fun `singleton-taken value punched out of interior of other domains`() {
        val factor = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 6)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(3, 3), IntDomain(1, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val session = PropagationSession(problem)
        val d1 = session.intDomain(1)
        assertEquals(1, d1.min, "v1's min should remain 1 (3 is interior)")
        assertEquals(5, d1.max, "v1's max should remain 5 (3 is interior)")
        assertEquals(4, d1.values.size, "v1 should have 4 values after punching out 3; got $d1")
        assertTrue(3 !in d1, "v1 should no longer contain 3")
        assertTrue(2 in d1 && 4 in d1, "v1 should still contain 2 and 4")
    }

    @Test
    fun `hall interval with spanning intruder punches every interior value`() {
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
        for (h in 3..5) assertTrue(h.toLong() !in d3, "value $h should be a hole, got $d3")
        for (k in intArrayOf(1, 2, 6, 7)) {
            assertTrue(k.toLong() in d3, "value $k should remain; got $d3")
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
    fun `AllDifferent non-contiguous Hall set prunes interior value`() {
        // x0, x1 ∈ {1, 3} (sparse). x2 ∈ {1, 2, 3}. {1, 3} is a non-contiguous Hall set
        // monopolised by x0+x1 — Régin must prune both 1 and 3 from x2, leaving {2}. Bound
        // consistency only checks contiguous intervals and misses this.
        val d01 = IntDomain(1, 3).excludeValue(2) // sparse {1, 3}
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 3)
        val state = stateWithAD(factor, arrayOf(d01, d01, IntDomain(1, 3)))
        assertTrue(state.problem.propagators[0].propagate(state, factorId = 0))
        val d2 = state.intDomains[2]
        assertEquals(2, d2.min)
        assertEquals(2, d2.max)
    }

    @Test
    fun `re-fire from the GAC fixpoint prunes nothing further`() {
        // x0, x1 in {1, 3}; x2 in {1, 2, 3}. Régin prunes 1 and 3 from x2 → {2}. A second fire on
        // the unchanged domains must hit the fast path and return without touching anything.
        val sparse = IntDomain(1, 3).excludeValue(2)
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 3)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(sparse, sparse, IntDomain(1, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        assertTrue(problem.propagators[0].propagate(state, factorId = 0))
        val afterFirst = state.intDomains[2]
        assertEquals(2, afterFirst.min)
        assertEquals(2, afterFirst.max)
        // Re-fire: identical domains → fast-path hit → same refs, no further change.
        assertTrue(problem.propagators[0].propagate(state, factorId = 0))
        assertTrue(afterFirst === state.intDomains[2], "fast-path re-fire must not rewrite the domain")
    }

    private fun problemOf(factor: Factor, vararg domains: IntDomain) = Problem(
        numBoolVars = 0,
        numIntVars = domains.size,
        intDomains = arrayOf(*domains),
        factors = arrayOf(factor),
    )

    /** Drive [state] into an AllDifferent conflict by pinning two vars to [value]. */
    private fun failPinnedPair(state: PropagationState, a: Int, b: Int, value: Long): Boolean {
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(a, value) && state.tightenIntMax(a, value)) { "pin $a failed" }
        check(state.tightenIntMin(b, value) && state.tightenIntMax(b, value)) { "pin $b failed" }
        return state.problem.propagators[0].propagate(state, 0)
    }

    @Test
    fun `interleaved sessions keep independent alldifferent conflict reasons`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 10)
        val problem = problemOf(factor, IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9))

        // Control: session A alone — pin vars 0 and 1 to the same value and capture the reason.
        val control = PropagationState(problem, Assumptions.None)
        assertFalse(failPinnedPair(control, a = 0, b = 1, value = 3), "pinned pair must conflict")
        val controlReason = problem.propagators[0].conflictReason(control, 0)

        // Interleaved: session A fails as above, then session B (same factor object) fails on a
        // DIFFERENT pair, then A's reason is read. With factor-level scratch B's failure
        // overwrites A's and this assertion breaks.
        val a = PropagationState(problem, Assumptions.None)
        assertFalse(failPinnedPair(a, a = 0, b = 1, value = 3))
        val b = PropagationState(problem, Assumptions.None)
        assertFalse(failPinnedPair(b, a = 1, b = 2, value = 7))
        val interleavedReason = problem.propagators[0].conflictReason(a, 0)

        assertContentEquals(
            controlReason,
            interleavedReason,
            "session A's conflict reason must be unaffected by session B's later failure",
        )
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
    fun `sort lexless and symmetric-alldiff subscribe to only bound events`() {
        assertBoundOnly(
            Sort(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3)).propagatorProjection().initialIntEventWatches,
            intArrayOf(0, 1, 2, 3),
        )
        assertBoundOnly(
            LexLess(
                xs = intArrayOf(0, 1),
                ys = intArrayOf(2, 3),
                strict = true,
            ).propagatorProjection().initialIntEventWatches,
            intArrayOf(0, 1, 2, 3),
        )
        assertBoundOnly(
            SymmetricAllDifferent(
                xs = intArrayOf(0, 1, 2),
                indexOffset = 0,
            ).propagatorProjection().initialIntEventWatches,
            intArrayOf(0, 1, 2),
        )
    }

    @Test
    fun `a matching prune cites only how its hall set is confined`() {
        // x0 and x1 take {0, 1} between them, so x2 leaves both; x3's hole at 4 plays no part. x2's lower bound
        // climbs past 0 and then 1, so its final move also cites the bound it left.
        val (state, problem) = alldiffAfter(boundsConsistent = false)

        val cited = citedBounds(state, problem, 2)

        assertEquals(
            setOf(Triple(0, AtomKind.LE, 1L), Triple(1, AtomKind.LE, 1L), Triple(2, AtomKind.GE, 1L)),
            cited,
        )
    }

    @Test
    fun `a bounds-consistent move cites only the hall interval it crossed`() {
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
    fun `alldifferent matching deductions are implied by their reasons under carved holes`() {
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
    fun `bounds-consistent alldifferent deductions are implied by their reasons under carved holes`() {
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
    fun `alldifferent except zero deductions are implied by their reasons under carved holes`() {
        val rng = Random(0xAD04)
        repeat(300) { iter ->
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
