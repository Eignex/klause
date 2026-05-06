package com.eignex.klause.solver

import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.IntLeq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sample-loop edge cases: determinism under a fixed seed, graceful exhaustion when the
 * window blocks every remaining solution, and that the rolling-window distance check
 * actually enforces `minHammingDistance` against window members.
 */
class SampleLoopTest {

    @Test
    fun sameSeedYieldsIdenticalSequence() {
        val problem = exactlyOneOver4()
        val solver = LocalSearchSolver(problem)
        val a = solver.enumerate(LocalSearchParams(maxFlips = 10_000, randomSeed = 42, recentWindow = 2)).take(10).toList()
        val b = solver.enumerate(LocalSearchParams(maxFlips = 10_000, randomSeed = 42, recentWindow = 2)).take(10).toList()
        assertEquals(a, b, "Same Solver, same seed, same params → identical sequence")
    }

    @Test
    fun differentSeedsExploreDifferentSequences() {
        // Soft check: at least one element differs across two seeds. Two random sequences over
        // 4 solutions colliding entirely is astronomically unlikely.
        val problem = exactlyOneOver4()
        val solver = LocalSearchSolver(problem)
        val a = solver.enumerate(LocalSearchParams(maxFlips = 10_000, randomSeed = 1, recentWindow = 2)).take(8).toList()
        val b = solver.enumerate(LocalSearchParams(maxFlips = 10_000, randomSeed = 9, recentWindow = 2)).take(8).toList()
        assertTrue(a != b, "Different seeds should produce different sample sequences")
    }

    @Test
    fun finiteFlipBudgetTerminatesEvenWhenWindowBlocksAllSolutions() {
        // ExactlyOne over 3 vars has 3 solutions; window=10 holds every one as soon as we've
        // seen them, so the solver loops on rejected samples until maxFlips runs out. The
        // sequence must end (rather than hang) and yield at most 3 samples.
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val solver = LocalSearchSolver(problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000L, randomSeed = 0L, recentWindow = 10)).take(20).toList()
        assertTrue(samples.size <= 3, "At most 3 distinct solutions exist; got ${samples.size}")
        assertEquals(samples.toSet().size, samples.size, "Window=10 forbids duplicates")
    }

    @Test
    fun minHammingDistanceIsEnforcedAgainstWindowMembers() {
        // ExactlyOne over 4 vars has 4 solutions. Adjacent solutions differ in exactly 2 bool
        // positions (e.g. (T,F,F,F) → (F,T,F,F) is distance 2). Setting minHammingDistance=3
        // means no two consecutive samples in the window can be adjacent solutions.
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val solver = LocalSearchSolver(problem)
        val samples = solver.enumerate(LocalSearchParams(
            maxFlips = 30_000L, randomSeed = 5L,
            minHammingDistance = 3, recentWindow = 8,
        )).take(8).toList()
        // Every pair within the window-eligible run must satisfy distance ≥ 3.
        for (i in samples.indices) {
            for (j in (i + 1) until samples.size) {
                val d = hamming(samples[i], samples[j])
                assertTrue(d >= 3, "Sample $i vs $j distance $d violates minHammingDistance=3")
            }
        }
    }

    @Test
    fun mixedBoolIntDistanceCountsBothKinds() {
        // 1 bool var + 1 int var with domain [0,3]. Solutions: (true, 0..3) — int can take 4
        // values, so 4 solutions total. Distance counts bool-flip + int-value differences;
        // (true, 1) vs (true, 0) is distance 1 (only int differs).
        val factors = listOf(
            Clause(intArrayOf(Lit.make(0, true))),
            IntLeq(intVar = 0, bound = 3),
        )
        val problem = Problem(numBoolVars = 1, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)), factors = factors)
        val solver = LocalSearchSolver(problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000L, randomSeed = 11L,
            minHammingDistance = 1, recentWindow = 8)).take(4).toList()
        // Only 4 solutions, all should be reachable (and unique under window=8).
        assertEquals(samples.toSet().size, samples.size)
        for (s in samples) assertTrue(s.bools[0], "Clause forces bool 0 = true")
    }

    @Test
    fun zeroDistanceBypassesWindow() {
        // minHammingDistance=0 disables the distance check; window setting is ignored. We
        // expect the requested count to be produced, including duplicates.
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val solver = LocalSearchSolver(problem)
        val samples = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 0L,
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
