package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JointRayInspectionTest {
    @Test
    fun `decoded ray should preserve finite values and signed zero`() {
        val ray = doubleArrayOf(0.0, -0.0, 1.5, Double.MIN_VALUE, Double.MAX_VALUE)
        val bits = assertNotNull(JointRepairLpCapture.rayBits(ray, ray.size))

        val decoded = JointRayInspection.decodeRay(bits, ray.size)

        assertContentEquals(ray, assertNotNull(decoded))
    }

    @ParameterizedTest
    @ValueSource(strings = ["7ff0000000000000", "fff0000000000000", "7ff8000000000000", "3f90", "zzzzzzzzzzzzzzzz"])
    fun `nonfinite or malformed ray bits should be unavailable`(bits: String) {
        val decoded = JointRayInspection.decodeRay(listOf(bits), 1)

        assertNull(decoded)
    }

    @ParameterizedTest
    @CsvSource("0,0", "2,1", "4097,4097")
    fun `unsupported decoded dimensions should be unavailable`(size: Int, rows: Int) {
        val bits = List(size) { "0000000000000000" }

        val decoded = JointRayInspection.decodeRay(bits, rows)

        assertNull(decoded)
    }
}
