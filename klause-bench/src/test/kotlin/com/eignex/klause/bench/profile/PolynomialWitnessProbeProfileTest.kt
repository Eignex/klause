package com.eignex.klause.bench.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolynomialWitnessProbeProfileTest {
    @Test
    fun `shared source observation retains the selected model and native work`() {
        val source = """
            (declare-const x Real) (declare-const y Real)
            (declare-const b Bool) (declare-const c Bool)
            (assert (= b (<= y (+ (* x x) 2))))
            (assert (= c (>= y (+ (* x x) 2))))
            (assert b) (assert c)
            (assert (> x 2)) (assert (< x 3)) (assert (> (* x x) 5))
        """.trimIndent()
        val access = PolynomialWitnessProbeProfile.ProbeAccess()

        val plain = access.measure(source, false, sourceMode = "shared")
        val observed = access.measure(source, true, chargeStacks = true,
            sourceMode = "shared", coupledChargesOnly = true)

        assertEquals(plain.result, observed.result)
        assertEquals(plain.stats, observed.stats)
        assertEquals(plain.sourceShape, observed.sourceShape)
        assertEquals("shared", observed.sourceShape?.mode)
        assertEquals("Problem", observed.sourceShape?.representation)
        assertTrue(checkNotNull(observed.sourceShape?.realCount) > 2)
        assertTrue(observed.nativeWork > 0)
        assertEquals(observed.nativeWork, observed.charges.values.sum())
    }

    @Test
    fun `shared linear admission supplies no polynomial producer verdict`() {
        val access = PolynomialWitnessProbeProfile.ProbeAccess()

        val record = access.measure("(declare-const x Real) (assert (> x 0))", true,
            chargeStacks = true, sourceMode = "shared", coupledChargesOnly = true)

        assertEquals("NotPolynomialSource", record.result)
        assertEquals(0L, record.nativeWork)
        assertEquals(emptyMap(), record.charges)
        assertEquals(null, record.sourceShape?.realCount)
    }

    @Test
    fun `coupled charge accounting retains work outside the private producer scope`() {
        val source = """
            (declare-const x Real) (declare-const y Real)
            (assert (= x 2)) (assert (> (* x y) 1))
        """.trimIndent()
        val access = PolynomialWitnessProbeProfile.ProbeAccess()

        val plain = access.measure(source, false)
        val observed = access.measure(source, true, chargeStacks = true, coupledChargesOnly = true)

        assertEquals(plain.stats, observed.stats)
        assertEquals(setOf("outside coupled scope"), observed.charges.keys)
        assertEquals(observed.nativeWork, observed.charges.values.sum())
    }

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
