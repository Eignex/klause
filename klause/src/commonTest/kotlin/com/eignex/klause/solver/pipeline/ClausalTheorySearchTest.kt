package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClausalTheorySearchTest {
    @Test
    fun `an infeasible Boolean abstraction should refute the source without arithmetic completion`() {
        val open = Bits(2).also { bits -> repeat(2, bits::set) }
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
            ),
        )
        val engine = OpenTheoryPipeline.engineFor(OpenTheoryRequest(model).withPresolve(PresolveConfig.NONE))

        val result = engine.solve(TheoryParams(maxLeaves = 0))

        val stats = assertIs<OpenTheoryResult.Unsat>(result).stats
        assertEquals(1L, stats.smt.completeSearchStages?.get("CLAUSAL_BOOLEAN_UNSAT"))
        assertEquals(null, stats.smt.completeSearchStages?.get("CLAUSAL_CUBE_OPENED"))
    }

    @Test
    fun `refuting every Boolean cube should refute the arithmetic source`() {
        val open = Bits(2).also { bits -> repeat(2, bits::set) }
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        val engine = OpenTheoryPipeline.engineFor(OpenTheoryRequest(model).withPresolve(PresolveConfig.NONE))

        val result = engine.solve()

        val stats = assertIs<OpenTheoryResult.Unsat>(result).stats
        assertEquals(2L, stats.smt.completeSearchStages?.get("CLAUSAL_CUBE_REFUTED"))
        assertEquals(1L, stats.smt.completeSearchStages?.get("CLAUSAL_BOOLEAN_UNSAT"))
    }

    @Test
    fun `a Boolean proposal should publish only a complete arithmetic witness`() {
        val open = Bits(2).also { bits -> repeat(2, bits::set) }
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
                ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        val engine = OpenTheoryPipeline.engineFor(OpenTheoryRequest(model).withPresolve(PresolveConfig.NONE))

        val result = assertIs<OpenTheoryResult.Sat>(engine.solve())

        val sum = result.assignment.intValue(0).toLong() + result.assignment.intValue(1).toLong()
        assertEquals(sum <= 0, result.assignment.boolValue(0))
        assertEquals(sum >= 1, result.assignment.boolValue(1))
        assertTrue(result.assignment.boolValue(0) != result.assignment.boolValue(1))
        assertNotNull(result.stats.smt.completeSearchStages?.get("CLAUSAL_BOOLEAN_SAT"))
    }

    @Test
    fun `an undecided arithmetic cube should remain unexcluded`() {
        val open = Bits(2).also { bits -> repeat(2, bits::set) }
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        for (checks in listOf(0L, 1L)) {
            val engine = OpenTheoryPipeline.engineFor(OpenTheoryRequest(model).withPresolve(PresolveConfig.NONE))

            val result = engine.solve(TheoryParams(maxLeaves = checks))

            val unknown = assertIs<OpenTheoryResult.Unknown>(result)
            assertEquals(TerminationReason.BudgetExhausted, unknown.reason)
            assertNotNull(unknown.stats.smt.completeSearchStages?.get("CLAUSAL_BOOLEAN_SAT"))
            assertEquals(checks, unknown.stats.smt.completeSearchStages?.get("CLAUSAL_CUBE_REFUTED") ?: 0L)
        }
    }

    @Test
    fun `clausal refinement should retain its progress across pauses`() {
        val open = Bits(2).also { bits -> repeat(2, bits::set) }
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        val engine = OpenTheoryPipeline.engineFor(OpenTheoryRequest(model).withPresolve(PresolveConfig.NONE))
        ResumableOpenTheory(engine, TheoryParams()).use { search ->
            var result: OpenTheoryResult? = null
            var slices = 0
            while (result == null && slices < 100) {
                result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 1)
                slices++
            }

            val stats = assertIs<OpenTheoryResult.Unsat>(result).stats
            assertTrue(slices > 1)
            assertEquals(2L, stats.smt.completeSearchStages?.get("CLAUSAL_CUBE_REFUTED"))
        }
    }

    @Test
    fun `Boolean abstraction decisions should spend the containing node allowance`() {
        val open = Bits(2).also { bits -> repeat(2, bits::set) }
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
            ),
        )
        val budget = NodeBudget(1)
        val engine = OpenTheoryPipeline.engineFor(OpenTheoryRequest(model).withPresolve(PresolveConfig.NONE))

        val result = engine.solve(TheoryParams(nodeBudget = budget))

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(result).reason)
        assertEquals(1L, budget.spent)
        assertEquals(1L, result.stats.openTheory.openClauseWork)
    }

    @Test
    fun `cancelling paused refinement should not exclude another cube`() {
        val open = Bits(2).also { bits -> repeat(2, bits::set) }
        val model = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        val engine = OpenTheoryPipeline.engineFor(OpenTheoryRequest(model).withPresolve(PresolveConfig.NONE))
        val params = TheoryParams()
        val state = OpenTheorySolveState(params)
        ResumableOpenTheory(engine, params, state).use { search ->
            assertEquals(null, search.runSlice(Cancellation.Never, Long.MAX_VALUE, 1))
            val completedRefutations = state.smt.snapshot().completeSearchStages?.get("CLAUSAL_CUBE_REFUTED")

            val stopped = search.runSlice(Cancellation { true }, Long.MAX_VALUE, 1)

            val unknown = assertIs<OpenTheoryResult.Unknown>(stopped)
            assertEquals(TerminationReason.Cancelled, unknown.reason)
            assertEquals(completedRefutations,
                unknown.stats.smt.completeSearchStages?.get("CLAUSAL_CUBE_REFUTED"))
        }
    }
}
