package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheckedLongOverflowExceptionTest {

    @Test
    fun `add sub and mul should match plain arithmetic in range`() {
        assertEquals(7L, addExact(3L, 4L))
        assertEquals(-1L, subExact(3L, 4L))
        assertEquals(12L, mulExact(3L, 4L))
        assertEquals(0L, mulExact(0L, Long.MAX_VALUE))
    }

    @Test
    fun `addExact should detect overflow`() {
        assertFailsWith<CheckedLongOverflowException> { addExact(Long.MAX_VALUE, 1L) }
        assertFailsWith<CheckedLongOverflowException> { addExact(Long.MIN_VALUE, -1L) }
    }

    @Test
    fun `subExact should detect overflow`() {
        assertFailsWith<CheckedLongOverflowException> { subExact(Long.MIN_VALUE, 1L) }
        assertFailsWith<CheckedLongOverflowException> { subExact(Long.MAX_VALUE, -1L) }
    }

    @Test
    fun `mulExact should detect overflow`() {
        assertFailsWith<CheckedLongOverflowException> { mulExact(Long.MAX_VALUE, 2L) }
        assertFailsWith<CheckedLongOverflowException> { mulExact(Long.MIN_VALUE, -1L) }
        assertFailsWith<CheckedLongOverflowException> { mulExact(3_037_000_500L, 3_037_000_500L) }
    }
}
