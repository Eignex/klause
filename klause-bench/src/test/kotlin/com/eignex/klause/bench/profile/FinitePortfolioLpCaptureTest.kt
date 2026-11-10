package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FinitePortfolioLpCaptureTest {
    @ParameterizedTest
    @ValueSource(strings = ["bt/satOptimized", "bt/conflictDriven"])
    fun `unselected callbacks should preserve selected metadata and owner capacity`(worker: String) {
        val window = FinitePortfolioLpCapture.QueryWindow("bt/lp-default", 1)
        val owner = Any()

        repeat(200) {
            assertNull(window.observe(worker, { error("unselected owner must not be read") }) { false })
        }
        val records = List(124) { window.observe("bt/lp-default", { owner }) { false } }

        records.forEach { assertEquals(0, assertNotNull(it).owner) }
        assertEquals(324, window.queries)
        assertEquals(124, window.workerQueries)
        assertEquals(200, window.unselectedWorkerQueries)
        assertEquals(0, window.metadataDropped)
    }

    @Test
    fun `all worker capture should share the early metadata budget`() {
        val window = FinitePortfolioLpCapture.QueryWindow("any", 1)
        val owner = Any()
        repeat(124) {
            val worker = if (it % 2 == 0) "bt/satOptimized" else "bt/lp-default"
            assertNotNull(window.observe(worker, { owner }) { false })
        }

        val overflow = window.observe("bt/lp-default", { owner }) { false }

        assertNull(overflow)
        assertEquals(125, window.workerQueries)
        assertEquals(0, window.unselectedWorkerQueries)
        assertEquals(1, window.metadataDropped)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4])
    fun `late matching captures should fit the reserved metadata capacity`(captures: Int) {
        val window = FinitePortfolioLpCapture.QueryWindow("bt/lp-default", captures)
        val owner = Any()
        repeat(124) { assertNotNull(window.observe("bt/lp-default", { owner }) { false }) }

        val late = List(10) { window.observe("bt/lp-default", { owner }) { true } }

        assertEquals(captures, late.count { it?.capture == true })
        assertEquals(captures, window.captureAttempts)
        assertEquals(10 - captures, window.metadataDropped)
    }

    @Test
    fun `equal owner values should retain separate identities`() {
        val window = FinitePortfolioLpCapture.QueryWindow("any", 1)
        val first = String(charArrayOf('x'))
        val second = String(charArrayOf('x'))

        val a = window.observe("bt/lp-default", { first }) { false }
        val b = window.observe("bt/lp-default", { second }) { false }

        assertEquals(first, second)
        assertEquals(0, assertNotNull(a).owner)
        assertEquals(1, assertNotNull(b).owner)
    }

    @Test
    fun `owner exhaustion should preserve queries from retained owners`() {
        val window = FinitePortfolioLpCapture.QueryWindow("bt/lp-default", 1)
        val owners = List(64) { Any() }
        owners.forEach { assertNotNull(window.observe("bt/lp-default", { it }) { false }) }

        val overflow = window.observe("bt/lp-default", { Any() }) { true }
        val retained = window.observe("bt/lp-default", { owners.first() }) { true }

        assertNull(overflow)
        assertEquals(0, assertNotNull(retained).owner)
        assertEquals(true, retained.capture)
        assertEquals(1, window.captureAttempts)
        assertEquals(1, window.metadataDropped)
    }

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

    @Test
    fun `joint capture should retain ordinary pre incumbent grants`() {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to "joint-before")

        val parameters = FinitePortfolioLpCapture.parameters(options).associate {
            it.substringBefore('=') to it.substringAfter('=')
        }

        assertEquals("ordinary", parameters["companion-admission"])
        assertEquals("false", parameters["companion-after-incumbent"])
        assertEquals("4000", parameters["companion-active-ms"])
        assertEquals("fixed", parameters["companion-repair"])
        assertEquals("complete", parameters["portfolio-proof-workers"])
    }

    @ParameterizedTest
    @CsvSource("handoff,false", "portfolio-policy,retained", "portfolio-progress,true", "capture-worker,any",
        "inspect-rays,unknown")
    fun `joint capture should reject incompatible controls before corpus resolution`(key: String, value: String) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to "joint-before") + (key to value)

        assertFailsWith<IllegalArgumentException> {
            FinitePortfolioLpCapture.main(options.map { (name, setting) -> "$name=$setting" }.toTypedArray())
        }
    }

    @ParameterizedTest
    @CsvSource("portfolio-policy,unknown", "portfolio-progress,yes", "handoff,true", "unsupported,true",
        "inspect-rays,true")
    fun `invalid policy requests should fail before corpus resolution`(key: String, value: String) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "false", "portfolio-policy" to "ordinary") + (key to value)

        assertFailsWith<IllegalArgumentException> {
            FinitePortfolioLpCapture.main(options.map { (name, setting) -> "$name=$setting" }.toTypedArray())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `ray inspection should preserve producer parameters`(enabled: Boolean) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to "joint-before")
        val expected = FinitePortfolioLpCapture.parameters(options)

        val parameters = FinitePortfolioLpCapture.parameters(options + ("inspect-rays" to enabled.toString()))

        assertEquals(expected, parameters)
    }
}
