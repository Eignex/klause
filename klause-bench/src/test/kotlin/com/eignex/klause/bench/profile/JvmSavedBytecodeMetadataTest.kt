package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSavedBytecodeMetadataTest {
    @Test
    fun `requests retain the pinned full input specification identity`() {
        val args = arrayOf("specification=https://example.test/inputs.json", "specification-sha256=${"a".repeat(64)}")

        val options = JvmSavedBytecodeMetadata.parse(args)

        assertEquals(args.associate { it.substringBefore('=') to it.substringAfter('=') }, options)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "unknown", "hash", "http", "credentials", "query", "fragment"])
    fun `ambiguous and unpinned requests fail before access`(variant: String) {
        val args = listOf("specification=https://example.test/inputs.json", "specification-sha256=${"a".repeat(64)}")
        val actual = when (variant) {
            "missing" -> args.drop(1)
            "duplicate" -> args + args.first()
            "unknown" -> args + "other=value"
            "hash" -> args.dropLast(1) + "specification-sha256=unhashed"
            else -> listOf("specification=" + when (variant) {
                "http" -> "http://example.test/inputs.json"
                "credentials" -> "https://user@example.test/inputs.json"
                "query" -> "https://example.test/inputs.json?q=x"
                else -> "https://example.test/inputs.json#fragment"
            }, args.last())
        }

        assertFailsWith<IllegalArgumentException> { JvmSavedBytecodeMetadata.parse(actual.toTypedArray()) }
    }
}
