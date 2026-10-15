package com.eignex.klause.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SchemaEntryTest {
    @Test
    fun `IntSpec should reject an inverted domain`() {
        assertFails { IntSpec(min = 5, max = 1) }
    }

    @Test
    fun `NominalSpec should reject an empty label set`() {
        assertFails { NominalSpec(labels = emptyList()) }
    }

    @Test
    fun `scale should handle a non-unit step`() {
        val s = FloatSpec(min = -1.0, max = 1.0, buckets = 5)
        assertEquals(0.5, s.scale)
        assertEquals(-1.0, s.realValue(0))
        assertEquals(0.0, s.realValue(2))
        assertEquals(1.0, s.realValue(4))
    }

    @Test
    fun `a float spec should require at least two buckets`() {
        assertFails { FloatSpec(min = 0.0, max = 1.0, buckets = 1) }
    }
}
