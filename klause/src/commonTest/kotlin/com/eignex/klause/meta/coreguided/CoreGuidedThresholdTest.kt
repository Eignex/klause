package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CoreGuidedThresholdTest {
    @Test
    fun `successive frontiers equal the count including complemented literals`() {
        val literals = intArrayOf(Lit.make(0, false), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true))
        val counter = CoreGuidedThreshold(literals)
        val clauses = ArrayList<Clause>()
        var nextBool = literals.size
        for (minimum in 2..literals.size) {
            var frontier: Int? = null
            while (frontier == null) {
                frontier = counter.advance(minimum, { Lit.make(nextBool++, true) }, { clauses += Clause(it) }, {})
            }
            val solver = BacktrackSolver(Problem(nextBool, 0, emptyArray(), clauses).bake())
            for (bits in 0 until (1 shl literals.size)) {
                val pins = (literals.indices).associateWith { bits and (1 shl it) != 0 }

                val result = solver.solve(BacktrackParams(assumptions = Assumptions(bools = pins)))

                val sample = assertIs<SolveResult.Sat>(result).assignment
                val count = literals.count { Lit.evaluate(it, pins.getValue(Lit.variable(it))) }
                assertEquals(count >= minimum, sample.boolValue(Lit.variable(frontier)))
                val opposite = pins + (Lit.variable(frontier) to (count < minimum))
                assertIs<SolveResult.Unsat>(solver.solve(BacktrackParams(assumptions = Assumptions(bools = opposite))))
            }
        }
    }
}
