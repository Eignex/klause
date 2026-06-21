package com.eignex.klause.solver.factor.bool

import com.eignex.klause.model.PbOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PseudoBooleanSumFactorTest {

    @Test
    fun `pbHolds LE satisfied at and below bound`() {
        assertTrue(pbHolds(3L, PbOp.LE, 5))
        assertTrue(pbHolds(5L, PbOp.LE, 5))
        assertFalse(pbHolds(6L, PbOp.LE, 5))
    }

    @Test
    fun `pbHolds GE satisfied at and above bound`() {
        assertTrue(pbHolds(5L, PbOp.GE, 5))
        assertTrue(pbHolds(6L, PbOp.GE, 5))
        assertFalse(pbHolds(4L, PbOp.GE, 5))
    }

    @Test
    fun `pbHolds EQ exact match only`() {
        assertTrue(pbHolds(5L, PbOp.EQ, 5))
        assertFalse(pbHolds(4L, PbOp.EQ, 5))
        assertFalse(pbHolds(6L, PbOp.EQ, 5))
    }

    @Test
    fun `pbDistance LE is zero when satisfied and overage when violated`() {
        assertEquals(0L, pbDistance(3L, PbOp.LE, 5))
        assertEquals(0L, pbDistance(5L, PbOp.LE, 5))
        assertEquals(2L, pbDistance(7L, PbOp.LE, 5))
    }

    @Test
    fun `pbDistance GE is zero when satisfied and shortfall when violated`() {
        assertEquals(0L, pbDistance(5L, PbOp.GE, 5))
        assertEquals(0L, pbDistance(7L, PbOp.GE, 5))
        assertEquals(3L, pbDistance(2L, PbOp.GE, 5))
    }

    @Test
    fun `pbDistance EQ is absolute deviation`() {
        assertEquals(0L, pbDistance(5L, PbOp.EQ, 5))
        assertEquals(2L, pbDistance(7L, PbOp.EQ, 5))
        assertEquals(2L, pbDistance(3L, PbOp.EQ, 5))
    }
}
