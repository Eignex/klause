package com.eignex.klause.ir

import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntBoundsTest {
    private fun bounds() = IntBounds.fromModelBounds(
        longArrayOf(0, 0),
        longArrayOf(9, 9),
        null,
        Bits(2).also { it.set(1) },
    )

    @Test
    fun `a tightening should close an open side at the proved bound`() {
        val tightening = bounds().tightening()

        tightening.atMost(1, 40)
        val proved = tightening.build()

        assertTrue(proved!!.hasUpper(1))
        assertEquals(40, proved.upper(1))
    }

    @Test
    fun `a tightening should keep the tighter of the proved and the declared bound`() {
        val tightening = bounds().tightening()

        tightening.atMost(0, 40)
        tightening.atLeast(0, -5)

        assertNull(tightening.build())
    }

    @Test
    fun `a tightening should narrow a closed side the proved bound improves`() {
        val tightening = bounds().tightening()

        tightening.atLeast(0, 3)
        tightening.atMost(0, 6)
        val proved = tightening.build()

        assertEquals(3, proved!!.lower(0))
        assertEquals(6, proved.upper(0))
    }

    @Test
    fun `a tightening should leave a column it does not name as declared`() {
        val tightening = bounds().tightening()

        tightening.atLeast(0, 3)
        val proved = tightening.build()

        assertEquals(0, proved!!.lower(1))
        assertTrue(proved.isOpenUpper(1))
    }

    @Test
    fun `a tightening that narrows nothing should state no bounds`() {
        val proved = bounds().tightening().build()

        assertNull(proved)
    }

    @Test
    fun `a tightening should not narrow the table it copied`() {
        val declared = bounds()

        declared.tightening().also { it.atLeast(0, 3) }.build()

        assertEquals(0, declared.lower(0))
    }

    @Test
    fun `a tightening should not mutate ranges it has built`() {
        val tightening = bounds().tightening().also { it.atLeast(0, 3) }
        val proved = tightening.build()

        assertFailsWith<IllegalStateException> { tightening.atLeast(0, 5) }
        assertEquals(3, proved!!.lower(0))
    }

    @Test
    fun `model bounds should represent an open range without a search domain`() {
        val openUpper = Bits(1).also { it.set(0) }
        val bounds = IntBounds.fromModelBounds(longArrayOf(3), longArrayOf(0), null, openUpper)

        assertTrue(bounds.hasLower(0))
        assertFalse(bounds.hasUpper(0))
        assertEquals(3, bounds.lower(0))
    }
}
