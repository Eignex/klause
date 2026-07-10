package com.eignex.klause.solver.integration

import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
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
class FznDefinitionalSweepTest {

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
        val state = LocalSearchState(program.problem, Random(1))
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
        val state = LocalSearchState(program.problem, Random(1))
        state.assignment.setInt(program.intVarsByName.getValue("x"), 0) // a = 7
        state.assignment.setInt(program.intVarsByName.getValue("y"), 10) // b = 8 -> s would be 15
        sweep.sweep(state.assignment, program.problem.intDomains, program.problem.factors)
        assertEquals(3, state.assignment.intValue(program.intVarsByName.getValue("s")), "clamped into domain")
        state.recompute()
        assertTrue(state.cost > 0, "the unsatisfiable-by-domain definition stays a violation")
    }

    @Test
    fun `bool-shaped definitions evaluate reifs bool2int and element`() {
        // The prize-collecting pattern: a comparison reification feeding bool2int feeding a sum,
        // plus a var-index element access.
        val src2 = """
            var 1..3: u;
            var 1..3: v;
            var bool: r;
            var 0..1: ri;
            var 0..10: t;
            var 1..3: i;
            var 0..9: e;
            constraint int_eq_reif(u, v, r) :: defines_var(r);
            constraint bool2int(r, ri) :: defines_var(ri);
            constraint int_lin_eq([1, 1, -1], [u, ri, t], 0) :: defines_var(t);
            constraint array_int_element(i, [7, 8, 9], e) :: defines_var(e);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src2)
        val sweep = assertNotNull(program.definitionalSweep)
        assertEquals(4, sweep.size, "reif + bool2int + lin + element must all build")
        val state = LocalSearchState(program.problem, Random(2))
        val iv = program.intVarsByName
        state.assignment.setInt(iv.getValue("u"), 2)
        state.assignment.setInt(iv.getValue("v"), 2) // r = true, ri = 1, t = u + ri = 3
        state.assignment.setInt(iv.getValue("i"), 3) // e = 9
        sweep.sweep(state.assignment, program.problem.intDomains, program.problem.factors)
        assertEquals(1, state.assignment.intValue(iv.getValue("ri")))
        assertEquals(3, state.assignment.intValue(iv.getValue("t")))
        assertEquals(9, state.assignment.intValue(iv.getValue("e")))
        // Flip v so the reif goes false and the chain re-evaluates.
        state.assignment.setInt(iv.getValue("v"), 1)
        sweep.sweep(state.assignment, program.problem.intDomains, program.problem.factors)
        assertEquals(0, state.assignment.intValue(iv.getValue("ri")))
        assertEquals(2, state.assignment.intValue(iv.getValue("t")))
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

    @Test
    fun `cyclic definitions are dropped while the acyclic remainder survives`() {
        // a and b define each other — a definitional cycle (the prize-collecting pos/next
        // shape in miniature). A cycle has no topological order: one-pass sweep evaluation
        // reads stale values and the invariant network would search-exclude vars it cannot
        // maintain. The re-entered definition must be dropped (it stays a searched factor);
        // the other cycle member and the independent definition keep their nodes.
        val cyclic = """
            var 0..10: x;
            var 0..10: a;
            var 0..10: b;
            var 0..10: c;
            constraint int_lin_eq([1, -1], [b, a], 1) :: defines_var(a);
            constraint int_lin_eq([1, -1], [a, b], -1) :: defines_var(b);
            constraint int_lin_eq([1, -1], [x, c], 3) :: defines_var(c);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(cyclic)
        val sweep = assertNotNull(program.definitionalSweep, "the acyclic definitions must still yield a sweep")
        assertEquals(2, sweep.size, "one cycle member is dropped; the other and the independent def survive")
        val net = sweep.network(program.problem.numIntVars, program.problem.numBoolVars)
        val aId = program.intVarsByName.getValue("a")
        val bId = program.intVarsByName.getValue("b")
        val cId = program.intVarsByName.getValue("c")
        assertTrue(!net.isDefinedInt(aId), "the re-entered cycle member must stay searched")
        assertTrue(net.isDefinedInt(bId), "the surviving cycle member is defined via the now-free input")
        assertTrue(net.isDefinedInt(cId), "the independent definition is unaffected")
    }
}
