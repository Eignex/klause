package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrivateRebuildAffineTest {
    @Test
    fun `affine reconstruction should retain free coordinates above one word`() {
        val clauses = listOf(intArrayOf(0, 70, 71), intArrayOf(1, 70, 71)).flatMap { columns ->
            listOf(1, 2, 4, 7).map { mask ->
                Clause(IntArray(3) { Lit.make(columns[it], mask and (1 shl it) == 0) })
            }
        }.toTypedArray<Factor>()
        val session = PropagationSession(Problem(72, 0, emptyArray(), clauses).bake())
        val captured = assertNotNull(PrivateRebuildParity.capture(session, 1_048_576L, { true }))
        val generation = assertNotNull(PrivateRebuildGeneration.prepare(session, 1_048_576L,
            Cancellation.Never, parity = captured))
        val privatePoint = BooleanArray(72) { it == 70 }

        val lifted = generation.reconstruct(Sample(privatePoint, longArrayOf()))

        assertEquals(false, lifted.boolValue(0))
        assertEquals(true, lifted.boolValue(70))
        assertTrue(clauses.all { factor ->
            (factor as Clause).literals.any { Lit.evaluate(it, lifted.boolValue(Lit.variable(it))) }
        })
    }
}
