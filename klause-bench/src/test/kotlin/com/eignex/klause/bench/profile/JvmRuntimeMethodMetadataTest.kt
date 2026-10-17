package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmRuntimeMethodMetadataTest {
    private val remote = listOf("manifest=https://example.test/manifest.json", "manifest-sha256=${"a".repeat(64)}",
        "archive=https://example.test/runtime.tar.gz", "archive-sha256=${"b".repeat(64)}")

    @Test
    fun `a remote archive keeps its original complete identity request`() {
        val options = JvmRuntimeMethodMetadata.parse(remote.toTypedArray())

        assertEquals(remote.associate { it.substringBefore('=') to it.substringAfter('=') }, options)
    }

    @Test
    fun `saved local mode retains the original path and complete byte identities`() {
        val arguments = remote.filterNot { it.startsWith("archive=") } +
            listOf("archive=/owned/original-runtime.tar.gz", "archive-mode=saved-local")

        val options = JvmRuntimeMethodMetadata.parse(arguments.toTypedArray())

        assertEquals(arguments.associate { it.substringBefore('=') to it.substringAfter('=') }, options)
    }

    @ParameterizedTest
    @ValueSource(strings = ["archive-mode=other", "archive=relative", "archive=https://example.test/runtime.tar.gz",
        "manifest=http://example.test/manifest.json", "manifest=https://user@example.test/manifest.json",
        "manifest=https://example.test/x?query=value", "manifest=https://example.test/x#fragment",
        "manifest-sha256=bad", "archive-sha256=bad", "unknown=value"])
    fun `local mode rejects unbound incomplete and remote archive inputs before access`(argument: String) {
        val local = remote.filterNot { it.startsWith("archive=") } +
            listOf("archive=/owned/original-runtime.tar.gz", "archive-mode=saved-local")
        val arguments = local.filterNot { it.substringBefore('=') == argument.substringBefore('=') } + argument

        assertFailsWith<IllegalArgumentException> { JvmRuntimeMethodMetadata.parse(arguments.toTypedArray()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "local-without-mode"])
    fun `ambiguous and incomplete requests cannot select a runtime`(variant: String) {
        val arguments = when (variant) {
            "missing" -> remote.drop(1)
            "duplicate" -> remote + remote.first()
            else -> remote.filterNot { it.startsWith("archive=") } + "archive=/owned/runtime.tar.gz"
        }

        assertFailsWith<IllegalArgumentException> { JvmRuntimeMethodMetadata.parse(arguments.toTypedArray()) }
    }
}
