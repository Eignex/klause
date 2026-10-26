package com.eignex.klause.meta.alns

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
}
