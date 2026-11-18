package com.eignex.klause.lp.engine

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class FloatEvidenceSlackTest(
    private val reading: String,
    private val weight: Long,
    private val rhs: Long,
    private val absolute: Double,
    private val relative: Double,
    private val optimum: Double,
    private val sign: Long,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} sign={6}")
        fun readings(): List<Array<Any>> = listOf(
            arrayOf<Any>("nominal", 1L, 0L, 0.0, 0.0, -1.0),
            arrayOf<Any>("absolute", 1L, 0L, 1.0, 0.0, -3.0),
            arrayOf<Any>("relative", 1L, 0L, 0.0, 1.0, -2.0),
            arrayOf<Any>("nominal offset", 2L, -1L, 0.0, 0.0, -1.0),
            arrayOf<Any>("absolute offset", 2L, -1L, 1.0, 0.0, -4.0),
            arrayOf<Any>("relative offset", 2L, -1L, 0.0, 1.0, -4.0),
        ).flatMap { reading -> listOf(-1L, 1L).map { reading + it } }
    }

    @Test
    fun `a derived numerical bound should cover the widened optimum`() {
        val model = LpBuilder().apply {
            val z = addRealVar(0.0, null, cost = -1.0)
            val u = addRealVar(0.0, 1.0)
            addRow(intArrayOf(z, u), longArrayOf(sign, -weight * sign), Relation.EQ, rhs * sign)
        }.build(Sense.MINIMIZE)

        val certificate = FloatDeductionCertificate.of(
            model, doubleArrayOf(0.0), slack = LpBoundSlack(absolute, relative),
        )

        val bound = assertNotNull(certificate).lowerBound
        assertTrue(bound <= optimum && bound > optimum - 1e-4, "$reading bound $bound optimum $optimum")
    }
}
