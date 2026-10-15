package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LegacyLpModelTest {
    @Test
    fun `IEEE bounds origins and objective should retain their source bits`() {
        val model = LpBuilder().apply { addRealVar(-0.0, 0.1, cost = 0.3, tag = 7) }.build(Sense.MAXIMIZE)

        val exact = assertNotNull(model.authoritativeModel())

        assertEquals(model.loShiftD(0).toRawBits(), exact.column(0).origin.ieeeBits)
        assertEquals(model.upperD(0).toRawBits(), exact.column(0).bounds.upper?.number?.ieeeBits)
        assertEquals(model.costD(0).toRawBits(), exact.objective.cost(0).ieeeBits)
        assertEquals(model.objConstantD.toRawBits(), exact.objective.constant.ieeeBits)
        assertEquals(Sense.MAXIMIZE, exact.objective.sense)
        assertEquals(7, exact.column(0).tag)
        assertFalse(exact.column(0).integral)
    }

    @Test
    fun `a continuous row should not make its logical column integral`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 2.0)
            addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.LE, 1.0)
        }.build(Sense.MINIMIZE)

        val exact = assertNotNull(model.authoritativeModel())

        assertFalse(exact.column(model.n).integral)
    }

    @Test
    fun `cancellation should not publish a legacy strict witness`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model, cancellation = Cancellation { true })

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(result.witness)
    }
}
