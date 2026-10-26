package com.eignex.klause.bench.profile

import com.eignex.klause.solver.Sample
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RepairTrajectoryTest {
    @Test
    fun `source input distance should exclude auxiliaries and real displacement`() {
        val before = Sample(booleanArrayOf(false), longArrayOf(1, 3), doubleArrayOf(2.0))
        val after = Sample(booleanArrayOf(true), longArrayOf(0, 7), doubleArrayOf(2.5))

        val distance = RepairTrajectory.distance(before, after, intArrayOf(0))

        assertEquals(1, distance.getValue("sourceInputChanges").jsonPrimitive.int)
        assertEquals(2, distance.getValue("sourceAuxiliaryChanges").jsonPrimitive.int)
        assertEquals(0.5, distance.getValue("approximateRealL1Displacement").jsonPrimitive.double)
    }
}
