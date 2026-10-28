package com.eignex.klause.meta.alns

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.ElementResultDefinition
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepairBlockClosureTest {
    @Test
    fun `output cones should keep unrelated envelope inputs pinned`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 100) }, emptyArray()).bake()
        val network = InvariantNetwork(listOf(
            DefinitionalSweep.SweepNode.IntDef(FunctionalObjective.Plus(2,
                FunctionalObjective.Operand.v(0), FunctionalObjective.Operand.v(1)), intArrayOf(0, 1)),
            DefinitionalSweep.SweepNode.IntDef(FunctionalObjective.Times(3,
                FunctionalObjective.Operand.v(2), FunctionalObjective.Operand.v(2)), intArrayOf(2)),
        ), 4, 0)
        val selector = SemanticBlockRemoval(SemanticBlockRemoval.Kind.Assignment, network)

        val closed = selector.closeControl(problem, FreedVars(intArrayOf(), intArrayOf(0)),
            Assumptions.None, Cancellation.Never)

        assertContentEquals(intArrayOf(0, 2, 3), closed.ints)
        assertEquals(1, selector.lastSearchedInputs)
    }

    @Test
    fun `selected defined roles should reach every searched ancestor`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 10) }, emptyArray()).bake()
        val network = InvariantNetwork(listOf(DefinitionalSweep.SweepNode.IntDef(FunctionalObjective.Plus(2,
            FunctionalObjective.Operand.v(0), FunctionalObjective.Operand.v(1)), intArrayOf(0, 1))), 3, 0)
        val selector = SemanticBlockRemoval(SemanticBlockRemoval.Kind.Assignment, network)

        val closed = selector.closeControl(problem, FreedVars(intArrayOf(), intArrayOf(2)),
            Assumptions.None, Cancellation.Never)

        assertContentEquals(intArrayOf(0, 1, 2), closed.ints)
        assertEquals(2, selector.lastSearchedInputs)
    }

    @Test
    fun `alternative element cells should keep their output free`() {
        val factor = Element(0, 3, longArrayOf(1, 2), true, 0)
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 10) }, arrayOf(factor)).bake()
        val selector = SemanticBlockRemoval(SemanticBlockRemoval.Kind.Assignment,
            InvariantNetwork(listOf(ElementResultDefinition(factor)), 4, 0))

        val closed = selector.closeControl(problem, FreedVars(intArrayOf(), intArrayOf(2)),
            Assumptions.None, Cancellation.Never)

        assertContentEquals(intArrayOf(2, 3), closed.ints)
    }

    @Test
    fun `pinned dependent outputs should decline the source block`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 10) }, emptyArray()).bake()
        val network = InvariantNetwork(listOf(DefinitionalSweep.SweepNode.IntDef(FunctionalObjective.Plus(1,
            FunctionalObjective.Operand.v(0), FunctionalObjective.Operand.c(1)), intArrayOf(0))), 2, 0)
        val selector = SemanticBlockRemoval(SemanticBlockRemoval.Kind.Assignment, network)

        val closed = selector.closeControl(problem, FreedVars(intArrayOf(), intArrayOf(0)),
            Assumptions(ints = mapOf(1 to 2L)), Cancellation.Never)

        assertTrue(closed.isEmpty)
        assertEquals(RepairBlockDecline.BlockedPin, selector.lastDecline)
    }
}
