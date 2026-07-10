package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.flatzinc.FlatZincParseException
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.writeFlatZincSolution
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlatZincParseTest {

    @Test
    fun `bool var with clause constraint`() {
        val src = """
            var bool: x;
            var bool: y;
            constraint bool_clause([x, y], []);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(2, program.problem.numBoolVars)
        assertEquals(SolveDirective.Satisfy, program.solve)
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(sat.assignment.bools[0] || sat.assignment.bools[1])
    }

    @Test
    fun `int range and linear le`() {
        val src = """
            var 0..10: x;
            var 0..10: y;
            constraint int_lin_le([1, 1], [x, y], 5);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(2, program.problem.numIntVars)
        val sample = LocalSearchSolver(program.problem)
            .sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        assertTrue(sample.ints[0] + sample.ints[1] <= 5)
    }

    @Test
    fun `solve minimize references int objective`() {
        val src = """
            var 1..10: cost;
            constraint int_lin_ge([1], [cost], 3);
            solve minimize cost;
        """.trimIndent()
        val program = parseFlatZinc(
            src.replace("int_lin_ge", "int_lin_le").replace("[1]", "[-1]").replace(", 3", ", -3"),
        )
        // FlatZinc has no `int_lin_ge` natively; encoded as negated LE.
        val solve = assertIs<SolveDirective.Minimize>(program.solve)
        assertEquals("cost", solve.objVar)
        assertEquals(SolveDirective.ObjKind.Int, solve.kind)
    }

    @Test
    fun `scalar var alias is bound to its target not left a free var`() {
        // `var T: name = <var>;` aliases name to another var. The compiler used to drop the
        // binding and allocate a fresh, disconnected var — so an aliased objective output var
        // floated at its domain minimum regardless of the real objective (#478). Here `obj`
        // aliases the int_max output `m`; after solving, both must hold the same value.
        val src = """
            var 0..100: a;
            var 0..100: b;
            var 1..101: m :: is_defined_var;
            var 1..101: obj = m;
            constraint int_eq(a, 30);
            constraint int_eq(b, 40);
            constraint int_max(a, b, m) :: defines_var(m);
            solve satisfy;
            output ["obj=", show(obj), " m=", show(m)];
        """.trimIndent()
        val program = parseFlatZinc(src)
        // The alias shares its target's var id — no fresh var allocated for `obj`.
        assertEquals(program.intVarsByName["m"], program.intVarsByName["obj"])
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment!!
        val obj = sample.ints[program.intVarsByName.getValue("obj")]
        assertEquals(40, obj, "aliased obj must equal max(30,40)=40, got $obj")
        assertTrue(writeFlatZincSolution(program, sample).contains("obj=40 m=40"))
    }

    @Test
    fun `scalar int alias narrows the shared domain`() {
        // An alias may declare a tighter range than its target; that range must constrain the
        // shared variable (widening would be unsound).
        val src = """
            var 0..100: x;
            var 0..5: y = x;
            constraint int_lin_le([-1], [x], -3);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment!!
        val v = sample.ints[program.intVarsByName.getValue("x")]
        assertTrue(v in 3..5, "x must be in 3..5 (alias y:0..5 ∧ x>=3), got $v")
    }

    @Test
    fun `output renders default when no output clause`() {
        val src = """
            var bool: x;
            constraint bool_clause([x], []);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment!!
        val rendered = writeFlatZincSolution(program, sample)
        assertTrue(rendered.contains("x = true"), "got: $rendered")
        assertTrue(rendered.contains("----------"))
    }

    @Test
    fun `output renders custom output items`() {
        val src = """
            var 0..5: a;
            var 0..5: b;
            constraint int_lin_eq([1, 1], [a, b], 3);
            solve satisfy;
            output ["a=", show(a), " b=", show(b), "\n"];
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment!!
        val rendered = writeFlatZincSolution(program, sample)
        // Result should look like "a=N b=M\n----------\n"
        assertTrue(rendered.startsWith("a="), "got: $rendered")
        assertTrue(rendered.contains(" b="), "got: $rendered")
        assertTrue(rendered.contains("----------"))
        assertEquals(3, sample.ints[0] + sample.ints[1])
    }

    @Test
    fun `parameter array used as coefficients`() {
        val src = """
            array [1..3] of int: coefs = [2, 3, 1];
            var 0..5: a;
            var 0..5: b;
            var 0..5: c;
            constraint int_lin_le(coefs, [a, b, c], 10);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = LocalSearchSolver(program.problem)
            .sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 0L)).assignment
        assertNotNull(sample)
        assertTrue(2 * sample.ints[0] + 3 * sample.ints[1] + sample.ints[2] <= 10)
    }

    @Test
    fun `count_eq over a par int array counts the constant matches`() {
        // MiniZinc emits `count` over a fixed array as a var-array builtin whose array is a par
        // constant array; each constant must be coerced to a fixed var. Here [1,2,2,3] has two 2s.
        val src = """
            array [1..4] of int: xs = [1, 2, 2, 3];
            var 0..4: n;
            constraint count_eq(xs, 2, n);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        assertEquals(2, sample.ints[program.intVarsByName.getValue("n")])
    }

    @Test
    fun `all_different_int`() {
        val src = """
            array [1..3] of var 0..2: xs;
            constraint all_different_int(xs);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        val seen = setOf(sample.ints[0], sample.ints[1], sample.ints[2])
        assertEquals(3, seen.size)
    }

    @Test
    fun `fzn_all_different_int is recognized`() {
        // Gecode flattening emits the fzn_-prefixed builtin; it must map to the same AllDifferent.
        val src = """
            array [1..3] of var 0..2: xs;
            constraint fzn_all_different_int(xs);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        assertEquals(3, setOf(sample.ints[0], sample.ints[1], sample.ints[2]).size)
    }

    @Test
    fun `circuit emits a hamiltonian cycle`() {
        // MiniZinc-style 1-indexed circuit: each succ holds a value in [1, n].
        val src = """
            array [1..4] of var 1..4: succ;
            constraint circuit(succ);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        // Decode the cycle in 1-indexed space.
        val visited = BooleanArray(4)
        var node = 0
        for (step in 0 until 4) {
            assertTrue(!visited[node], "revisit at step $step: ${sample.ints.toList()}")
            visited[node] = true
            node = (sample.ints[node] - 1).toInt()
        }
        assertEquals(0, node, "circuit should close in 4 steps: ${sample.ints.toList()}")
    }

    @Test
    fun `cumulative respects capacity`() {
        // 3 unit-resource tasks of duration 2; capacity 1 forces serialization.
        val src = """
            array [1..3] of var 0..4: s;
            array [1..3] of int: dur = [2, 2, 2];
            array [1..3] of int: res = [1, 1, 1];
            constraint cumulative(s, dur, res, 1);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        val occ = IntArray(8)
        for (i in 0 until 3) {
            val start = sample.ints[i].toInt()
            for (t in start until start + 2) if (t in occ.indices) occ[t]++
        }
        for (t in occ.indices) assertTrue(occ[t] <= 1, "capacity violated at t=$t: ${sample.ints.toList()}")
    }

    @Test
    fun `disjunctive serializes three unit tasks`() {
        val src = """
            array [1..3] of var 0..2: s;
            array [1..3] of int: dur = [1, 1, 1];
            constraint disjunctive(s, dur);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val samples = BacktrackSolver(program.problem).enumerate(BacktrackParams(randomSeed = 0L)).toList()
        assertEquals(6, samples.size, "expected 3! disjunctive schedules; got ${samples.size}")
    }

    @Test
    fun `float vars are bucketed and float_lin_le works`() {
        val src = """
            var 0.0..10.0: x;
            var 0.0..10.0: y;
            constraint float_lin_le([1.0, 1.0], [x, y], 5.0);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src, floatBuckets = 100)
        assertEquals(2, program.floatVarsByName.size)
        val sample = LocalSearchSolver(program.problem)
            .sample(LocalSearchParams(maxFlips = 20_000L, randomSeed = 3L)).assignment
        assertNotNull(sample)
        val xVal = program.floatVarsByName.getValue("x").valueOf(sample.ints[0].toInt())
        val yVal = program.floatVarsByName.getValue("y").valueOf(sample.ints[1].toInt())
        // Allow a small tolerance for rounding through the bucket/scale pipeline.
        assertTrue(xVal + yVal <= 5.0 + 0.5, "x+y = ${xVal + yVal}")
    }

    @Test
    fun `unsupported builtin throws`() {
        // `not_a_real_builtin` is a deliberately fake name that no klause emitter handles.
        val src = """
            var 0..5: x;
            constraint not_a_real_builtin(x);
            solve satisfy;
        """.trimIndent()
        try {
            parseFlatZinc(src)
            error("expected FlatZincParseException")
        } catch (e: FlatZincParseException) {
            assertTrue(e.message!!.contains("not_a_real_builtin"), "got: ${e.message}")
        }
    }

    @Test
    fun `comments are skipped`() {
        val src = """
            % this is a comment
            var bool: x;  % trailing comment
            constraint bool_clause([x], []);  % another
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(1, program.problem.numBoolVars)
    }

    @Test
    fun `symmetry_breaking_constraint enforced in CP mode`() {
        val src = """
            var bool: x;
            constraint symmetry_breaking_constraint(x);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(sat.assignment.bools[0])
    }

    @Test
    fun `klause_enum_labels annotation populates enumLabelsByVar`() {
        val src = """
            var 1..3: color :: klause_enum_labels(["Red","Green","Blue"]);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(listOf("Red", "Green", "Blue"), program.enumLabelsByVar["color"])
    }

    @Test
    fun `set var lowers to indicator bool decomposition`() {
        val src = """
            var set of 1..5: s;
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val layout = program.setVarsByName.getValue("s")
        assertEquals(listOf(1, 2, 3, 4, 5), layout.elements.toList())
        assertEquals(5, layout.indicatorBoolIds.size)
        assertEquals(5, program.problem.numBoolVars)
    }

    @Test
    fun `set var with sparse universe uses sorted element list`() {
        val src = """
            var set of {7, 2, 11}: s;
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val layout = program.setVarsByName.getValue("s")
        assertEquals(listOf(2, 7, 11), layout.elements.toList())
    }

    @Test
    fun `redundant_constraint dropped under forLocalSearch`() {
        // With LS no-op, the constraint disappears entirely: x is free.
        val src = """
            var bool: x;
            constraint redundant_constraint(x);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src, forLocalSearch = true)
        assertEquals(0, program.problem.factors.size)
    }

    @Test
    fun `int_eq with constant`() {
        val src = """
            var 0..5: x;
            constraint int_eq(x, 3);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        assertEquals(3, sample.ints[0])
    }

    @Test
    fun `int_le_imp enforces the relation when the guard holds`() {
        val src = """
            var 0..10: x;
            var 0..10: y;
            var bool: r;
            constraint bool_clause([r], []);
            constraint int_le_imp(x, y, r);
            constraint int_eq(x, 6);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        val x = sample.ints[program.intVarsByName.getValue("x")]
        val y = sample.ints[program.intVarsByName.getValue("y")]
        assertTrue(x <= y, "r true must force x<=y, got x=$x y=$y")
    }

    @Test
    fun `int_le_imp leaves the relation free when the guard is false`() {
        // r false must not enforce x<=y, so x=10>y=0 stays satisfiable.
        val src = """
            var 0..10: x;
            var 0..10: y;
            var bool: r;
            constraint bool_clause([], [r]);
            constraint int_le_imp(x, y, r);
            constraint int_eq(x, 10);
            constraint int_eq(y, 0);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        assertEquals(10, sample.ints[program.intVarsByName.getValue("x")])
        assertEquals(0, sample.ints[program.intVarsByName.getValue("y")])
    }

    @Test
    fun `int_lin_le_imp enforces the linear bound when the guard holds`() {
        val src = """
            var 0..10: a;
            var 0..10: b;
            var bool: r;
            constraint bool_clause([r], []);
            constraint int_lin_le_imp([1, 1], [a, b], 3, r);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val sample = BacktrackSolver(program.problem).sample(BacktrackParams(randomSeed = 0L)).assignment
        assertNotNull(sample)
        val a = sample.ints[program.intVarsByName.getValue("a")]
        val b = sample.ints[program.intVarsByName.getValue("b")]
        assertTrue(a + b <= 3, "r true must force a+b<=3, got a=$a b=$b")
    }
}
