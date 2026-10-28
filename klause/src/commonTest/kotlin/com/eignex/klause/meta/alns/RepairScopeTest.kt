package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RepairScopeTest {
    @Test
    fun `definition closure should precede caller pin filtering`() {
        val problem = Problem(3, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(1, intArrayOf(Lit.make(0, true)), true),
            BoolFoldDefinition(2, intArrayOf(Lit.make(1, false)), true),
        )))
        val caller = Assumptions(bools = mapOf(1 to false), ints = mapOf(0 to 1L))

        val freed = assertNotNull(RepairScope.effective(
            FreedVars(intArrayOf(2), intArrayOf(0)), caller, sweep, Cancellation.Never,
        ))

        assertContentEquals(intArrayOf(2, 0), freed.bools)
        assertContentEquals(intArrayOf(), freed.ints)
    }

    @Test
    fun `cancelled closure should decline the whole requested scope`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(1, intArrayOf(Lit.make(0, true)), true),
        )))

        assertNull(RepairScope.effective(FreedVars(intArrayOf(1), intArrayOf()), Assumptions.None,
            sweep, Cancellation { true }))
    }

    @Test
    fun `exact pinned controls should distinguish individual freedoms from an exchange`() {
        val center = Sample(booleanArrayOf(), longArrayOf(1, 0, 0))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0, 0))
        for (factor in listOf<Factor>(AllDifferent(intArrayOf(0, 1)),
            Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            Diffn(intArrayOf(0, 1), intArrayOf(2, 2), longArrayOf(1, 1), longArrayOf(1, 1)))) {
            val problem = Problem(0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 0)),
                arrayOf(factor)).bake()
            for ((requested, expected) in listOf(intArrayOf(0) to 1.0, intArrayOf(1) to 1.0,
                intArrayOf(0, 1) to 0.0)) {
                val freed = assertNotNull(RepairScope.effective(FreedVars(intArrayOf(), requested),
                    Assumptions.None, null, Cancellation.Never))
                val params = BacktrackParams(assumptions = RepairScope.pins(problem, center, freed), maxDecisions = 100)

                val result = assertIs<MinimizeResult.Optimal>(BacktrackSolver(problem).minimize(objective, params))

                assertEquals(expected, result.objective)
            }
        }
    }
}
