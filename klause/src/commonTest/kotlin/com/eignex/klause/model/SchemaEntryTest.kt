package com.eignex.klause.model

import kotlin.test.Test
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
}
