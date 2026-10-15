package com.eignex.klause.solver.objective

import com.eignex.klause.formats.flatzinc.parseFlatZincExecution
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FunctionalObjectiveTest {
    @Test
    fun `deltaIfApplied should keep wide endpoint differences`() {
        val half = 1L shl 62
        for (minimize in listOf(true, false)) {
            val objective = FunctionalObjective(0, minimize, emptyList(), intArrayOf(0))
            val assignment = Assignment(0, 1)
            assignment.setInt(0, -half)

            val delta = objective.deltaIfApplied(assignment, Move.IntSet(0, half))

            assertEquals(half.toDouble() * if (minimize) 2.0 else -2.0, delta)
            assertEquals(-half, assignment.intValue(0))
        }
    }

    @Test
    fun `deltaIfApplied should keep weighted values beyond Long`() {
        for (minimize in listOf(true, false)) {
            val objective = FunctionalObjective(
                intArrayOf(0),
                longArrayOf(Long.MAX_VALUE),
                0,
                minimize,
                emptyList(),
                intArrayOf(0),
            )
            val assignment = Assignment(0, 1)

            val delta = objective.deltaIfApplied(assignment, Move.IntSet(0, 2))
            assignment.setInt(0, 2)

            val expected = Long.MAX_VALUE.toDouble() * if (minimize) 2.0 else -2.0
            assertEquals(expected, delta)
            assertEquals(expected, objective.evaluate(assignment))
        }
    }

    @Test
    fun `deltaIfApplied should honor repeated compound targets and Boolean parity`() {
        val fold = FunctionalObjective.BoolFold(3, intArrayOf(Lit.make(1, true), Lit.make(2, false)), true)
        val objective = FunctionalObjective(
            intArrayOf(2, 0), longArrayOf(1, 1), 0, true, emptyList(), intArrayOf(0),
            intArrayOf(1), longArrayOf(7), emptyList(), intArrayOf(2),
            listOf(
                FunctionalObjective.Definition(0, true) { intOf, _ -> if (intOf(0) == 1L) 1L else 0L },
                FunctionalObjective.Definition(1, false) { _, boolOf -> if (boolOf(0)) 3L else 0L },
                FunctionalObjective.Definition(1, true) { intOf, _ -> if (intOf(1) == 3L) 1L else 0L },
                FunctionalObjective.Definition(3, true) { _, boolOf -> if (fold.compute(boolOf)) 1L else 0L },
                FunctionalObjective.Definition(2, false) { _, boolOf -> if (boolOf(3)) 5L else 0L },
            ),
        )
        val assignment = Assignment(4, 3)
        assignment.setInt(1, 99)
        assignment.setBool(1, true)
        val move = Move.Compound(
            listOf(
                Move.IntSet(0, 2),
                Move.BoolFlip(2),
                Move.IntSet(0, 1),
                Move.BoolFlip(2),
            ),
        )
        val before = objective.evaluate(assignment)

        val predicted = objective.deltaIfApplied(assignment, move)
        assignment.setInt(0, 1)

        assertEquals(0.0, before)
        assertEquals(13.0, objective.evaluate(assignment))
        assertEquals(13.0, predicted)
        assertEquals(13.0, objective.evaluate(Sample(booleanArrayOf(false, false, false, false), longArrayOf(1, 0, 0))))
    }

    @Test
    fun `deltaIfApplied should match the evaluated difference`() {
        val src = """
            var 0..20: a; var 0..20: b; var 0..20: c;
            var -20..20: d1; var -20..20: d2;
            var 0..40: ad1; var 0..40: ad2;
            var 0..80: objective;
            constraint int_lin_eq([1,-1,-1],[a,b,d1],0):: defines_var(d1);
            constraint int_lin_eq([1,-1,-1],[a,c,d2],0):: defines_var(d2);
            constraint int_abs(d1, ad1):: defines_var(ad1);
            constraint int_abs(d2, ad2):: defines_var(ad2);
            constraint int_lin_eq([1,-1,-1],[objective,ad1,ad2],0):: defines_var(objective);
            solve minimize objective;
        """.trimIndent()
        val execution = parseFlatZincExecution(src)
        val program = execution.program
        val obj = assertIs<FunctionalObjective>(execution.localSearchObjective)
        val (a, b, c) = listOf("a", "b", "c").map { program.intVarsByName.getValue(it) }
        val assignment = Assignment(program.problem.numBoolVars, program.problem.numIntVars)
        assignment.setInt(a, 5)
        assignment.setInt(b, 2)
        assignment.setInt(c, 9)

        val predicted = obj.deltaIfApplied(assignment, Move.IntSet(a, 10))
        assignment.setInt(a, 10)

        assertEquals(setOf(a, b, c), obj.leafVars.toSet())
        assertEquals(2.0, predicted, 1e-9)
        assertEquals(9.0, obj.evaluate(assignment), 1e-9)
    }

    @Test
    fun `bool count objective should descend to literals through array_bool_and`() {
        val src = """
            var bool: x1; var bool: x2; var bool: x3; var bool: x4;
            var bool: a1; var bool: a2;
            var 0..1: t1; var 0..1: t2;
            var 0..2: obj;
            constraint array_bool_and([x1,x2], a1):: defines_var(a1);
            constraint array_bool_and([x3,x4], a2):: defines_var(a2);
            constraint bool2int(a1, t1):: defines_var(t1);
            constraint bool2int(a2, t2):: defines_var(t2);
            constraint int_lin_eq([1,1,-1],[t1,t2,obj],0):: defines_var(obj);
            solve maximize obj;
        """.trimIndent()
        val execution = parseFlatZincExecution(src)
        val program = execution.program
        val obj = assertIs<FunctionalObjective>(execution.localSearchObjective)
        val x = intArrayOf(1, 2, 3, 4).map { program.boolVarsByName.getValue("x$it") }
        val assignment = Assignment(program.problem.numBoolVars, program.problem.numIntVars)
        for (v in x.take(3)) assignment.setBool(v, true)

        val predicted = obj.deltaIfApplied(assignment, Move.BoolFlip(x[3]))
        assignment.flipBool(x[3])

        assertEquals(x.toSet(), obj.boolLeafVars.toSet())
        assertEquals(-1.0, predicted, 1e-9)
        assertEquals(-2.0, obj.evaluate(assignment), 1e-9)
    }
}
