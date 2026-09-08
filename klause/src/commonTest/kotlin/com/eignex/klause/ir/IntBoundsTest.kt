package com.eignex.klause.ir

import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Narrowing a range table column by column, the form a pass states a bound it proved in. */
class IntBoundsTest {

    /** Two columns: column 0 declared `0..9`, column 1 declared `0..9` but open above. */
    private fun bounds() = IntBounds.fromModelBounds(
        longArrayOf(0, 0),
        longArrayOf(9, 9),
        null,
        Bits(2).also { it.set(1) },
    )

    @Test
    fun `a tightening closes an open side at the proved bound`() {
        val tightening = bounds().tightening()

        tightening.atMost(1, 40)
        val proved = tightening.build()

        assertTrue(proved!!.hasUpper(1), "the side the pass proved is no longer open")
        assertEquals(40, proved.upper(1))
    }

    @Test
    fun `a tightening keeps the tighter of the proved and the declared bound`() {
        val tightening = bounds().tightening()

        tightening.atMost(0, 40)
        tightening.atLeast(0, -5)

        assertNull(tightening.build(), "neither bound is tighter than the declaration")
    }

    @Test
    fun `a tightening narrows a closed side the proved bound improves`() {
        val tightening = bounds().tightening()

        tightening.atLeast(0, 3)
        tightening.atMost(0, 6)
        val proved = tightening.build()

        assertEquals(3, proved!!.lower(0))
        assertEquals(6, proved.upper(0))
    }

    @Test
    fun `a tightening leaves a column it does not name as declared`() {
        val tightening = bounds().tightening()

        tightening.atLeast(0, 3)
        val proved = tightening.build()

        assertEquals(0, proved!!.lower(1))
        assertTrue(proved.isOpenUpper(1), "the untouched column keeps its open side")
    }

    @Test
    fun `a tightening that narrows nothing states no bounds at all`() {
        val proved = bounds().tightening().build()

        assertNull(proved)
    }

    @Test
    fun `a tightening does not narrow the table it copied`() {
        val declared = bounds()

        declared.tightening().also { it.atLeast(0, 3) }.build()

        assertEquals(0, declared.lower(0), "the pass narrows its own copy")
    }

    @Test
    fun `a tightening cannot mutate ranges it has built`() {
        val tightening = bounds().tightening().also { it.atLeast(0, 3) }
        val proved = tightening.build()

        assertFailsWith<IllegalStateException> { tightening.atLeast(0, 5) }
        assertEquals(3, proved!!.lower(0))
    }
}
