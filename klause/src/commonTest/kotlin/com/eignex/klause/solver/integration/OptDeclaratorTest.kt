package com.eignex.klause.solver.integration

import com.eignex.klause.compile.compile
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.PresenceSpec
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptDeclaratorTest {
    private class S : VariableSchema() {
        val x by optIntVar(min = 0, max = 5)
    }

    @Test
    fun `optIntVar registers presence then value`() {
        val s = S()
        val entries = s.entries.entries.toList()
        assertEquals(2, entries.size)
        // Presence bool registered first under the synthetic name.
        assertEquals("x__present", entries[0].key)
        val presence = entries[0].value
        assertTrue(presence is PresenceSpec)
        assertEquals("x", presence.valueName)
        assertEquals("x", entries[1].key)
        assertTrue(entries[1].value is IntSpec)
    }

    @Test
    fun `decode returns null when presence false`() {
        val s = S()
        val compiled = s.compile()
        val sample = Sample(
            bools = booleanArrayOf(false),
            ints = longArrayOf(3),
        )
        assertNull(compiled.decode(s.x, sample))
    }

    @Test
    fun `decode returns value when presence true`() {
        val s = S()
        val compiled = s.compile()
        val sample = Sample(
            bools = booleanArrayOf(true),
            ints = longArrayOf(3),
        )
        assertEquals(3, compiled.decode(s.x, sample))
    }
}
