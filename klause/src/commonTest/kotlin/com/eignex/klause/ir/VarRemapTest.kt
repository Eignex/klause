package com.eignex.klause.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VarRemapTest {
    @Test
    fun `source remapping owns its caller supplied tables`() {
        val booleans = intArrayOf(2)
        val integers = intArrayOf(3)
        val reals = intArrayOf(4)
        val mapping = VarRemap(booleans, integers, reals)

        booleans[0] = 0
        integers[0] = 0
        reals[0] = 0

        assertEquals(SourceBoolId(2), mapping.bool(SourceBoolId(0)))
        assertEquals(SourceIntId(3), mapping.int(SourceIntId(0)))
        assertEquals(SourceRealId(4), mapping.real(SourceRealId(0)))
    }

    @Test
    fun `absent variables have no typed target identity`() {
        val mapping = VarRemap(intArrayOf(-1), intArrayOf(-1), intArrayOf(-1))

        assertNull(mapping.bool(SourceBoolId(0)))
        assertNull(mapping.int(SourceIntId(0)))
        assertNull(mapping.real(SourceRealId(0)))
    }
}
