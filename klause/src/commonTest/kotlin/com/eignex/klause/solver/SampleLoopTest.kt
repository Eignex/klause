package com.eignex.klause.solver

import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.IntLeq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleLoopTest {

    @Test
    fun `same seed yields identical sequence`() {
        val problem = exactlyOneOver4()
        val solver = LocalSearchSolver(problem)
        val a = solver.enumerate(LocalSearchParams(maxFlips = 10_000, randomSeed = 42, recentWindow = 2)).take(10).toList()
        val b = solver.enumerate(LocalSearchParams(maxFlips = 10_000, randomSeed = 42, recentWindow = 2)).take(10).toList()
        assertEquals(a, b, "Same Solver, same seed, same params → identical sequence")
    }

    @Test
    fun `different seeds explore different sequences`() {

        val problem = exactlyOneOver4()
        val solver = LocalSearchSolver(problem)
        val a = solver.enumerate(LocalSearchParams(maxFlips = 10_000, randomSeed = 1, recentWindow = 2)).take(8).toList()
        val b = solver.enumerate(LocalSearchParams(maxFlips = 10_000, randomSeed = 9, recentWindow = 2)).take(8).toList()
        assertTrue(a != b, "Different seeds should produce different sample sequences")
    }

    @Test
    fun `finite flip budget terminates even when window blocks all solutions`() {

        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val solver = LocalSearchSolver(problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000L, randomSeed = 0L, recentWindow = 10)).take(20).toList()
        assertTrue(samples.size <= 3, "At most 3 distinct solutions exist; got ${samples.size}")
        assertEquals(samples.toSet().size, samples.size, "Window=10 forbids duplicates")
    }

    @Test
    fun `min hamming distance is enforced against window members`() {

        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val solver = LocalSearchSolver(problem)
        val samples = solver.enumerate(LocalSearchParams(
            maxFlips = 30_000L, randomSeed = 5L,
            minHammingDistance = 3, recentWindow = 8,
        )).take(8).toList()

        for (i in samples.indices) {
            for (j in (i + 1) until samples.size) {
                val d = hamming(samples[i], samples[j])
                assertTrue(d >= 3, "Sample $i vs $j distance $d violates minHammingDistance=3")
            }
        }
    }

    @Test
    fun `mixed bool int distance counts both kinds`() {

        val factors = listOf(
            Clause(intArrayOf(Lit.make(0, true))),
            IntLeq(intVar = 0, bound = 3),
        )
        val problem = Problem(numBoolVars = 1, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)), factors = factors)
        val solver = LocalSearchSolver(problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000L, randomSeed = 11L,
            minHammingDistance = 1, recentWindow = 8)).take(4).toList()

        assertEquals(samples.toSet().size, samples.size)
        for (s in samples) assertTrue(s.bools[0], "Clause forces bool 0 = true")
    }

    @Test
    fun `zero distance bypasses window`() {

        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val solver = LocalSearchSolver(problem)
        val samples = solver.samples(LocalSearchParams(maxFlips = 10_000L, randomSeed = 0L,
            minHammingDistance = 0, recentWindow = 16)).take(10).toList()
        assertEquals(10, samples.size, "minHammingDistance=0 should allow duplicates freely")
    }

    private fun exactlyOneOver4(): Problem {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        return Problem(4, 0, emptyArray(), listOf(factor))
    }

    private fun hamming(a: Sample, b: Sample): Int {
        var d = 0
        for (i in a.bools.indices) if (a.bools[i] != b.bools[i]) d++
        for (i in a.ints.indices) if (a.ints[i] != b.ints[i]) d++
        return d
    }
}
