package com.eignex.klause.bench.profile

import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExtensionalCycleProjectionTest {
    @Test
    fun `projected removal should count distinct private source rows beyond root filtering`() {
        val source = Table(intArrayOf(7, 2, 3), longArrayOf(99, 0, 0, 98, 0, 1, 97, 0, 1, 96, 1, 1))
        val domains = arrayOf<IntDomain?>(IntDomain(97, 99), null, null)
        val projection = assertNotNull(ExtensionalCycleProjection.project(source, listOf(2, 1), domains, { true }))
        val allowed = Table(intArrayOf(3, 2), longArrayOf(0, 0, 1, 1))

        val removed = ExtensionalCycleProjection.countUnsupported(projection, allowed, { true })

        assertContentEquals(intArrayOf(3, 2), projection.table.xs)
        assertEquals(2, removed)
    }

    @Test
    fun `interrupted source consumption should decline its count`() {
        val source = Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1))
        val projection = assertNotNull(ExtensionalCycleProjection.project(source, listOf(0, 1), arrayOf(null, null),
            { true }))
        var remaining = 2

        val removed = ExtensionalCycleProjection.countUnsupported(projection, projection.table, { remaining-- > 0 })

        assertNull(removed)
    }
}
