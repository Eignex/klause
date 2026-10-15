package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GridArithmeticTest {
    @Test
    fun `product rounding preserves independent input and output grids`() {
        val relation = GridProduct(GridCoordinate(0, -1.0, 1.0, 5), GridCoordinate(1, -1.0, 1.0, 5),
            GridCoordinate(2, 0.0, 1.0, 5))

        assertEquals(1L, relation.image { 1L })
        assertNull(relation.image { if (it == 0) 1L else 3L })
    }

    @Test
    fun `square filtering retains domain holes through retracted output pins`() {
        val relation = GridProduct(GridCoordinate(0, -2.0, 2.0, 5), GridCoordinate(0, -2.0, 2.0, 5),
            GridCoordinate(1, 0.0, 4.0, 5))
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 4).excludeValue(0), IntDomain(0, 4)),
            arrayOf<Factor>(relation))
        val session = PropagationSession(problem)
        session.seed(Assumptions.None)

        assertIs<PropagationResult.Implied>(session.pinInt(1, 4))
        assertEquals(IntDomain(4, 4), session.intDomain(0))
        session.popLast()
        assertIs<PropagationResult.Implied>(session.pinInt(1, 1))
        assertEquals(IntDomain(1, 3).excludeValue(2), session.intDomain(0))
    }

    @Test
    fun `grid power result aliases retain only rounded fixed points`() {
        val coordinate = GridCoordinate(0, -2.0, 2.0, 5)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 4)), arrayOf<Factor>(GridPower(coordinate, 2, coordinate)))

        val points = BacktrackSolver(problem.bake()).enumerate(BacktrackParams(maxDecisions = 20))
            .map { it.intValue(0) }.toSet()

        assertEquals(setOf(2L, 3L), points)
    }

    @Test
    fun `grid arithmetic move deltas replace every aliased role`() {
        val coordinate = GridCoordinate(0, -2.0, 2.0, 5)
        val output = GridCoordinate(1, 0.0, 4.0, 5)
        for (factor in listOf<Factor>(GridProduct(coordinate, coordinate, output),
            GridProduct(coordinate, coordinate, coordinate), GridPower(coordinate, 3, output),
            GridPower(coordinate, 2, coordinate))) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 4) }, arrayOf(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
            for ((old, target) in listOf(0L to 2L, 1L to 3L, 4L to 1L)) {
                state.assignment.setInt(0, old)
                state.assignment.setInt(1, 2L)
                state.recompute()
                val before = state.cost
                val predicted = state.netDelta(Move.IntSet(0, target))
                state.assignment.setInt(0, target)
                val applied = state.factors[0].applyIntSet(state, 0, 0, old)
                state.recompute()

                assertEquals(state.cost - before, predicted)
                assertEquals(predicted, applied.toLong())
            }
        }
    }

    @Test
    fun `grid remapping retains each coordinate range and operation`() {
        val relation = GridProduct(GridCoordinate(0, -2.0, 2.0, 5), GridCoordinate(1, 0.0, 1.0, 3),
            GridCoordinate(2, -2.0, 2.0, 7))
        val mapping = VarRemap(intArrayOf(), intArrayOf(3, 3, 4))
        val remapped = relation.remap(mapping) as GridProduct

        assertEquals(3, remapped.a.variable)
        assertEquals(3, remapped.b.variable)
        assertEquals(5, remapped.a.buckets)
        assertEquals(3, remapped.b.buckets)
        assertEquals(relation.remapStructuralHash(mapping), remapped.structuralKey().hashCode())
    }
}
