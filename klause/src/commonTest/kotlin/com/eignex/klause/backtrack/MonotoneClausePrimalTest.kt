package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonotoneClausePrimalTest {
    @Test
    fun `greedy feasible assumptions preserve hard clauses and improve cost`() {
        val problem = Problem(
            3, 0, emptyArray(),
            arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(2, false))),
            ),
        ).bake()
        val objective = LinearObjective(boolWeights = longArrayOf(1L, 2L, 0L))
        val primal = assertNotNull(MonotoneClausePrimal.create(
            BacktrackSolver(problem), objective, BacktrackParams(), Cancellation.Never,
        ))
        primal.use {
            val sample = assertNotNull(it.advance())
            assertEquals(1L, objective.evaluateLong(sample))
            assertTrue(problem.factors.all { factor ->
                (factor as Clause).literals.any { literal ->
                    Lit.evaluate(literal, sample.bools[Lit.variable(literal)])
                }
            })
            while (!it.isDone) it.advance()
        }
    }

    @Test
    fun `relaxation channels retain the necessary source cost`() {
        val solver = BacktrackSolver(Problem(
            4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
            ),
        ).bake())
        val objective = LinearObjective(boolWeights = longArrayOf(0L, 0L, 1L, 2L))
        val primal = assertNotNull(MonotoneClausePrimal.create(
            solver, objective, BacktrackParams(), Cancellation.Never,
        ))
        primal.use {
            val sample = assertNotNull(it.advance())
            assertEquals(1L, objective.evaluateLong(sample))
            assertTrue(sample.bools[0] || sample.bools[1])
        }
    }

    @Test
    fun `polishing preserves caller pins on free variables`() {
        val solver = BacktrackSolver(Problem(
            3, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(2, false))),
            ),
        ).bake())
        val objective = LinearObjective(boolWeights = longArrayOf(1L, 2L, 0L))
        val primal = assertNotNull(MonotoneClausePrimal.create(
            solver, objective, BacktrackParams(assumptions = Assumptions(bools = mapOf(2 to true))), Cancellation.Never,
        ))
        primal.use {
            val sample = assertNotNull(it.advance())
            assertEquals(2L, objective.evaluateLong(sample))
            assertTrue(sample.bools[2])
        }
    }

    @Test
    fun `a feasibility trial resumes across small work slices`() {
        val solver = BacktrackSolver(Problem(
            12, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        ).bake())
        val objective = LinearObjective(boolWeights = longArrayOf(1L))
        val primal = assertNotNull(MonotoneClausePrimal.create(
            solver, objective, BacktrackParams(), Cancellation.Never,
        ))
        primal.use { probe ->
            var sample = probe.advance(sliceNodes = 1L)
            repeat(30) { if (sample == null && !probe.isDone) sample = probe.advance(sliceNodes = 1L) }
            assertEquals(0L, objective.evaluateLong(assertNotNull(sample)))
        }
    }

    @Test
    fun `negative occurrence of a costly variable disables the probe`() {
        val solver = BacktrackSolver(Problem(
            1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, false)))),
        ).bake())
        assertNull(MonotoneClausePrimal.create(
            solver, LinearObjective(boolWeights = longArrayOf(1L)), BacktrackParams(), Cancellation.Never,
        ))
    }

    @Test
    fun `pinned costly variables disable the probe`() {
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())
        assertNull(MonotoneClausePrimal.create(
            solver, LinearObjective(boolWeights = longArrayOf(1L)),
            BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to true))), Cancellation.Never,
        ))
    }

    @Test
    fun `cancelled probe publishes no candidate`() {
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())
        val primal = assertNotNull(MonotoneClausePrimal.create(
            solver, LinearObjective(boolWeights = longArrayOf(1L)), BacktrackParams(), Cancellation { true },
        ))
        primal.use { assertNull(it.advance()) }
    }
}
