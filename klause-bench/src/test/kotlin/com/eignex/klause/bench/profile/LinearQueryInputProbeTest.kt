package com.eignex.klause.bench.profile

import com.eignex.klause.ir.Problem
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LinearQueryInputProbeTest {
    @Test
    fun `probing an unopened component should not initialize its numerical owner`() {
        val source = Problem(0, 0, intDomains = emptyArray(), factors = emptyArray())
        val samples = LinearQueryInputSamples<Any>()
        ExactLiraSearchComponent(source).use { component ->
            val delegate = component.javaClass.getDeclaredField("lpDelegate").apply { isAccessible = true }
                .get(component) as Lazy<*>
            val probe = LinearQueryInputProbe(component, samples)

            repeat(4) { probe.observe() }

            assertFalse(delegate.isInitialized())
            assertEquals(4L, probe.coverage().getValue("UNINITIALIZED"))
            assertEquals(0L, samples.transitions)
            assertEquals(0, samples.snapshot().samples.size)
        }
    }

    @Test
    fun `stale or absent inputs should not read storage or retain a sample`() {
        val samples = LinearQueryInputSamples<Any>()
        var weightReads = 0
        val gate = LinearQueryInputGate(samples) { weightReads++; 10L }

        gate.observe(Any(), Any())
        gate.observe(null, Any())

        assertEquals(0, weightReads)
        assertEquals(0L, samples.transitions)
        assertEquals(1L, gate.coverage().getValue("STALE"))
        assertEquals(1L, gate.coverage().getValue("NO_INPUT"))
    }

    @Test
    fun `an uncached matching input should remain unretained without a fallback scan`() {
        val samples = LinearQueryInputSamples<Any>()
        var cacheReads = 0
        val gate = LinearQueryInputGate(samples) { cacheReads++; null }
        val state = Any()

        gate.observe(state, state)

        assertEquals(1, cacheReads)
        assertEquals(1L, samples.uncachedWeight)
        assertEquals(0, samples.snapshot().samples.size)
        assertEquals(1L, gate.coverage().getValue("UNCACHED"))
    }

}
