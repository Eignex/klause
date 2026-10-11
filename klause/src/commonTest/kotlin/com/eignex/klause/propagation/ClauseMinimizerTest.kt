package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.IntArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ClauseMinimizerTest {

    @Test
    fun `redundancy follows the reason graph of each conflict`() {
        val state = PropagationState(Problem(3, 0, emptyArray(), emptyList()), Assumptions.None)
        var reason: IntArray? = intArrayOf(Lit.make(2, false))
        val graph = object : ReasonGraph {
            override fun universeSize(): Int = 3

            override fun levelOf(v: Int): Int = when (v) {
                0 -> 1
                1 -> 2
                else -> 0
            }

            override fun antecedentsOf(v: Int): IntArray? = if (v == 0) reason else null
        }
        val minimizer = ClauseMinimizer(state, graph)
        val learned = IntArrayList().also {
            it.add(Lit.make(0, false))
            it.add(Lit.make(1, false))
        }
        assertContentEquals(intArrayOf(Lit.make(1, false)), minimizer.reduce(learned, 2).toIntArray())

        reason = null

        assertContentEquals(learned.toIntArray(), minimizer.reduce(learned, 2).toIntArray())
        reason = intArrayOf(Lit.make(2, false))
        assertContentEquals(intArrayOf(Lit.make(1, false)), minimizer.reduce(learned, 2).toIntArray())
    }
}
