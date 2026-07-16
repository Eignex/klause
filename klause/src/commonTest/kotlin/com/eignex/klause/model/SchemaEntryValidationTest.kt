package com.eignex.klause.model

import kotlin.test.Test
import kotlin.test.assertFails

class SchemaEntryValidationTest {

    @Test
    fun `IntSpec rejects an inverted domain`() {
        assertFails { IntSpec(min = 5, max = 1) }
        IntSpec(min = 3, max = 3) // a single-value domain is valid
    }

    @Test
    fun `NominalSpec rejects an empty label set`() {
        assertFails { NominalSpec(labels = emptyList()) }
    }
}
