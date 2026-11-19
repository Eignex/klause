package com.eignex.klause.factor.symmetry

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.codec.FactorCodecs
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals

class SymmetryHandlingTest {
    @Test
    fun `mixed symmetry consumers should share the captured coordinate order`() {
        val symmetry = SymmetryHandling(
            listOf(intArrayOf(1, 0) to intArrayOf(1, 0)), intArrayOf(2, 3, 0, 1),
        )
        val codecs = FactorCodecs.builtin
        val decoded = codecs.decodeFromJsonElement(codecs.encodeToJsonElement(symmetry))

        for (factor in listOf(symmetry, decoded)) {
            val problem = Problem(2, 2, Array(2) { IntDomain(0, 1) }, listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
                factor,
            ))

            val representatives = BacktrackSolver(problem.bake()).enumerate().map { it.ints.toList() }.toSet()

            assertEquals(setOf(listOf(0L, 0L), listOf(1L, 0L), listOf(1L, 1L)), representatives)
        }
    }

    @Test
    fun `remapped symmetry should retain the captured representative order`() {
        val mapping = VarRemap(intArrayOf(1, 0), intArrayOf(1, 0))
        val symmetry = SymmetryHandling(
            listOf(intArrayOf(1, 0) to intArrayOf(1, 0)), intArrayOf(2, 3, 0, 1),
        )
        val problem = Problem(2, 2, Array(2) { IntDomain(0, 1) }, listOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0).remap(mapping),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0).remap(mapping),
            symmetry.remap(mapping),
        ))

        val representatives = BacktrackSolver(problem.bake()).enumerate().map { it.ints.toList() }.toSet()

        assertEquals(setOf(listOf(0L, 0L), listOf(0L, 1L), listOf(1L, 1L)), representatives)
    }
}
