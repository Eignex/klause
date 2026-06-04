package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The model-wide definitional sweep built from `:: defines_var` annotations: evaluation order,
 * domain clamping, and the local-search restart integration. The miniature model mirrors the
 * fast-food shape — decision vars feeding an abs → min → linear-sum DAG — which the LS engine
 * previously had to hand-repair move by move.
 */
class DefinitionalSweepTest {

    // x, y decisions; a = |x - 7| and b = |y - 2| via lin_eq+abs; m = min(a, b); s = a + b.
    private val src = """
        var 0..10: x;
        var 0..10: y;
        var -10..10: dx;
        var -10..10: dy;
        var 0..10: a;
        var 0..10: b;
        var 0..10: m;
        var 0..20: s;
        constraint int_lin_eq([1, -1], [x, dx], 7) :: defines_var(dx);
        constraint int_lin_eq([1, -1], [y, dy], 2) :: defines_var(dy);
        constraint int_abs(dx, a) :: defines_var(a);
        constraint int_abs(dy, b) :: defines_var(b);
        constraint int_min(a, b, m) :: defines_var(m);
        constraint int_lin_eq([1, 1, -1], [a, b, s], 0) :: defines_var(s);
        constraint int_le(s, 6);
        solve satisfy;
    """.trimIndent()

    @Test
    fun `builds the full DAG and evaluates defined vars from decisions`() {
        val program = parseFlatZinc(src)
        val sweep = assertNotNull(program.definitionalSweep, "defines_var model must yield a sweep")
        assertEquals(6, sweep.size, "all six definitional constraints are evaluable")
        val xId = program.intVarsByName.getValue("x")
        val yId = program.intVarsByName.getValue("y")
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(program.problem, kotlin.random.Random(1))
        state.assignment.setInt(xId, 10) // dx = 3, a = 3
        state.assignment.setInt(yId, 0) // dy = -2, b = 2
        state.recompute()
        val costBefore = state.cost
        sweep.sweep(state.assignment, program.problem.intDomains, program.problem.factors)
        assertEquals(3, state.assignment.intValue(program.intVarsByName.getValue("a")))
        assertEquals(2, state.assignment.intValue(program.intVarsByName.getValue("b")))
        assertEquals(2, state.assignment.intValue(program.intVarsByName.getValue("m")))
        assertEquals(5, state.assignment.intValue(program.intVarsByName.getValue("s")))
        state.recompute()
        // The annotated DAG and its reification bools are satisfied by evaluation. klause's own
        // abs/min lowering introduces internal aux vars outside the annotation graph, so a small
        // residual may remain — the contract is "collapse the definitional mass", with stragglers
        // left to the engine's greedy repair (see the end-to-end test below).
        assertTrue(
            state.cost < costBefore,
            "sweep must collapse the definitional violations (before=$costBefore after=${state.cost}; " +
                "violated=${state.violated.toIntArray().map { state.factors[it]::class.simpleName }})",
        )
    }

    @Test
    fun `out-of-domain results are clamped never fabricated`() {
        // s declared 0..3 but a+b can reach 20: the sweep clamps s to 3 and the lin_eq factor
        // stays violated — the sweep must not write out-of-domain values to force feasibility.
        val tight = src.replace("var 0..20: s;", "var 0..3: s;")
        val program = parseFlatZinc(tight)
        val sweep = assertNotNull(program.definitionalSweep)
        val state = com.eignex.klause.solver.localsearch.LocalSearchState(program.problem, kotlin.random.Random(1))
        state.assignment.setInt(program.intVarsByName.getValue("x"), 0) // a = 7
        state.assignment.setInt(program.intVarsByName.getValue("y"), 10) // b = 8 -> s would be 15
        sweep.sweep(state.assignment, program.problem.intDomains, program.problem.factors)
        assertEquals(3, state.assignment.intValue(program.intVarsByName.getValue("s")), "clamped into domain")
        state.recompute()
        assertTrue(state.cost > 0, "the unsatisfiable-by-domain definition stays a violation")
    }

    @Test
    fun `model without defines_var yields no sweep`() {
        val plain = """
            var 0..3: x;
            var 0..3: y;
            constraint int_le(x, y);
            solve satisfy;
        """.trimIndent()
        assertNull(parseFlatZinc(plain).definitionalSweep)
    }

    @Test
    fun `local search with the sweep solves the decomposed model`() {
        val program = parseFlatZinc(src)
        val solver = LocalSearchSolver(program.problem, definitionalSweep = program.definitionalSweep)
        val r = solver.solve(LocalSearchParams(maxFlips = 50_000, randomSeed = 3L))
        assertTrue(r is SolveResult.Sat, "decomposed model must be satisfiable under the sweep; got $r")
    }
}
