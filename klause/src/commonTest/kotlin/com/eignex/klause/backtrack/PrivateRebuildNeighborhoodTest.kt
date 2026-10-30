package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PrivateRebuildNeighborhoodTest {
    @Test
    fun `a cancelled neighborhood scan should withhold its incomplete source view`() {
        val source = Problem(4, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(3, 5, 6)), Clause(intArrayOf(0, 2)),
            Clause(intArrayOf(3, 5, 7)), Clause(intArrayOf(1, 2)),
            Clause(intArrayOf(2, 4, 6)), Clause(intArrayOf(2, 4, 7)),
        ))
        val admission = assertNotNull(PrivateRebuildAdmission.analyze(source, Cancellation.Never))
        var spent = 0L
        val meter = WorkMeter { spent += it }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= source.numBoolVars
            override fun workMeter(): WorkMeter = meter
        }

        val neighborhood = PrivateRebuildNeighborhood.prepare(source, admission, token)

        assertNull(neighborhood)
        assertEquals(6, source.numFactors)
    }
}
