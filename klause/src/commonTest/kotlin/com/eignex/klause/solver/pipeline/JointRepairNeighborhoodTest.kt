package com.eignex.klause.solver.pipeline

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JointRepairNeighborhoodTest {
    @Test
    fun `repair should free related integers through a real bridge and retain unrelated pins`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("bridge", "exact")), setOf(x, y, z, real),
            mapOf("x-real" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, real to BigFraction.ONE),
                BigFraction.ONE)),
                "real-y" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE, y to BigFraction.ONE),
                    BigFraction.ONE))))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 1, Cancellation.Never))
        repair.guide(mapOf(y to 0.5, x to 1.0, z to 2.0, real to 0.5))

        val condition = repair.propose(List(4) { BigFraction.ZERO }, booleanArrayOf(), 2, 1,
            BigFraction.ONE, BigFraction.ONE)

        assertEquals(mapOf(2 to BigFraction.ZERO), condition.lower)
        assertEquals(condition.lower, condition.upper)
        assertEquals(BigFraction.ONE, condition.cutoff)
        assertEquals(2L, repair.selectedIntegers)
    }

    @Test
    fun `initial rounding should pin all integer centers and leave continuous coordinates free`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("rounding", "exact")), setOf(x, real), emptyMap())
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 1, 1, Cancellation.Never))

        val condition = repair.propose(listOf(BigFraction.ONE, BigFraction.ZERO), booleanArrayOf(true),
            1, 0, BigFraction.ONE, null)

        assertEquals(mapOf(0 to BigFraction.ONE), condition.lower)
        assertEquals(condition.lower, condition.upper)
        assertEquals(mapOf(0 to true), condition.bools)
    }

    @Test
    fun `cancelled connectivity preparation should withhold a partial index`() {
        val source = SourceProofContext(SourceScope(SourceRevision("cancelled", "exact")), emptySet(),
            mapOf("empty" to SourcePremise(SourceInequality(emptyMap(), BigFraction.ZERO))))

        assertNull(JointRepairNeighborhood.prepare(source, 0, 0, Cancellation { true }))
    }
}
