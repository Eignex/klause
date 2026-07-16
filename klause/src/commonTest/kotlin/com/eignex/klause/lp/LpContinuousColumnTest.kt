package com.eignex.klause.lp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpContinuousColumnTest {

    @Test
    fun `solves a continuous LP in floats and declines exact certification`() {
        // minimize x  subject to  2x >= 3 (x >= 1.5),  x in [0, 10] real.
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 10.0, cost = 1.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.GE, 3.0)
        val model = b.build(Sense.MINIMIZE)
        assertTrue(model.hasContinuous)
        assertTrue(model.colContinuous[x])

        val result = solveAndCertify(model)

        // The float solve finds the real optimum; the exact integer certifier declines a real column, so
        // the verdict is INDETERMINATE (sound), not a spurious OPTIMAL proof.
        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(result.certificate)
        val float = assertNotNull(result.float)
        assertEquals(1.5, float.objective, 1e-9)
        assertEquals(1.5, float.primal[x], 1e-9)
        // The Neumaier–Shcherbina safe bound now works over the real columns: a sound lower bound on the
        // true minimum (never above it), usable for node/leaf pruning even though the exact verdict is
        // withheld.
        val safe = assertNotNull(result.safeLowerBound)
        assertTrue(safe <= 1.5 + 1e-9, "safe bound $safe exceeds the optimum 1.5")
    }

    @Test
    fun `certifies an infeasible continuous LP via the rationalized 128-bit Farkas`() {
        // 0 <= x <= 1 with 2x >= 3 (x >= 1.5) has no feasible point; coefficients rationalize exactly.
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 1.0, cost = 1.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.GE, 3.0)
        val result = solveAndCertify(b.build(Sense.MINIMIZE))
        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertNotNull(result.farkasRay)
    }

    @Test
    fun `certifies infeasibility with a fractional but dyadic coefficient`() {
        // 0.5 x >= 1 (x >= 2) with x <= 1 is infeasible; 0.5 = 2⁻¹ rationalizes at k = 1.
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 1.0, cost = 1.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.GE, 1.0)
        assertEquals(LpVerdict.INFEASIBLE, solveAndCertify(b.build(Sense.MINIMIZE)).verdict)
    }

    @Test
    fun `declines to certify when a coefficient is not rationalizable in budget`() {
        // 0.1 is not dyadic within the 40-bit budget; infeasible in reals (0.1x>=1, x<=5 ⇒ x>=10) but the
        // rationalization declines, so the verdict is INDETERMINATE — sound, never a wrong INFEASIBLE.
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 5.0, cost = 1.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(0.1), Relation.GE, 1.0)
        assertEquals(LpVerdict.INDETERMINATE, solveAndCertify(b.build(Sense.MINIMIZE)).verdict)
    }

    @Test
    fun `minimizes a real objective coefficient over a continuous column`() {
        // minimize 1.5 x  subject to  x >= 2,  x in [0, 10] real  ->  x = 2, objective = 3.0.
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 10.0, cost = 1.5)
        b.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 2.0)
        val model = b.build(Sense.MINIMIZE)

        val float = assertNotNull(solveAndCertify(model).float)
        assertEquals(3.0, float.objective, 1e-9)
        assertEquals(2.0, float.primal[x], 1e-9)
    }
}
