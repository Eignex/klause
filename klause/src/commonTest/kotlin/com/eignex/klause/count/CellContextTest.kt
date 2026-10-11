package com.eignex.klause.count

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CellContextTest {
    @Test
    fun `a hashed cell caps distinct projections rather than full witnesses`() {
        val model = Problem(5, 0, emptyArray<IntDomain>(), emptyArray()).bake()
        val context = CellContext.resolve(model, intArrayOf(0, 1), null)
        val hash = Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true)), 0)

        val cell = context.countCell(listOf(hash), cap = 2)

        assertTrue(cell.complete)
        assertFalse(cell.capped)
        assertEquals(setOf(listOf(0L, 0L), listOf(1L, 1L)), cell.representatives.map(context::projectionKey).toSet())
    }

    @Test
    fun `mixed projections match direct integer and Boolean semantics`() {
        val model = Problem(
            2, 2, Array(2) { IntDomain(0, 2) },
            arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 2),
            ),
        ).bake()
        val context = CellContext.resolve(model, intArrayOf(0), intArrayOf(0))
        val expected = (0L..1L).flatMap { bool -> (0L..2L).map { value -> listOf(bool, value) } }.toSet()

        val cell = context.countCell(emptyList(), cap = 6)

        assertTrue(cell.complete)
        assertEquals(expected, cell.representatives.map(context::projectionKey).toSet())
        assertTrue(cell.representatives.all { it.ints.sum() == 2L })
    }

    @Test
    fun `a distinct projection cap supplies a lower bound without claiming exhaustion`() {
        val model = Problem(4, 0, emptyArray<IntDomain>(), emptyArray()).bake()
        val context = CellContext.resolve(model, intArrayOf(0, 1), null)

        val cell = context.countCell(emptyList(), cap = 1)

        assertTrue(cell.capped)
        assertFalse(cell.complete)
        assertEquals(2, cell.count)
    }

    @Test
    fun `the largest integer cap permits a complete small cell`() {
        val model = Problem(1, 0, emptyArray<IntDomain>(), emptyArray()).bake()
        val context = CellContext.resolve(model, intArrayOf(0), null)

        val cell = context.countCell(emptyList(), cap = Int.MAX_VALUE)

        assertTrue(cell.complete)
        assertFalse(cell.capped)
        assertEquals(2, cell.count)
    }

    @Test
    fun `an interrupted projected cell cannot be treated as exact`() {
        val model = Problem(4, 0, emptyArray<IntDomain>(), emptyArray()).bake()
        val context = CellContext.resolve(model, intArrayOf(0, 1), null)

        val cell = context.countCell(emptyList(), cap = 4, maxDecisions = 0)

        assertFalse(cell.complete)
        assertFalse(cell.capped)
    }

    @Test
    fun `the empty projection has one representative when any source witness exists`() {
        val model = Problem(4, 0, emptyArray<IntDomain>(), emptyArray()).bake()
        val context = CellContext.resolve(model, intArrayOf(), intArrayOf())

        val cell = context.countCell(emptyList(), cap = 1)

        assertTrue(cell.complete)
        assertEquals(1, cell.count)
    }
}
