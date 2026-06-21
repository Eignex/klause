package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pilot for the typed int-event substrate (#622): the bounds-consistency [AllDifferent] is driven by
 * advisor subscription instead of occurrence-list wakeup. Because [boundsAllDifferentFilter] reads
 * only each variable's `min`/`max`, the factor subscribes to `LB_RAISED`/`UB_LOWERED` and is *not*
 * woken by interior `VALUE_REMOVED` carves — a sound selectivity win (an interior hole can never
 * change a bounds-consistent deduction). The risk is that skipping those wakes drops a needed
 * deduction; the enumeration test punches interior holes *during search* via a co-constraint and
 * checks the full solution set still equals brute force.
 */
class AllDifferentBoundsEventTest {

    /** When [src] is fixed, carve its value out of [dst] — used to punch interior holes into the
     *  bounds-AllDifferent's variables mid-search. Plain occurrence-list wakeup (no event
     *  subscription), so it always fires and the holes really do appear. */
    private class ExcludeOnFix(val src: Int, val dst: Int) : Factor {
        override val boolVars: IntArray = IntArray(0)
        override val intVars: IntArray = intArrayOf(src, dst)

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val d = state.intDomains[src]
            return if (d.min == d.max) state.excludeIntValue(dst, d.min) else true
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ExcludeOnFix(intMap[src], intMap[dst])

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
    }

    @Test
    fun `bounds-consistent alldifferent subscribes to exactly the bound events`() {
        val ad = AllDifferent(intArrayOf(5, 7), domainMin = 0, domainSize = 10, boundsConsistent = true)
        val watches = ad.asPropagator().initialIntEventWatches
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
    fun `full-gac alldifferent keeps occurrence-list wakeup`() {
        // Default (full GAC) needs every value removal, so it must not opt into the bound-only path.
        assertNull(
            AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 4).asPropagator().initialIntEventWatches,
        )
    }

    @Test
    fun `bounds-alldiff is dropped from occurrence wakeup on its vars`() {
        val ad = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 4, boundsConsistent = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 3) },
            factors = listOf(ad),
        )
        for (v in 0..2) {
            assertContains(problem.intOccurrences[v].toList(), 0, "factor still mentions var $v")
            assertFalse(
                problem.nonIntEventWatcherIntOccurrences[v].contains(0),
                "subscribed bounds-alldiff must be off the occurrence-wakeup list for var $v",
            )
        }
    }

    @Test
    fun `bounds-alldiff with interior holes punched mid-search enumerates exactly brute force`() {
        // x0,x1,x2 pairwise distinct (::bounds) over 0..3, plus x3 over 0..3 whose fixing carves its
        // value out of x0 and x1 — punching interior holes the bounds-alldiff is not woken for. The
        // enumerated set must still equal brute force, proving the skipped wakes lose no soundness.
        val hi = 3
        val adVars = intArrayOf(0, 1, 2)
        for (seed in 1L..6L) {
            val factors = listOf<Factor>(
                AllDifferent(adVars, domainMin = 0, domainSize = hi + 1, boundsConsistent = true),
                ExcludeOnFix(src = 3, dst = 0),
                ExcludeOnFix(src = 3, dst = 1),
            )
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 4,
                intDomains = Array(4) { IntDomain(0, hi) },
                factors = factors,
            )
            val brute = HashSet<List<Int>>()
            val base = hi + 1
            for (m in 0 until base * base * base * base) {
                val a = m % base
                val b = (m / base) % base
                val c = (m / (base * base)) % base
                val d = m / (base * base * base)
                if (a != b && a != c && b != c && a != d && b != d) brute.add(listOf(a, b, c, d))
            }
            val found = BacktrackSolver(problem)
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                .take(100_000).map { it.ints.toList() }.toHashSet()
            assertEquals(
                brute,
                found,
                "seed $seed: bounds-alldiff + interior-hole co-constraint must match brute force",
            )
        }
    }
}
