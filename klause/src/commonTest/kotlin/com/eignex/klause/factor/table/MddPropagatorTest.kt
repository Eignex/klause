package com.eignex.klause.factor.table

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MddPropagatorTest {

    @Test
    fun `mdd deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x3DD0)
        repeat(60) { iter ->
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 2) }, arrayOf<Factor>(randomMdd(rng, 4, 3, cost = -1)))
            PropagationReasonOracle.assertReasonsImply(problem, "mdd#$iter") { state -> state.narrow(rng, 2, 2) }
        }
    }

    @Test
    fun `cost mdd deductions should be implied by their reasons`() {
        val rng = Random(0x3DD1)
        repeat(60) { iter ->
            val domains = Array(4) { if (it == 3) IntDomain(0, 9) else IntDomain(0, 2) }
            val problem = Problem(0, 4, domains, arrayOf<Factor>(randomMdd(rng, 3, 2, cost = 3)))
            PropagationReasonOracle.assertReasonsImply(problem, "cost-mdd#$iter") { state -> state.narrow(rng, 3, 9) }
        }
    }

    private fun randomMdd(rng: Random, n: Int, width: Int, cost: Int): Mdd {
        val numStatesPerLayer = IntArray(n + 1) { if (it == 0 || it == n) 1 else width }
        val records = ArrayList<Long>()
        val layerStarts = IntArray(n + 1)
        for (layer in 0 until n) {
            layerStarts[layer] = records.size
            for (src in 0 until numStatesPerLayer[layer]) {
                for (symbol in 0L..2L) {
                    if (rng.nextInt(3) == 0) continue
                    records += listOf(src.toLong(), symbol, rng.nextInt(numStatesPerLayer[layer + 1]).toLong())
                    if (cost >= 0) records += rng.nextInt(4).toLong()
                }
            }
        }
        layerStarts[n] = records.size
        return Mdd(
            seq = IntArray(n) { it },
            numStatesPerLayer = numStatesPerLayer,
            layerStarts = layerStarts,
            transitions = records.toLongArray(),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = if (cost >= 0) 4 else 3,
            cost = cost,
        )
    }

    private fun PropagationState.narrow(rng: Random, lastVar: Int, lastTop: Int): Boolean = (0 until 4).all {
        val v = rng.nextInt(lastVar + 1)
        val top = if (v == lastVar) lastTop else 2
        when (rng.nextInt(3)) {
            0 -> excludeIntValue(v, rng.nextInt(top + 1).toLong())
            1 -> tightenIntMin(v, rng.nextInt(top).toLong())
            else -> tightenIntMax(v, 1L + rng.nextInt(top))
        }
    }

    @Test
    fun `an mdd prune should cite only the bounds its cut of the diagram crosses`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(
                Mdd(
                    seq = intArrayOf(0, 1, 2),
                    numStatesPerLayer = intArrayOf(1, 3, 1, 1),
                    layerStarts = intArrayOf(0, 9, 18, 27),
                    transitions = longArrayOf(
                        0, 0, 0, 0, 1, 1, 0, 2, 2,
                        0, 0, 0, 1, 1, 0, 2, 2, 0,
                        0, 0, 0, 0, 1, 0, 0, 2, 0,
                    ),
                    initial = 0,
                    accepting = intArrayOf(0),
                    recordStride = 3,
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 1L) && state.tightenIntMin(2, 1L))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMaxAntecedents[1])!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(listOf(Triple(0, AtomKind.LE, 1L)), cited)
    }
}
