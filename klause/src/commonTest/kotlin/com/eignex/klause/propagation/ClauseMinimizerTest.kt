package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.IntArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ClauseMinimizerTest {

    @Test
    fun `a known leaf sibling should avoid unused historical reconstruction`() {
        val state = PropagationState(Problem(5, 0, emptyArray(), emptyList()), Assumptions.None)
        var expensiveBuilds = 0
        val graph = object : ReasonGraph {
            override fun universeSize(): Int = 5
            override fun levelOf(v: Int): Int = when (v) {
                1 -> 2
                4 -> 0
                else -> 1
            }
            override fun knownLeaf(v: Int): Boolean = v == 3
            override fun deferredReason(v: Int): Boolean = v == 2
            override fun antecedentsOf(v: Int): IntArray? = when (v) {
                0 -> intArrayOf(Lit.make(2, false), Lit.make(3, false))
                2 -> {
                    expensiveBuilds++
                    intArrayOf(Lit.make(4, false))
                }
                else -> null
            }
        }
        val minimizer = ClauseMinimizer(state, graph)
        val learned = IntArrayList().also {
            it.add(Lit.make(0, false))
            it.add(Lit.make(1, false))
        }
        val ordinary = minimizer.reduce(learned, 2).toIntArray()
        assertEquals(1, expensiveBuilds)
        expensiveBuilds = 0
        state.reasonPreflight = true

        val preflight = minimizer.reduce(learned, 2).toIntArray()

        assertContentEquals(ordinary, preflight)
        assertEquals(0, expensiveBuilds)
    }

    @Test
    fun `eager reasons should not trigger speculative sibling inspection`() {
        val state = PropagationState(Problem(4, 0, emptyArray(), emptyList()), Assumptions.None)
        state.reasonPreflight = true
        var siblingQueries = 0
        val graph = object : ReasonGraph {
            override fun universeSize(): Int = 4
            override fun levelOf(v: Int): Int = if (v == 1) 2 else 1
            override fun knownLeaf(v: Int): Boolean {
                siblingQueries++
                return v == 3
            }
            override fun antecedentsOf(v: Int): IntArray? = when (v) {
                0 -> intArrayOf(Lit.make(2, false), Lit.make(3, false))
                2 -> intArrayOf()
                else -> null
            }
        }
        val learned = IntArrayList().also {
            it.add(Lit.make(0, false))
            it.add(Lit.make(1, false))
        }

        val reduced = ClauseMinimizer(state, graph).reduce(learned, 2)

        assertContentEquals(learned.toIntArray(), reduced.toIntArray())
        assertEquals(0, siblingQueries)
    }

    @Test
    fun `redundancy should follow the reason graph of each conflict`() {
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
