package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.backtrack.IndomainMax
import com.eignex.klause.solver.backtrack.IndomainMin
import com.eignex.klause.solver.backtrack.IndomainSplit
import com.eignex.klause.solver.backtrack.SmallestDomain
import com.eignex.klause.solver.backtrack.SmallestLowerBound
import com.eignex.klause.solver.backtrack.SolutionGuided
import com.eignex.klause.solver.backtrack.TierVarSelect
import com.eignex.klause.solver.backtrack.TieredValueHeuristic
import com.eignex.klause.solver.backtrack.TieredVariableHeuristic
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlatZincSearchAnnotationTest {

    private fun tieredVar(program: FlatZincProgram): TieredVariableHeuristic =
        assertNotNull(program.defaultBacktrackParams).variableHeuristic as TieredVariableHeuristic

    @Test
    fun `int_search becomes one tier over the annotated array`() {
        val src = """
            var 0..5: x;
            var 0..5: y;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], first_fail, indomain_min, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val varH = tieredVar(program)
        assertEquals(1, varH.tiers.size)
        val tier = varH.tiers[0]
        assertContentEquals(intArrayOf(assertNotNull(program.intVarsByName["x"])), tier.intVars)
        assertEquals(TierVarSelect.SmallestDomain, tier.varSelect)
        assertEquals(IndomainMin, tier.valueHeuristic)
        assertEquals(SmallestDomain, varH.fallback)
    }

    @Test
    fun `seq_search keeps every block as its own tier in order`() {
        val src = """
            var 0..5: x;
            var bool: y;
            constraint int_lin_le([1], [x], 3);
            solve :: seq_search([
                int_search([x], input_order, indomain_max, complete),
                bool_search([y], first_fail, indomain_min, complete)
            ]) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val varH = tieredVar(program)
        assertEquals(2, varH.tiers.size)
        assertEquals(TierVarSelect.InputOrder, varH.tiers[0].varSelect)
        assertEquals(IndomainMax, varH.tiers[0].valueHeuristic)
        assertContentEquals(intArrayOf(assertNotNull(program.intVarsByName["x"])), varH.tiers[0].intVars)
        assertEquals(TierVarSelect.SmallestDomain, varH.tiers[1].varSelect)
        assertEquals(IndomainMin, varH.tiers[1].valueHeuristic)
        assertContentEquals(intArrayOf(assertNotNull(program.boolVarsByName["y"])), varH.tiers[1].boolVars)
        val valH = assertNotNull(program.defaultBacktrackParams).valueHeuristic
        assertTrue(valH is TieredValueHeuristic)
    }

    @Test
    fun `smallest and largest map to the bound heuristics`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], smallest, indomain_split, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val varH = tieredVar(program)
        assertEquals(TierVarSelect.SmallestLowerBound, varH.tiers[0].varSelect)
        assertEquals(IndomainSplit, varH.tiers[0].valueHeuristic)
        assertEquals(SmallestLowerBound, varH.fallback)
    }

    @Test
    fun `set_search tiers over the set var's indicator bools`() {
        val src = """
            var set of 1..3: s;
            solve :: set_search([s], input_order, indomain_min, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val varH = tieredVar(program)
        val layout = assertNotNull(program.setVarsByName["s"])
        assertContentEquals(layout.indicatorBoolIds, varH.tiers[0].boolVars)
    }

    @Test
    fun `constants in a search array are skipped`() {
        val src = """
            var bool: a;
            var bool: b;
            constraint bool_clause([a, b], []);
            solve :: bool_search([a, true, b], input_order, indomain_max, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val varH = tieredVar(program)
        assertContentEquals(
            intArrayOf(
                assertNotNull(program.boolVarsByName["a"]),
                assertNotNull(program.boolVarsByName["b"]),
            ),
            varH.tiers[0].boolVars,
        )
    }

    @Test
    fun `unrecognised variable strategy keeps the tier with input order`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], domwdeg_xyz, indomain_min, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val varH = tieredVar(program)
        assertEquals(TierVarSelect.InputOrder, varH.tiers[0].varSelect)
    }

    @Test
    fun `no search annotation leaves defaultBacktrackParams null`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertNull(program.defaultBacktrackParams)
    }

    @Test
    fun `minimize wraps the value side in SolutionGuided`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], input_order, indomain_min, complete) minimize x;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val params = assertNotNull(program.defaultBacktrackParams)
        assertTrue(params.valueHeuristic is SolutionGuided)
    }

    @Test
    fun `satisfy does not wrap the value side`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], input_order, indomain_min, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val params = assertNotNull(program.defaultBacktrackParams)
        assertTrue(params.valueHeuristic is TieredValueHeuristic)
    }
}
