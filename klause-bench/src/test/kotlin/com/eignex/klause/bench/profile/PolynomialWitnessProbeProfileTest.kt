package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        val access = PolynomialWitnessProbeProfile.ProbeAccess()
        val workType = Class.forName("com.eignex.klause.theory.qfnra.QfNraWork")
        val paramsType = Class.forName("com.eignex.klause.theory.qfnra.QfNraParams")
        val owner = workType.getConstructor(paramsType).newInstance(paramsType.getConstructor().newInstance())
        val probe = workType.getMethod("probe", Function0::class.java)
        val coupled = workType.getMethod("coupled", Function0::class.java)
        var captured: ProbeStop? = null

        probe.invoke(owner, {
            coupled.invoke(owner, {
                (owner as WorkMeter).charge(32768)
                assertTrue((owner as Cancellation).isCancelled())
                captured = access.workCheckpoint(owner)
                null
            })
            null
        })

        val stop = assertNotNull(captured)
        assertEquals(32768L, stop.workSpent)
        assertEquals(32768L, stop.coupledEnd)
        assertEquals(65536L, stop.probeEnd)
        assertTrue(stop.stack.any { it.contains("QfNraWork.coupled(QfNraSolver.kt:") })
        val snapshot = workType.getMethod("snapshot").invoke(owner)
        assertEquals(32768L, snapshot.javaClass.getMethod("getWorkSpent").invoke(snapshot))
    }
}
