package com.eignex.klause.bench.profile

import com.eignex.klause.solver.Sample
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RepairTrajectoryTest {
    @Test
    fun `proper subsets should cover every smaller nonempty freedom`() {
        val subsets = RepairTrajectory.properSubsets(intArrayOf(0, 3, 6))

        assertEquals(setOf(listOf(0), listOf(3), listOf(6), listOf(0, 3), listOf(0, 6), listOf(3, 6)),
            subsets.map { it.toList() }.toSet())
    }

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
