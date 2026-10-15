package com.eignex.klause.bench.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolynomialWitnessProbeProfileTest {
    @Test
    fun `observation retains successful fixed coordinate producer work`() {
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
    fun `unresolved producer stops before projection and retains pending work`() {
        val source = """
            (declare-const x Real)
            (declare-const y Real)
            (assert (> (* x y) 1))
            (assert (<= (* x y) 0))
        """.trimIndent()
        val access = PolynomialWitnessProbeProfile.ProbeAccess()

        val plain = access.measure(source, false)
        val observed = access.measure(source, true)

        assertEquals("PendingAfterProbe", observed.result)
        assertEquals(plain.stats, observed.stats)
        assertTrue(observed.stats.contains("projectedPolynomials=0, liftedCells=0"))
    }

    @Test
    fun `private work checkpoint retains its charge and producer frame`() {
        val declarations = (0 until 32).joinToString("\n") { "(declare-const x$it Real)" }
        val terms = (1..16).flatMap { x -> (1..32).map { y -> "(* (^ x0 $x) (^ x31 $y))" } }
            .joinToString(" ")
        val source = "$declarations\n(assert (> (+ $terms) 2))"
        val access = PolynomialWitnessProbeProfile.ProbeAccess()

        val plain = access.measure(source, false)
        val observed = access.measure(source, true)

        assertEquals(plain.result, observed.result)
        assertEquals(plain.stats, observed.stats)
        assertEquals(emptyList(), plain.stops)
        assertEquals(1, observed.stops.size)
        for (stop in observed.stops) {
            assertTrue(stop.workSpent >= stop.coupledEnd)
            assertTrue(requireNotNull(stop.probeEnd) > stop.coupledEnd)
            assertTrue(stop.stack.any { it.contains("QfNraSolver.kt:") })
        }
    }
}
