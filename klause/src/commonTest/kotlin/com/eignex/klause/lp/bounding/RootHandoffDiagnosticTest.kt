package com.eignex.klause.lp.bounding

import com.eignex.klause.solver.result.LpStatsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootHandoffDiagnosticTest {
    @Test
    fun `diagnostic scalar payload preserves signed long bits through phase stats`() {
        val sink = LpStatsSink()
        val diagnostic = RootHandoffDiagnostic(sink, Long.MIN_VALUE, "DEFAULT", automatic = true)

        diagnostic.record("SEARCH", "BOUND_HANDOFF", mapOf("floorBits" to (-0.0).toRawBits().toString()))

        val phase = sink.snapshot().phases.values.single()
        val payload = phase.outcomes.keys.single()
        val fields = payload.split('|').drop(1).associate { value ->
            value.substringBefore(':') to value.substringAfter(':')
        }
        assertEquals(1L, phase.calls)
        assertEquals(1L, phase.outcomes.getValue(payload))
        assertEquals(Long.MIN_VALUE, fields.getValue("seed").toLong())
        assertEquals((-0.0).toRawBits(), fields.getValue("floorBits").toLong())
        assertTrue(payload.none { it == '=' || it.isWhitespace() })
    }

    @Test
    fun `diagnostic trace stops with one cap marker within the lifetime record limit`() {
        val sink = LpStatsSink()
        val diagnostic = RootHandoffDiagnostic(sink, 1L, "DEFAULT", automatic = true)

        repeat(200) { diagnostic.record("SEARCH", "NOT_PAUSED") }

        val phases = sink.snapshot().phases
        assertEquals(128, phases.size)
        assertEquals(1L, phases.getValue("ROOT_HANDOFF_TRACE_1_CAP_ROOT").calls)
        assertTrue(phases.values.all { it.calls == 1L && it.outcomes.values.single() == 1L })
    }

    @Test
    fun `merging colliding diagnostic owners preserves a detectable duplicate marker`() {
        val first = LpStatsSink()
        val second = LpStatsSink()
        RootHandoffDiagnostic(first, 1L, "DEFAULT", automatic = true).record("SEARCH", "NOT_PAUSED")
        RootHandoffDiagnostic(second, 1L, "DEFAULT", automatic = true).record("SEARCH", "NOT_PAUSED")

        val merged = first.snapshot().mergedWith(second.snapshot())

        val phase = merged.phases.values.single()
        assertEquals(2L, phase.calls)
        assertEquals(2L, phase.outcomes.values.single())
    }

    @Test
    fun `disabled diagnostic capture leaves phase stats empty`() {
        val sink = LpStatsSink()
        val diagnostic = RootHandoffDiagnostic(sink, 1L, "DEFAULT", automatic = true, enabled = false)

        diagnostic.reset("WARM_START_REPLACED")
        diagnostic.record("SEARCH", "NOT_PAUSED")

        assertTrue(sink.snapshot().phases.isEmpty())
    }

    @Test
    fun `diagnostic payload refuses nonascii and control characters`() {
        for (value in listOf("\u00e9", "\u0000", "\n")) {
            val sink = LpStatsSink()
            val diagnostic = RootHandoffDiagnostic(sink, 1L, "DEFAULT", automatic = true)

            diagnostic.record("SEARCH", "BOUND_HANDOFF", mapOf("comparison" to value))

            assertEquals(mapOf("v1|reason:PAYLOAD" to 1L), sink.snapshot().phases.values.single().outcomes)
        }
    }

    @Test
    fun `diagnostic fields cannot overwrite the record identity`() {
        val sink = LpStatsSink()
        val diagnostic = RootHandoffDiagnostic(sink, 1L, "DEFAULT", automatic = true)

        diagnostic.record("SEARCH", "BOUND_HANDOFF", mapOf("seed" to "2"))

        assertEquals(mapOf("v1|reason:HEADER" to 1L), sink.snapshot().phases.values.single().outcomes)
    }
}
