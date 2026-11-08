package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class LpQueryObservationTest {
    @Test
    fun `captured basis arrays should remain owned after observation ends`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 5.0, cost = 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
        val model = builder.build(Sense.MINIMIZE)
        val original = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
        val query = LpQueryObservation(Any(), 1L, model, false, 0L,
            LpClaim.ATTAINED_OPTIMUM, 0.0, false, LpRefinementLimits(), LpPricingOptions(), false) { original }

        val captured = assertNotNull(query.captureBasis())
        query.release()
        original.basicVars[0] = 1
        original.status[0] = VarStatus.AT_UPPER

        assertContentEquals(intArrayOf(0), captured.basicVars)
        assertContentEquals(arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER), captured.status)
    }
}
