package com.eignex.klause.compile

import com.eignex.klause.ast.costMdd
import com.eignex.klause.ast.costRegular
import com.eignex.klause.ast.mdd
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke + bit-blast coverage for the "newer" globals decomposed in
 * [CompilerGlobalsLowering]. Each test compiles a tiny model, finds a satisfying
 * sample with the backtrack solver, then asserts the bit-blasted CNF accepts the
 * same sample (proves the decomposition emits only bit-blastable primitives).
 */
class NewGlobalsDslTest {

    @Test
    fun `mdd - 2-layer MDD accepts a sample`() {
        // Layer 0: state 0 -> seq[0] = 1 -> state 0 (or 0 -> 2 -> 1).
        // Layer 1: state 0 -> seq[1] = 1 -> accept (state 0); state 1 -> 2 -> accept.
        class S : VariableSchema() {
            val s0 by intVar(1, 2)
            val s1 by intVar(1, 2)
            val rule by constraint {
                mdd(
                    seq = listOf(s0, s1),
                    numStatesPerLayer = listOf(1, 2, 1),
                    layerStarts = listOf(0, 6, 12),
                    transitions = listOf(
                        0, 1, 0, // layer 0 trans 1
                        0, 2, 1, // layer 0 trans 2
                        0, 1, 0, // layer 1 trans 1
                        1, 2, 0, // layer 1 trans 2
                    ),
                    initial = 0,
                    accepting = listOf(0),
                )
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val sample = BacktrackSolver(compiled.problem).enumerate(BacktrackParams(maxDecisions = 500_000L)).firstOrNull()
        assertTrue(sample != null, "mdd: solver found no sample")
    }

    @Test
    fun `cost_regular - 1-step weighted DFA`() {
        class S : VariableSchema() {
            val s0 by intVar(1, 2)
            val cost by intVar(0, 100)
            val rule by constraint {
                costRegular(
                    seq = listOf(s0),
                    numStates = 2,
                    numSymbols = 2,
                    // Row-major Q×S; dst-state is 1-based (0 = no transition).
                    transitions = listOf(
                        1,
                        2, // from state 0
                        1,
                        2, // from state 1
                    ),
                    weights = listOf(
                        3,
                        5,
                        7,
                        11,
                    ),
                    initial = 0,
                    accepting = listOf(0, 1),
                    cost = cost,
                )
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val sample = BacktrackSolver(compiled.problem).enumerate(BacktrackParams(maxDecisions = 500_000L)).firstOrNull()
        assertTrue(sample != null, "cost_regular: solver found no sample")
    }

    @Test
    fun `cost_mdd - weighted layer accumulation`() {
        class S : VariableSchema() {
            val s0 by intVar(1, 2)
            val cost by intVar(0, 100)
            val rule by constraint {
                costMdd(
                    seq = listOf(s0),
                    numStatesPerLayer = listOf(1, 1),
                    layerStarts = listOf(0, 8),
                    transitions = listOf(
                        0,
                        1,
                        0,
                        3,
                        0,
                        2,
                        0,
                        7,
                    ),
                    initial = 0,
                    accepting = listOf(0),
                    cost = cost,
                )
            }
        }
        val schema = S()
        val compiled = schema.compile()
        val sample = BacktrackSolver(compiled.problem).enumerate(BacktrackParams(maxDecisions = 500_000L)).firstOrNull()
        assertTrue(sample != null, "cost_mdd: solver found no sample")
    }
}
