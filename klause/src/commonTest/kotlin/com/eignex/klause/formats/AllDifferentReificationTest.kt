package com.eignex.klause.formats

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AllDifferentReificationTest {

    private class Sink : CnfLowering {
        override val factors = mutableListOf<Factor>()
        override var trueLitCache: Int = -1
        val intDomains = mutableListOf<IntDomain>()
        var numBools = 0

        override fun newBool(): Int = numBools++

        fun newInt(lo: Long, hi: Long): Int {
            intDomains += IntDomain(lo, hi)
            return intDomains.size - 1
        }
    }

    /** The encoding over [n] variables sharing the value window `[0, values - 1]`, with the reified
     *  literal's variable id returned alongside the problem. */
    private fun encode(n: Int, values: Int): Pair<Problem, Int> {
        val sink = Sink()
        val vars = IntArray(n) { sink.newInt(0L, (values - 1).toLong()) }
        val lit = sink.reifyAllDifferentWitness(vars, 0L, values) { lo, hi -> sink.newInt(lo, hi) }
        val problem = Problem(
            numBoolVars = sink.numBools,
            numIntVars = sink.intDomains.size,
            intDomains = sink.intDomains.toTypedArray(),
            factors = sink.factors.toTypedArray(),
        )
        return problem to Lit.variable(lit)
    }

    private fun allDifferent(values: List<Long>): Boolean = values.toSet().size == values.size

    private fun checkAgainstOracle(n: Int, values: Int) {
        val (problem, flag) = encode(n, values)
        val solutions = BruteForceSolver(problem.bake()).enumerate(BruteForceParams(randomSeed = 0L)).toList()
        val realized = mutableSetOf<List<Long>>()
        for (s in solutions) {
            val terms = (0 until n).map { s.ints[it] }
            realized += terms
            assertEquals(
                allDifferent(terms),
                s.bools[flag],
                "reified literal disagrees with all-different on $terms",
            )
        }
        val expected = (0 until n).fold(listOf(emptyList<Long>())) { acc, _ ->
            acc.flatMap { prefix -> (0 until values).map { prefix + it.toLong() } }
        }
        assertEquals(expected.toSet(), realized, "some assignment of the terms has no model")
    }

    @Test
    fun `reified literal agrees with all different over three terms`() {
        checkAgainstOracle(n = 3, values = 3)
    }

    @Test
    fun `reified literal agrees with all different over four terms`() {
        checkAgainstOracle(n = 4, values = 4)
    }

    @Test
    fun `reified literal is false throughout when the terms cannot be distinct`() {
        checkAgainstOracle(n = 4, values = 3)
    }

    @Test
    fun `encoding size is independent of the term count`() {
        val small = encode(n = 4, values = 4).first
        val large = encode(n = 40, values = 40).first
        assertEquals(small.factors.size, large.factors.size)
        assertEquals(small.numBoolVars, large.numBoolVars)
        assertEquals(small.numIntVars - 4, large.numIntVars - 40)
    }

    @Test
    fun `window size declines a span wider than an int`() {
        assertNull(allDifferentWindowSize(0L, Int.MAX_VALUE.toLong()))
        assertNull(allDifferentWindowSize(Long.MIN_VALUE, Long.MAX_VALUE))
        assertNull(allDifferentWindowSize(5L, 4L))
        assertEquals(3, allDifferentWindowSize(-1L, 1L))
    }
}
