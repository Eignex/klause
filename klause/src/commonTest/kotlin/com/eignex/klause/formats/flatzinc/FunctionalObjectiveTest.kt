package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.FunctionalObjective
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
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
        val fo = obj as FunctionalObjective
        // leaf vars should be a,b,c (the decision vars), not the aux/objective vars.
        val aId = program.intVarsByName["a"]!!
        val bId = program.intVarsByName["b"]!!
        val cId = program.intVarsByName["c"]!!
        assertEquals(setOf(aId, bId, cId), fo.leafVars.toSet())

        val rng = Random(7)
        val state = LocalSearchState(program.problem, rng)
        repeat(200) {
            for (i in 0 until program.problem.numIntVars) {
                val dom = program.problem.intDomains[i]
                state.assignment.setInt(i, dom.valueAt(rng.nextInt(dom.size)))
            }
            // Pick a leaf var move.
            val v = listOf(aId, bId, cId).random(rng)
            val dom = program.problem.intDomains[v]
            val nv = dom.valueAt(rng.nextInt(dom.size))
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
                (kotlin.math.abs(a - b) + kotlin.math.abs(a - cc)).toDouble(),
                after,
                1e-9,
                "evaluate != true objective",
            )
        }
    }

    private fun snapshot(state: LocalSearchState, program: FlatZincProgram): com.eignex.klause.solver.Sample {
        val bools = BooleanArray(program.problem.numBoolVars) { state.assignment.boolValue(it) }
        val ints = IntArray(program.problem.numIntVars) { state.assignment.intValue(it) }
        return com.eignex.klause.solver.Sample(bools, ints)
    }
}
