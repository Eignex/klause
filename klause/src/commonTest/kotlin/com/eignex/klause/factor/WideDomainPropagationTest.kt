package com.eignex.klause.factor

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Table
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Each global propagator here has a variable whose domain is too large to walk — either non-enumerable
 * (span > 2^31, where the old scans would crash with NegativeArraySize) or merely far past the walk cap
 * (where they would hang for seconds). The span-safe guards must instead reason over the array positions /
 * cover / support / bounds — solving to a correct verdict without ever walking the domain.
 */
class WideDomainPropagationTest {

    // span 3 * 10^9 > 2^31, so the domain is non-enumerable — the wide path under test.
    private val wideHi = 3_000_000_000L

    // span 2 * 10^8 < 2^31, so the domain is enumerable, but far past the walk cap: the old `enumerable`
    // guard would walk its 200 million values (seconds), the cap routes it to the same span-safe fallback.
    private val bigHi = 200_000_000L

    private fun solve(numInt: Int, doms: Array<IntDomain>, vararg f: Factor): SolveResult =
        BacktrackSolver(Problem(0, numInt, intDomains = doms, factors = arrayOf(*f)).bake())
            .solve(BacktrackParams(randomSeed = 0L))

    @Test
    fun `element with a wide result domain pins to the selected constant`() {
        val r = solve(
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, wideHi)), // idx small, result wide
            Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false, indexOffset = 0),
        )
        val result = assertIs<SolveResult.Sat>(r).assignment.ints[1]
        assertTrue(result == 10L || result == 20L || result == 30L, "result must be a selected constant, was $result")
    }

    @Test
    fun `global cardinality counts a cover value over a wide domain variable`() {
        // A closed GCC forbids any value outside the cover {5}, so the wide x0 must be pinned to 5 by
        // restricting it to the (tiny) cover set — over x0's 3-billion span, without ever walking it.
        val r = solve(
            2,
            arrayOf(IntDomain(0, wideHi), IntDomain(5, 5)),
            GlobalCardinality(
                xs = intArrayOf(0, 1),
                cover = longArrayOf(5L),
                countLow = intArrayOf(1),
                countHigh = intArrayOf(2),
                closed = true,
            ),
        )
        assertEquals(5L, assertIs<SolveResult.Sat>(r).assignment.ints[0], "the wide variable must be pinned to 5")
    }

    @Test
    fun `nvalue over a wide domain variable is satisfiable`() {
        // count distinct of {x1 (wide), x2 = 7} into n; n in [1, 2] is satisfiable without walking x1.
        val r = solve(
            3,
            arrayOf(IntDomain(1, 2), IntDomain(0, wideHi), IntDomain(7, 7)),
            NValue(n = 0, xs = intArrayOf(1, 2)),
        )
        assertIs<SolveResult.Sat>(r)
    }

    @Test
    fun `table with a wide column pins to a supporting tuple`() {
        // Allowed tuples (5,7) and (8,9); x1 is fixed to 7, so x0 (wide) must take 5.
        val r = solve(
            2,
            arrayOf(IntDomain(0, wideHi), IntDomain(7, 7)),
            Table(intArrayOf(0, 1), longArrayOf(5, 7, 8, 9)),
        )
        val x0 = assertIs<SolveResult.Sat>(r).assignment.ints[0]
        assertEquals(5L, x0, "the wide column must be pinned to its supporting value")
    }

    @Test
    fun `element with a wide result and no valid value is unsat`() {
        // idx fixed to 0, result fixed away from arr[0] = 10 → unsatisfiable, decided without a span walk.
        val r = solve(
            2,
            arrayOf(IntDomain(0, 0), IntDomain(11, wideHi)),
            Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false, indexOffset = 0),
        )
        assertIs<SolveResult.Unsat>(r)
    }

    // The same propagators over a large-but-enumerable domain: the walk cap must route these to the
    // span-safe fallback too, so they stay fast instead of walking hundreds of millions of values.

    @Test
    fun `global cardinality over a large enumerable domain is not walked`() {
        val r = solve(
            2,
            arrayOf(IntDomain(0, bigHi), IntDomain(5, 5)),
            GlobalCardinality(
                xs = intArrayOf(0, 1),
                cover = longArrayOf(5L),
                countLow = intArrayOf(1),
                countHigh = intArrayOf(2),
                closed = true,
            ),
        )
        assertEquals(5L, assertIs<SolveResult.Sat>(r).assignment.ints[0], "the large variable must be pinned to 5")
    }

    @Test
    fun `nvalue over a large enumerable domain is satisfiable`() {
        val r = solve(
            3,
            arrayOf(IntDomain(1, 2), IntDomain(0, bigHi), IntDomain(7, 7)),
            NValue(n = 0, xs = intArrayOf(1, 2)),
        )
        assertIs<SolveResult.Sat>(r)
    }

    @Test
    fun `table with a large enumerable column pins to a supporting tuple`() {
        val r = solve(
            2,
            arrayOf(IntDomain(0, bigHi), IntDomain(7, 7)),
            Table(intArrayOf(0, 1), longArrayOf(5, 7, 8, 9)),
        )
        assertEquals(5L, assertIs<SolveResult.Sat>(r).assignment.ints[0], "the large column must be pinned")
    }

    @Test
    fun `element with a large enumerable result and no valid value is unsat`() {
        val r = solve(
            2,
            arrayOf(IntDomain(0, 0), IntDomain(11, bigHi)),
            Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false, indexOffset = 0),
        )
        assertIs<SolveResult.Unsat>(r)
    }
}
