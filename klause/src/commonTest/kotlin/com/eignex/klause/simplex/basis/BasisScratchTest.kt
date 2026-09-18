package com.eignex.klause.simplex.basis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class BasisScratchTest {
    @Test
    fun `nested borrows keep live buffers exclusive`() {
        val scratch = BasisScratch()
        scratch.borrow(3) { outer ->
            outer[0] = 7.0
            scratch.borrow(3) { inner ->
                assertNotSame(outer, inner)
                inner[0] = 9.0
            }
            assertEquals(7.0, outer[0])
        }
        scratch.borrowI32(3) { outer ->
            outer[0] = 7
            scratch.borrowI32(3) { inner ->
                assertNotSame(outer, inner)
                inner[0] = 9
            }
            assertEquals(7, outer[0])
        }
    }

    @Test
    fun `exceptions return both types of scratch without clearing`() {
        val scratch = BasisScratch()
        var doubles: DoubleArray? = null
        var integers: IntArray? = null

        assertFailsWith<IllegalStateException> {
            scratch.borrow(2) { values ->
                doubles = values
                values[0] = 7.0
                scratch.borrowI32(2) { indices ->
                    integers = indices
                    indices[0] = 8
                    error("abandoned operation")
                }
            }
        }

        scratch.borrow(2) {
            assertSame(doubles, it)
            assertEquals(7.0, it[0])
        }
        scratch.borrowI32(2) {
            assertSame(integers, it)
            assertEquals(8, it[0])
        }
    }

    @Test
    fun `reclamation discards idle sizes without reclaiming a live buffer`() {
        val scratch = BasisScratch()
        val oldest = scratch.borrow(1) { it }
        scratch.borrow(2) { live ->
            live[0] = 7.0
            for (size in 3..70) scratch.borrow(size) { assertEquals(size, it.size) }
            scratch.borrow(1) { assertNotSame(oldest, it) }
            scratch.borrow(2) { assertNotSame(live, it) }
            assertEquals(7.0, live[0])
        }
    }

    @Test
    fun `empty buffers are reusable and negative sizes are rejected`() {
        val scratch = BasisScratch()
        val doubles = scratch.borrow(0) { it }
        val integers = scratch.borrowI32(0) { it }

        assertFailsWith<IllegalArgumentException> { scratch.borrow(-1) {} }
        assertFailsWith<IllegalArgumentException> { scratch.borrowI32(-1) {} }

        scratch.borrow(0) { assertSame(doubles, it) }
        scratch.borrowI32(0) { assertSame(integers, it) }
    }
}
