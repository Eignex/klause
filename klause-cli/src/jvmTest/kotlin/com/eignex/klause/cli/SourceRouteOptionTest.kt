package com.eignex.klause.cli

import com.eignex.klause.solver.pipeline.SourceRoutePreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceRouteOptionTest {
    @Test
    fun `source route preference should be consumed before worker options`() {
        val options = CommonOptions().apply { engineParams += listOf("source-route=exact-linear", "arms=1") }

        val preference = options.takeSourceRoutePreference()

        assertEquals(SourceRoutePreference.EXACT_LINEAR, preference)
        assertEquals(listOf("arms=1"), options.engineParams)
    }

    @Test
    fun `source route preference should reject an unknown owner`() {
        val options = CommonOptions().apply { engineParams += "source-route=forced" }

        assertFailsWith<IllegalArgumentException> { options.takeSourceRoutePreference() }
    }
}
