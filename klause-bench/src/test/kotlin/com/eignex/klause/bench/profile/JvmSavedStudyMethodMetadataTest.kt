package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSavedStudyMethodMetadataTest {
    private val request = listOf("control=${"a".repeat(40)}", "candidate=${"b".repeat(40)}",
        "capture-manifest=https://example.test/capture.json", "capture-manifest-sha256=${"c".repeat(64)}",
        "archive=/owned/original-study.tar.gz", "archive-sha256=${"d".repeat(64)}")

    @Test
    fun `a complete saved study request retains both source and byte identities`() {
        val options = JvmSavedStudyMethodMetadata.parse(request.toTypedArray())

        assertEquals(request.associate { it.substringBefore('=') to it.substringAfter('=') }, options)
    }

    @ParameterizedTest
    @ValueSource(strings = ["control=main", "candidate=", "capture-manifest=http://example.test/capture.json",
        "capture-manifest=https://user@example.test/capture.json", "capture-manifest=https://example.test/x?y=z",
        "capture-manifest=https://example.test/x#fragment", "capture-manifest-sha256=bad",
        "archive-sha256=bad", "archive=relative", "unknown=argument"])
    fun `unbound source hashes remote identities and archive paths are rejected before access`(argument: String) {
        val arguments = request.filterNot { it.substringBefore('=') == argument.substringBefore('=') } + argument

        assertFailsWith<IllegalArgumentException> { JvmSavedStudyMethodMetadata.parse(arguments.toTypedArray()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate"])
    fun `missing and duplicate options cannot select a partial study`(variant: String) {
        val arguments = if (variant == "missing") request.drop(1) else request + request.first()

        assertFailsWith<IllegalArgumentException> { JvmSavedStudyMethodMetadata.parse(arguments.toTypedArray()) }
    }
}
