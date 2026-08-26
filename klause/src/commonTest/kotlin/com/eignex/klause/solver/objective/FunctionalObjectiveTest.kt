package com.eignex.klause.solver.objective

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.lowering.flatzinc.FlatZincProgram
import com.eignex.klause.lowering.flatzinc.parseFlatZinc
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.values
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FunctionalObjectiveTest {
    @Test
    fun `functional objective delta matches evaluate-after minus before`() {
        // objective = |a - b| + |a - c|, minimized. Decomposed via int_abs + int_lin_eq.
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
        val program = parseFlatZinc(src)
        val obj = program.lsObjective
        assertNotNull(obj, "expected a functional objective for the decomposed minimize")
        assertTrue(obj is FunctionalObjective)
        val fo = obj
        // leaf vars should be a,b,c (the decision vars), not the aux/objective vars.
        val aId = program.intVarsByName.getValue("a")
        val bId = program.intVarsByName.getValue("b")
        val cId = program.intVarsByName.getValue("c")
        assertEquals(setOf(aId, bId, cId), fo.leafVars.toSet())

        val rng = Random(7)
        val state = LocalSearchState(program.problem, rng)
        repeat(200) {
            for (i in 0 until program.problem.numIntVars) {
                val dom = program.problem.requireFiniteIntDomains()[i]
                state.assignment.setInt(i, dom.values.valueAt(rng.nextInt(dom.values.size)))
            }
            // Pick a leaf var move.
            val v = listOf(aId, bId, cId).random(rng)
            val dom = program.problem.requireFiniteIntDomains()[v]
            val nv = dom.values.valueAt(rng.nextInt(dom.values.size))
            val move = Move.IntSet(v, nv)
            val before = obj.evaluate(snapshot(state, program))
            val predicted = obj.deltaIfApplied(state.assignment, move)
            state.assignment.setInt(v, nv)
            val after = obj.evaluate(snapshot(state, program))
            assertEquals(
                (after - before),
                predicted,
                1e-9,
                "delta mismatch: before=$before after=$after predicted=$predicted move=$move",
            )
            // True objective at this assignment = |a-b|+|a-c| (minimize → positive).
            val a = state.assignment.intValue(aId)
            val b = state.assignment.intValue(bId)
            val cc = state.assignment.intValue(cId)
            assertEquals(
                (abs(a - b) + abs(a - cc)).toDouble(),
                after,
                1e-9,
                "evaluate != true objective",
            )
        }
    }

    @Test
    fun `bool-count objective descends the literals through array_bool_and`() {
        // maximize bool2int(x1 /\ x2) + bool2int(x3 /\ x4), the indicators functionally defined.
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
        val program = parseFlatZinc(src)
        val obj = program.lsObjective
        assertNotNull(obj, "expected a bool-count functional objective")
        assertTrue(obj is FunctionalObjective)
        val x = intArrayOf(1, 2, 3, 4).map { program.boolVarsByName.getValue("x$it") }
        assertEquals(x.toSet(), obj.boolLeafVars.toSet())

        val rng = Random(11)
        val state = LocalSearchState(program.problem, rng)
        repeat(200) {
            for (b in 0 until program.problem.numBoolVars) state.assignment.setBool(b, rng.nextBoolean())
            val v = x.random(rng)
            val move = Move.BoolFlip(v)
            val before = obj.evaluate(snapshot(state, program))
            val predicted = obj.deltaIfApplied(state.assignment, move)
            state.assignment.flipBool(v)
            val after = obj.evaluate(snapshot(state, program))
            assertEquals(after - before, predicted, 1e-9, "delta mismatch: before=$before after=$after")
            // maximize ⇒ "lower is better" objective is −(count of satisfied ANDs).
            val a1 = state.assignment.boolValue(x[0]) && state.assignment.boolValue(x[1])
            val a2 = state.assignment.boolValue(x[2]) && state.assignment.boolValue(x[3])
            assertEquals(-((if (a1) 1 else 0) + (if (a2) 1 else 0)).toDouble(), after, 1e-9)
        }
    }

    private fun snapshot(state: LocalSearchState, program: FlatZincProgram): Sample {
        val bools = BooleanArray(program.problem.numBoolVars) { state.assignment.boolValue(it) }
        val ints = LongArray(program.problem.numIntVars) { state.assignment.intValue(it) }
        return Sample(bools, ints)
    }
}
