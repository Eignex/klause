package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceDefinitionReferencesTest {
    @Test
    fun `projection should remove recovered outputs and preserve surviving literal polarity`() {
        val references = SourceDefinitionReferences(intArrayOf(0, 1), listOf(
            BoolFoldDefinition(1, intArrayOf(Lit.make(0, false)), true),
            BoolFoldDefinition(2, intArrayOf(Lit.make(1, true)), false),
        ))
        val coordinates = VarRemap(intArrayOf(1, 0, -1), intArrayOf(-1, 0))

        val projected = assertNotNull(references.remapped(coordinates, Cancellation.Never))

        assertEquals(listOf(0), projected.integers.toList())
        assertEquals(0, projected.folds.single().out)
        assertEquals(listOf(Lit.make(1, false)), projected.folds.single().lits.toList())
    }

    @Test
    fun `interrupted projection should not export partially remapped folds`() {
        val references = SourceDefinitionReferences(intArrayOf(), listOf(
            BoolFoldDefinition(2, intArrayOf(Lit.make(0, true), Lit.make(1, false)), true),
        ))
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 2
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val projected = references.remapped(VarRemap(intArrayOf(0, 1, 2), intArrayOf()), stop)

        assertNull(projected)
        assertEquals(2L, charged)
    }
}
