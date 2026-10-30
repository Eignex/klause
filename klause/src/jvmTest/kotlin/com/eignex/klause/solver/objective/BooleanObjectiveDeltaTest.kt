package com.eignex.klause.solver.objective

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Assignment
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class BooleanObjectiveDeltaTest(private val minimize: Boolean) {
    @Test
    fun `a maintained probe should restore its coordinates after scoring soft clauses`() {
        val source = Dimacs.parseWcnf("h 1 3 0\nh -1 -3 0\n7 3 2 0\n11 -3 2 0\n").toProblem()
        val sweep = assertNotNull(DefinitionalSweep.infer(source.problem, boolFolds = source.boolFolds))
        val functional = assertIs<FunctionalObjective>(sweep.functionalObjective(
            intArrayOf(), longArrayOf(), 0L, minimize, intArrayOf(3, 4), longArrayOf(7L, 11L),
        ))
        val state = LocalSearchState(source.problem.bake(), Random(0))
        state.invariants = assertNotNull(DefinitionalSweep.infer(source.problem, boolFolds = listOf(
            BoolFoldDefinition(2, intArrayOf(Lit.make(0, false)), true),
        ))).network(0, 5)
        state.assignment.setBool(2, true)
        state.assignment.setBool(4, true)
        state.recompute()
        state.prepareObjectiveDelta(functional).forEach { }

        val delta = state.objectiveDelta(functional, Move.BoolFlip(0))

        assertEquals(if (minimize) -4.0 else 4.0, delta)
        assertFalse(state.assignment.boolValue(0))
        assertTrue(state.assignment.boolValue(2))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `parsed soft clauses should score their exchanged violation through local search`() {
        val source = Dimacs.parseWcnf("7 1 2 0\n11 -1 2 0\n").toProblem()
        val sweep = assertNotNull(DefinitionalSweep.infer(source.problem, boolFolds = source.boolFolds))
        val functional = assertIs<FunctionalObjective>(
            sweep.functionalObjective(
                intArrayOf(), longArrayOf(), 0L, minimize, intArrayOf(2, 3), longArrayOf(7L, 11L),
            )
        )
        val state = LocalSearchState(source.problem.bake(), Random(0))
        state.prepareObjectiveDelta(functional).forEach { }

        val delta = state.objectiveDelta(functional, Move.BoolFlip(0))

        assertEquals(if (minimize) 4.0 else -4.0, delta)
        assertFalse(state.assignment.boolValue(0))
    }

    @Test
    fun `a flip should score its affected cone within a bounded poll allowance`() {
        val definitions = List(128) { variable ->
            val fold = DefinitionalSweep.SweepNode.BoolFold(128 + variable, intArrayOf(Lit.make(variable, true)), true)
            FunctionalObjective.Definition(fold.out, true, fold) { _, boolOf -> if (boolOf(variable)) 1L else 0L }
        }
        val evaluator = assertNotNull(
            BooleanObjectiveDelta.preparation(
                definitions, IntArray(128) { 128 + it }, LongArray(128) { 7L }, minimize, 256,
            ).filterNotNull().last().evaluator,
        )
        val assignment = Assignment(256, 0)
        var polls = 0

        val delta = evaluator.delta(assignment, Move.BoolFlip(0)) {
            check(++polls <= 4) { "unrelated objective work" }
        }

        assertEquals(if (minimize) 7.0 else -7.0, delta)
        assertFalse(assignment.boolValue(0))
    }

    @Test
    fun `compound parity should preserve cancellation between wide folded and bare costs`() {
        val fold = DefinitionalSweep.SweepNode.BoolFold(
            3,
            intArrayOf(Lit.make(0, true), Lit.make(0, true), Lit.make(1, true)),
            false,
        )
        val definition = FunctionalObjective.Definition(3, true, fold) { _, boolOf ->
            if (boolOf(0) || boolOf(1)) 1L else 0L
        }
        val evaluator = assertNotNull(
            BooleanObjectiveDelta.preparation(
                listOf(definition), intArrayOf(3, 0, 2), longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE, 7L), minimize, 4,
            ).filterNotNull().last().evaluator,
        )
        val assignment = Assignment(4, 0)
        val move = Move.Compound(
            listOf(
                Move.BoolFlip(0), Move.BoolFlip(1), Move.BoolFlip(2), Move.BoolFlip(1),
            )
        )

        val delta = evaluator.delta(assignment, move) {}

        assertEquals(if (minimize) 6.0 else -6.0, delta)
        assertFalse(assignment.boolValue(0))
        assertFalse(assignment.boolValue(1))
        assertFalse(assignment.boolValue(2))
    }

    @Test
    fun `flipping a defined output should not change its input based cost`() {
        val fold = DefinitionalSweep.SweepNode.BoolFold(2, intArrayOf(Lit.make(0, true), Lit.make(1, false)), true)
        val definition = FunctionalObjective.Definition(2, true, fold) { _, boolOf ->
            if (boolOf(0) && !boolOf(1)) 1L else 0L
        }
        val evaluator = assertNotNull(
            BooleanObjectiveDelta.preparation(
                listOf(definition), intArrayOf(2), longArrayOf(9L), minimize, 3,
            ).filterNotNull().last().evaluator,
        )
        val assignment = Assignment(3, 0)
        assignment.setBool(2, true)

        val delta = evaluator.delta(assignment, Move.BoolFlip(2)) {}

        assertEquals(0.0, delta)
        assertTrue(assignment.boolValue(2))
    }

    @Test
    fun `nested objective folds should retain generic evaluation`() {
        val first = DefinitionalSweep.SweepNode.BoolFold(1, intArrayOf(Lit.make(0, true)), true)
        val second = DefinitionalSweep.SweepNode.BoolFold(2, intArrayOf(Lit.make(1, true)), true)
        val definitions = listOf(first, second).map { fold ->
            FunctionalObjective.Definition(fold.out, true, fold) { _, _ -> 0L }
        }

        val prepared = BooleanObjectiveDelta.preparation(
            definitions,
            intArrayOf(2),
            longArrayOf(1L),
            minimize,
            3,
        ).filterNotNull().last()

        assertNull(prepared.evaluator)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "minimize={0}")
        fun parameters(): List<Array<Any>> = listOf(true, false).map { arrayOf<Any>(it) }
    }
}
