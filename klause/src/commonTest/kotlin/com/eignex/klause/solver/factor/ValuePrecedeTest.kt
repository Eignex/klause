package com.eignex.klause.solver.factor

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `value_precede(s, t, xs)` (#432). The non-negotiable property is **soundness**: the native
 * propagator must accept exactly the assignments where the first occurrence of `s` precedes the
 * first occurrence of `t` (or `t` is absent). Each test enumerates the whole assignment space via
 * the engine and compares it to the brute-force feasible set.
 */
class ValuePrecedeTest {

    /** Brute semantics: the first position holding `s` or `t` must hold `s` (or neither occurs). */
    private fun satisfied(s: Int, t: Int, assign: List<Int>): Boolean {
        for (v in assign) {
            if (v == s) return true
            if (v == t) return false
        }
        return true
    }

    /** Every assignment over [domains] that satisfies `value_precede(s, t, ·)`. */
    private fun bruteFeasible(s: Int, t: Int, domains: Array<IntRange>): Set<List<Int>> {
        val out = HashSet<List<Int>>()
        val cur = IntArray(domains.size) { domains[it].first }
        while (true) {
            if (satisfied(s, t, cur.toList())) out.add(cur.toList())
            var i = 0
            while (i < domains.size) {
                cur[i]++
                if (cur[i] <= domains[i].last) break
                cur[i] = domains[i].first
                i++
            }
            if (i == domains.size) break
        }
        return out
    }

    private fun enumerated(s: Int, t: Int, domains: Array<IntRange>): Set<List<Int>> {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = domains.size,
            intDomains = Array(domains.size) { IntDomain(domains[it].first, domains[it].last) },
            factors = listOf(ValuePrecede(s, t, IntArray(domains.size) { it })),
        )
        return BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).map { it.ints.toList() }.toSet()
    }

    private fun assertExact(s: Int, t: Int, domains: Array<IntRange>) {
        assertEquals(
            bruteFeasible(s, t, domains),
            enumerated(s, t, domains),
            "value_precede($s,$t) over ${domains.toList()}",
        )
    }

    @Test
    fun `enumeration matches the feasible set on small instances`() {
        assertExact(0, 1, arrayOf(0..1, 0..1, 0..1))
        assertExact(0, 1, arrayOf(0..2, 0..2, 0..2))
        // s / t do not span the whole domain.
        assertExact(1, 2, arrayOf(0..2, 0..2, 0..2))
        // a position that can be neither s nor t in the middle.
        assertExact(2, 5, arrayOf(2..5, 0..1, 2..5))
    }

    @Test
    fun `forces s before a fixed t`() {
        // x1 is pinned to t=1, so s=0 must occur strictly before it ⇒ x0 = 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(1, 1)),
            factors = listOf(ValuePrecede(s = 0, t = 1, xs = intArrayOf(0, 1))),
        )
        assertEquals(
            setOf(listOf(0, 1)),
            BacktrackSolver(problem).enumerate(BacktrackParams()).map { it.ints.toList() }.toSet(),
        )
    }

    @Test
    fun `t is impossible when s never can occur`() {
        // s=5 is not in any domain, so t=1 may never appear.
        assertExact(5, 1, arrayOf(0..1, 0..1))
    }

    @Test
    fun `random instances match the feasible set`() {
        val rng = Random(0x432)
        repeat(300) {
            val n = 2 + rng.nextInt(3) // 2..4
            val domains = Array(n) {
                val lo = rng.nextInt(3) // 0..2
                lo..(lo + rng.nextInt(3)) // span 0..2
            }
            val s = rng.nextInt(4)
            var t = rng.nextInt(4)
            if (t == s) t = (t + 1) % 4
            assertExact(s, t, domains)
        }
    }
}
