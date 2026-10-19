package com.eignex.klause.bench.profile

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSourceCompilerLinksTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://example.test/catalog", "https://user@example.test/catalog",
            "https://example.test/catalog?query", "https://example.test/catalog#fragment",
        ],
    )
    fun `catalog requests require an unambiguous HTTPS identity`(address: String) {
        assertFailsWith<IllegalArgumentException> {
            JvmSourceCompilerLinks.parse(arrayOf("catalog=$address", "catalog-sha256=${"a".repeat(64)}"))
        }
    }

    @ParameterizedTest
    @CsvSource("8,72,8", "8,4,4", "1,8,1")
    fun `the reader respects the scheduled processor allowance`(scheduled: Int, available: Int, expected: Int) {
        val actual = JvmSourceCompilerLinks.scheduledProcessors("-Xmx4g -XX:ActiveProcessorCount=$scheduled", available)

        assertEquals(expected, actual)
    }

    @Test
    fun `a failed reader retains its exit status`(@TempDir out: File) {
        assertFailsWith<IllegalStateException> {
            JvmSourceCompilerLinks.runReader(listOf("sh", "-c", "exit 7"), out, out, ProfileRunBudget())
        }

        assertEquals("7\n", File(out, "source-compiler-links.exit").readText())
    }
}
