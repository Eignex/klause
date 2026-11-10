package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FinitePortfolioLpCaptureTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `omitted policy should retain the companion configuration`(handoff: Boolean) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to handoff.toString())

        val parameters = FinitePortfolioLpCapture.parameters(options)

        assertEquals(listOf("companion-integers=true", "companion-active-ms=4000",
            "companion-after-incumbent=true", "companion-admission=progress-funded", "companion-repair=fixed",
            "source-incumbent-completion=$handoff", "portfolio-progress=true", "portfolio-reward=ordinary",
            "phase-retention=0.25", "reseed-stale-threshold=3", "portfolio-probes=ordinary",
            "portfolio-proof-workers=retained", "portfolio-proof-share=0.5"), parameters)
    }

    @Test
    fun `ordinary policy should leave algorithm selection at source defaults`() {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "false", "portfolio-policy" to "ordinary", "portfolio-progress" to "false")

        val parameters = FinitePortfolioLpCapture.parameters(options)

        assertEquals(listOf("portfolio-progress=false"), parameters)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `complete coverage should preserve supported handoff selection`(handoff: Boolean) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to handoff.toString(), "portfolio-policy" to "complete")

        val parameters = FinitePortfolioLpCapture.parameters(options).associate {
            it.substringBefore('=') to it.substringAfter('=')
        }

        assertEquals("complete", parameters["portfolio-proof-workers"])
        assertEquals(handoff.toString(), parameters["source-incumbent-completion"])
        assertEquals("fixed", parameters["companion-repair"])
    }

    @ParameterizedTest
    @CsvSource("ordinary,false", "ordinary,true", "retained,false", "retained,true", "complete,false", "complete,true")
    fun `progress logging should be independent of policy selection`(policy: String, progress: Boolean) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "false", "portfolio-policy" to policy,
            "portfolio-progress" to progress.toString())

        val parameters = FinitePortfolioLpCapture.parameters(options)

        assertEquals(listOf("portfolio-progress=$progress"), parameters.filter { it.startsWith("portfolio-progress=") })
    }

    @ParameterizedTest
    @CsvSource("portfolio-policy,unknown", "portfolio-progress,yes", "handoff,true", "unsupported,true")
    fun `invalid policy requests should fail before corpus resolution`(key: String, value: String) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "false", "portfolio-policy" to "ordinary") + (key to value)

        assertFailsWith<IllegalArgumentException> {
            FinitePortfolioLpCapture.main(options.map { (name, setting) -> "$name=$setting" }.toTypedArray())
        }
    }
}
