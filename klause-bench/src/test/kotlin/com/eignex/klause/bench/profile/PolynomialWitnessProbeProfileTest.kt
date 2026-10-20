package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialWitnessProbeProfileTest {
    @Test
    fun `required numeric facts retain reified polarity when their conjunction is inconsistent`() {
        val source = """
            (declare-const x Real) (declare-const b Bool)
            (assert (= b (> (* x x) 0))) (assert b) (assert (<= (* x x) 0))
        """.trimIndent()
        val access = PolynomialWitnessProbeProfile.ProbeAccess()

        val inspection = access.inspectRequired(source, sourceMode = "direct")

        assertEquals("Inconsistent", inspection.status)
        assertEquals(true, inspection.inconsistent)
        assertEquals(2, inspection.mandatoryAtoms)
        assertEquals(0, inspection.mandatoryEqualities)
        assertEquals(false, inspection.ordinaryRecoveryCredit)
        assertEquals(false, inspection.sourceAcceptanceCredit)
    }

    @Test
    fun `required reduction excludes optional numeric graphs and retains original coordinate counts`() {
        val source = """
            (declare-const x Real) (declare-const y Real) (declare-const z Real)
            (assert (or (= y (* x x)) (= y (- (* x x))))) (assert (= z (+ (* x x) 1)))
        """.trimIndent()
        val access = PolynomialWitnessProbeProfile.ProbeAccess()

        val inspection = access.inspectRequired(source, sourceMode = "direct")

        assertEquals("Reduced", inspection.status)
        assertEquals(3, inspection.sourceShape?.realCount)
        assertEquals(1, inspection.mandatoryAtoms)
        assertEquals(1, inspection.mandatoryEqualities)
        assertEquals(2, inspection.initialExplicitGraphVariable)
        assertEquals(2, inspection.initialExplicitGraphReplacementTerms)
        assertEquals(1, inspection.totalGraphSteps)
        assertEquals(1, inspection.eliminatedCount)
        assertEquals(0, inspection.remainingReferencedVariables)
        assertEquals(false, inspection.inconsistent)
    }

    @Test
    fun `interrupted required inspection withholds its partial reduction conclusion`() {
        val source = """
            (declare-const x Real) (declare-const z Real) (declare-const y Real)
            (assert (and (= y (* z z)) (= z (+ (* x x) 1))))
        """.trimIndent()
        val access = PolynomialWitnessProbeProfile.ProbeAccess()
        val complete = access.inspectRequired(source, sourceMode = "direct")
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= complete.analysisWork / 2
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val stopped = access.inspectRequired(source, sourceMode = "direct", cancellation = cancellation)
        val fresh = access.inspectRequired(source, sourceMode = "direct")

        assertEquals("Stopped", stopped.status)
        assertTrue(stopped.analysisWork > 0)
        assertNull(stopped.inconsistent)
        assertNull(stopped.remainingAtoms)
        assertEquals(complete, fresh)
    }

    @Test
    fun `required inspection keeps the default shared producer work independent`() {
        val source = "(declare-const x Real) (assert (> (* x x) 2))"
        val access = PolynomialWitnessProbeProfile.ProbeAccess()
        val before = access.measure(source, false, sourceMode = "shared")

        val inspection = access.inspectRequired(source)
        val after = access.measure(source, false, sourceMode = "shared")

        assertEquals("shared", inspection.sourceShape?.mode)
        assertEquals(before.result, after.result)
        assertEquals(before.stats, after.stats)
        assertEquals(before.sourceShape, after.sourceShape)
    }

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
