package com.eignex.klause.bench.profile

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopedComponentBoundsTest {
    private fun scope(v: Int, weight: Long = 1L, conditions: Map<Int, Boolean> = emptyMap()) =
        ComponentCostScope("source", conditions, listOf(v), listOf(listOf(0)), listOf(weight))

    @Test
    fun `exact child floors should close a strict global cutoff`() {
        val a = scope(0, 3L)
        val b = scope(1, 3L)
        val children = listOf(a, b).map { ComponentCostBounds.opening(it).withWitness(listOf(true))
            .refutedBelow(it, BigInteger.valueOf(3)) }
        val composition = ScopedComponentBounds(BigInteger.ZERO, listOf(a, b))

        assertTrue(composition.closes(BigInteger.valueOf(6), children))
        assertEquals(BigInteger.valueOf(6), composition.exact(children))
        assertFalse(composition.closes(BigInteger.valueOf(7), children))
    }

    @Test
    fun `threshold refutation should preserve source feasibility`() {
        val scope = scope(0, 3L)
        val child = ComponentCostBounds.opening(scope).refutedBelow(scope, BigInteger.valueOf(3))

        assertFalse(child.sourceInfeasible)
        assertNull(child.exact)
        assertEquals(BigInteger.valueOf(3), child.withWitness(listOf(true)).upper)
    }

    @Test
    fun `partial sibling witness should not supply an outside lower bound`() {
        val a = scope(0)
        val b = scope(1, 9L)
        val children = listOf(ComponentCostBounds.opening(a),
            ComponentCostBounds.opening(b).withWitness(listOf(true)))
        val composition = ScopedComponentBounds(BigInteger.ONE, listOf(a, b))

        assertEquals(BigInteger.valueOf(9), composition.improvementBudget(0, BigInteger.TEN, children))
        assertNull(composition.exact(children))
    }

    @Test
    fun `signed costs and fixed constants should compose beyond signed word range`() {
        val a = scope(0, Long.MIN_VALUE)
        val b = scope(1, Long.MIN_VALUE)
        val children = listOf(a, b).map { ComponentCostBounds.opening(it).withWitness(listOf(true)) }
        val composition = ScopedComponentBounds(BigInteger.valueOf(-7), listOf(a, b))

        assertEquals(BigInteger("-18446744073709551623"), composition.exact(children))
        assertEquals(BigInteger("9223372036854775825"),
            composition.improvementBudget(0, BigInteger.TEN, children))
    }

    @Test
    fun `refutation should reject a different condition or restriction`() {
        val original = scope(0, conditions = mapOf(7 to true))
        val child = ComponentCostBounds.opening(original)
        val altered = listOf(original.copy(conditions = mapOf(7 to false)),
            original.copy(clauses = emptyList()), original.copy(source = "another-source"),
            original.copy(variables = listOf(1)), original.copy(weights = listOf(2L)))

        for (scope in altered) assertFailsWith<IllegalArgumentException> {
            child.refutedBelow(scope, BigInteger.ONE)
        }
    }

    @Test
    fun `undo should not admit sibling facts`() {
        val left = scope(0, conditions = mapOf(7 to false))
        val right = scope(0, conditions = mapOf(7 to true))
        val children = listOf(ComponentCostBounds.opening(left))
        val composition = ScopedComponentBounds(BigInteger.ZERO, listOf(right))

        assertFailsWith<IllegalArgumentException> { composition.floor(children) }
    }

    @Test
    fun `composition should reject overlapping coordinates`() {
        assertFailsWith<IllegalArgumentException> {
            ScopedComponentBounds(BigInteger.ZERO, listOf(scope(0), scope(0)))
        }
    }

    @Test
    fun `witness should satisfy every clause and agree with checked bounds`() {
        val scope = scope(0)
        val bounds = ComponentCostBounds.opening(scope).refutedBelow(scope, BigInteger.ONE)

        assertFailsWith<IllegalArgumentException> { bounds.withWitness(listOf(false)) }
        assertFailsWith<IllegalArgumentException> {
            bounds.withWitness(listOf(true)).refutedBelow(scope, BigInteger.TWO)
        }
    }

    @Test
    fun `source refutation should remain distinct from an exact optimum`() {
        val scope = scope(0)
        val children = listOf(ComponentCostBounds.opening(scope).refutedSource(scope))
        val composition = ScopedComponentBounds(BigInteger.ZERO, listOf(scope))

        assertTrue(composition.closes(BigInteger.TEN, children))
        assertNull(composition.exact(children))
    }
}
