package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CompilationStudyBuildsTest {
    @Test
    fun `identical pinned revisions are allowed for repeated source controls`() {
        val heads = mapOf("control" to "a".repeat(40), "candidate" to "a".repeat(40))

        CompilationStudyBuilds.validateHeads(heads)
    }

    @ParameterizedTest
    @ValueSource(strings = ["main", "abc", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"])
    fun `moving or malformed source references are rejected before building`(head: String) {
        val heads = mapOf("control" to "a".repeat(40), "candidate" to head)

        assertFailsWith<IllegalArgumentException> { CompilationStudyBuilds.validateHeads(heads) }
    }

    @Test
    fun `a missing comparison arm is rejected`() {
        val heads = mapOf("candidate" to "a".repeat(40))

        assertFailsWith<IllegalArgumentException> { CompilationStudyBuilds.validateHeads(heads) }
    }
}
