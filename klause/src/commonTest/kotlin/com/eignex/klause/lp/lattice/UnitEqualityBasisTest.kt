package com.eignex.klause.lp.lattice

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitEqualityBasisTest {
    @Test
    fun `a three coordinate equality can combine with another row`() {
        val basis = UnitEqualityBasis(Cancellation.Never)
        basis.add(intArrayOf(0, 1, 2), longArrayOf(1, 1, -1), 3, 7)
        basis.add(intArrayOf(1, 3), longArrayOf(1, 1), 5, 8)

        val reduced = assertNotNull(basis.reduce(intArrayOf(0, 2, 3), longArrayOf(1, -1, -1), -2))

        assertTrue(reduced.columns.isEmpty())
        assertEquals(0L, reduced.bound)
        assertEquals(setOf(7, 8), reduced.support.toSet())
    }

    @Test
    fun `an inconsistent equality combination retains its nonzero bound`() {
        val basis = UnitEqualityBasis(Cancellation.Never)
        basis.add(intArrayOf(0, 1, 2), longArrayOf(1, 1, -1), 3, 0)

        val reduced = assertNotNull(basis.reduce(intArrayOf(0, 1, 2), longArrayOf(1, 1, -1), 4))

        assertTrue(reduced.columns.isEmpty())
        assertEquals(1L, reduced.bound)
        assertEquals(listOf(0), reduced.support.toList())
    }

    @Test
    fun `a nonunit leading coefficient supplies no pivot`() {
        val basis = UnitEqualityBasis(Cancellation.Never)
        basis.add(intArrayOf(0, 1, 2), longArrayOf(2, 1, -1), 3, 0)

        val reduced = assertNotNull(basis.reduce(intArrayOf(0, 1, 2), longArrayOf(2, 1, -1), 3))

        assertTrue(reduced.columns.isNotEmpty())
        assertTrue(reduced.support.isEmpty())
    }

    @Test
    fun `a later earlier pivot can use the already stored suffix`() {
        val basis = UnitEqualityBasis(Cancellation.Never)
        basis.add(intArrayOf(1, 2), longArrayOf(1, 1), 3, 0)
        basis.add(intArrayOf(0, 1, 2), longArrayOf(-1, 1, -1), 4, 1)

        val reduced = assertNotNull(basis.reduce(intArrayOf(0, 1, 2), longArrayOf(-1, 1, -1), 4))

        assertTrue(reduced.columns.isEmpty())
        assertEquals(0L, reduced.bound)
        assertEquals(setOf(0, 1), reduced.support.toSet())
    }

    @Test
    fun `mutating input and returned arrays cannot change a stored pivot`() {
        val columns = intArrayOf(0, 1, 2)
        val coefficients = longArrayOf(1, 1, -1)
        val basis = UnitEqualityBasis(Cancellation.Never)
        val added = assertNotNull(basis.add(columns, coefficients, 3, 0))
        columns[0] = 8
        coefficients[0] = 99
        added.columns[0] = 9
        added.coefficients[0] = 100
        added.support[0] = 20

        val reduced = assertNotNull(basis.reduce(intArrayOf(0, 1, 2), longArrayOf(1, 1, -1), 3))

        assertTrue(reduced.columns.isEmpty())
        assertEquals(0L, reduced.bound)
        assertEquals(listOf(0), reduced.support.toList())
    }

    @Test
    fun `an overflowing elimination withholds its result without changing the basis`() {
        val basis = UnitEqualityBasis(Cancellation.Never)
        basis.add(intArrayOf(0, 1, 2), longArrayOf(1, Long.MAX_VALUE, 1), Long.MAX_VALUE, 0)

        val overflow = basis.reduce(intArrayOf(0), longArrayOf(2), 0)
        val unchanged = assertNotNull(basis.reduce(intArrayOf(0, 1, 2),
            longArrayOf(1, Long.MAX_VALUE, 1), Long.MAX_VALUE))

        assertNull(overflow)
        assertTrue(unchanged.columns.isEmpty())
        assertEquals(0L, unchanged.bound)
    }

    @Test
    fun `an unnegatable bound cannot become a unit pivot`() {
        val basis = UnitEqualityBasis(Cancellation.Never)

        basis.add(intArrayOf(0, 1, 2), longArrayOf(-1, 1, 1), Long.MIN_VALUE, 0)

        val reduced = assertNotNull(basis.reduce(intArrayOf(0, 1, 2), longArrayOf(-1, 1, 1), Long.MIN_VALUE))
        assertTrue(reduced.columns.isNotEmpty())
        assertTrue(reduced.support.isEmpty())
    }

    @Test
    fun `a cancelled basis cannot retain an equation`() {
        val basis = UnitEqualityBasis(Cancellation { true })

        val result = basis.add(intArrayOf(0, 1, 2), longArrayOf(1, 1, -1), 3, 0)

        assertNull(result)
    }

    @Test
    fun `an unused equality is absent from the reduction support`() {
        val basis = UnitEqualityBasis(Cancellation.Never)
        basis.add(intArrayOf(0, 1, 2), longArrayOf(1, 1, -1), 3, 0)
        basis.add(intArrayOf(3, 4, 5), longArrayOf(1, 1, -1), 7, 1)

        val reduced = assertNotNull(basis.reduce(intArrayOf(0, 1, 2), longArrayOf(1, 1, -1), 3))

        assertEquals(listOf(0), reduced.support.toList())
    }

    @Test
    fun `basis work is charged to the containing cancellation token`() {
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged > 0L
            override fun workMeter() = WorkMeter { charged += it }
        }
        val basis = UnitEqualityBasis(stop)

        val result = basis.add(intArrayOf(0, 1, 2), longArrayOf(1, 1, -1), 3, 0)

        assertNull(result)
        assertTrue(charged > 0L)
    }

    @Test
    fun `an oversized source row supplies no equation`() {
        val basis = UnitEqualityBasis(Cancellation.Never)

        val result = basis.add(IntArray(4_097) { it }, LongArray(4_097) { 1L }, 0, 0)

        assertNull(result)
        val unchanged = assertNotNull(basis.reduce(intArrayOf(0), longArrayOf(1), 0))
        assertTrue(unchanged.columns.isNotEmpty())
        assertTrue(unchanged.support.isEmpty())
    }
}
