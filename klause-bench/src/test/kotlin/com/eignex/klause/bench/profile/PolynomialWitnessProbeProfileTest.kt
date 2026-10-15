package com.eignex.klause.bench.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolynomialWitnessProbeProfileTest {
    @Test
    fun `observation should retain successful fixed coordinate producer work`() {
        val source = """
            (declare-const x Real)
            (declare-const y Real)
            (assert (= x 2))
            (assert (> (* x y) 1))
        """.trimIndent()
        val access = PolynomialWitnessProbeProfile.ProbeAccess()

        val plain = access.measure(source, false)
        val observed = access.measure(source, true, chargeStacks = true)

        assertEquals("Satisfiable", observed.result)
        assertEquals(plain.stats, observed.stats)
        assertEquals(observed.nativeWork, observed.charges.values.sum())
        assertTrue(observed.charges.isNotEmpty())
        assertEquals(emptyList(), observed.stops)
    }

    @Test
    fun `an unresolved producer should stop before projection and retain pending work`() {
        val source = "(assert false)"
        val access = PolynomialWitnessProbeProfile.ProbeAccess()

        val plain = access.measure(source, false)
        val observed = access.measure(source, true)

        assertEquals("PendingAfterProbe", observed.result)
        assertEquals(plain.stats, observed.stats)
        assertTrue(observed.stats.contains("projectedPolynomials=0, liftedCells=0"))
    }
}
