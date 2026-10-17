package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSourceSyntaxBindingsTest {
    @Test
    fun `join request preserves the complete pinned input catalog`() {
        val args = arrayOf("catalog=https://example.test/catalog.json", "catalog-sha256=${"a".repeat(64)}")

        val options = JvmSourceSyntaxBindings.parse(args)

        assertEquals(mapOf("catalog" to "https://example.test/catalog.json", "catalog-sha256" to "a".repeat(64)), options)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "unknown", "hash", "http", "credentials", "query", "fragment"])
    fun `join requests reject ambiguous and unpinned inputs`(variant: String) {
        val base = listOf("catalog=https://example.test/catalog.json", "catalog-sha256=${"a".repeat(64)}")
        val args = when (variant) {
            "missing" -> base.drop(1)
            "duplicate" -> base + base.first()
            "unknown" -> base + "other=value"
            "hash" -> base.dropLast(1) + "catalog-sha256=bad"
            else -> listOf("catalog=" + when (variant) {
                "http" -> "http://example.test/catalog.json"
                "credentials" -> "https://user@example.test/catalog.json"
                "query" -> "https://example.test/catalog.json?q=x"
                else -> "https://example.test/catalog.json#fragment"
            }, base.last())
        }

        assertFailsWith<IllegalArgumentException> { JvmSourceSyntaxBindings.parse(args.toTypedArray()) }
    }
}
