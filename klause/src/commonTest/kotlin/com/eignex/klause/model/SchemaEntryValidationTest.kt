package com.eignex.klause.model

import kotlin.test.Test
import kotlin.test.assertFails

class SchemaEntryValidationTest {

    @Test
    fun `IntSpec rejects an inverted domain`() {
        assertFails { IntSpec(min = 5, max = 1) }
    }

    @Test
    fun `IntSpec accepts a single-value domain`() {
        IntSpec(min = 3, max = 3)
    }

    @Test
    fun `NominalSpec rejects an empty label set`() {
        assertFails { NominalSpec(labels = emptyList()) }
    }
}
