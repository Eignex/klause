package com.eignex.klause.portfolio

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals

class BacktrackWorkerConfigTest {

    private fun signalsOf(label: String, objective: LinearObjective?, pools: SharedPools): Set<Signal> {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality(IntArray(3) { Lit.make(it, true) }, min = 1, max = 3)),
        ).bake()
        return BacktrackWorkerConfig(BacktrackCatalog.byLabel(label)).materialize(
            problem,
            index = 0,
            armId = 0,
            seed = 0L,
            lsLambda = 1.0,
            objective = objective,
            lsObjective = null,
            definitionalSweep = null,
            onEvent = null,
            pools = pools,
        ).use { it.signals }
    }

    private fun allPools() = SharedPools(
        clauses = SharedClausePool(),
        cuts = SharedCutPool(),
        bounds = SharedObjectiveBound(),
        varBounds = SharedVarBounds(0),
    )

    @Test
    fun `an arm earns cut uses only when it builds a relaxation to separate cuts in`() {
        for ((label, earnsCuts) in listOf("lp-default" to true, "conflictDriven" to false)) {
            val signals = signalsOf(label, LinearObjective(boolWeights = longArrayOf(1L, 2L, 3L)), allPools())

            assertEquals(earnsCuts, Signal.CutUses in signals, label)
        }
    }

    @Test
    fun `an optimizing arm earns the signal of every pool it shares through`() {
        val signals = signalsOf("lp-default", LinearObjective(boolWeights = longArrayOf(1L, 2L, 3L)), allPools())

        assertEquals(SEARCH_SIGNALS + setOf(Signal.ClauseUses, Signal.CutUses, Signal.Floor, Signal.BoundUses), signals)
    }

    @Test
    fun `a satisfying arm earns no objective floor or bound uses`() {
        val signals = signalsOf("conflictDriven", objective = null, allPools())

        assertEquals(SEARCH_SIGNALS + Signal.ClauseUses, signals)
    }
}
