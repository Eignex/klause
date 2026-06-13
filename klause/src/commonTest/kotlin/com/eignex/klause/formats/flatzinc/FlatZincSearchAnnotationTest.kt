package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.backtrack.TierVarSelect
import com.eignex.klause.solver.backtrack.TieredValueSelector
import com.eignex.klause.solver.backtrack.TieredVariableSelector
import com.eignex.klause.solver.backtrack.selector.IndomainMax
import com.eignex.klause.solver.backtrack.selector.IndomainMedian
import com.eignex.klause.solver.backtrack.selector.IndomainMin
import com.eignex.klause.solver.backtrack.selector.IndomainSplit
import com.eignex.klause.solver.backtrack.selector.InputOrder
import com.eignex.klause.solver.backtrack.selector.MaxRegret
import com.eignex.klause.solver.backtrack.selector.SmallestDomain
import com.eignex.klause.solver.backtrack.selector.SmallestLowerBound
import com.eignex.klause.solver.backtrack.selector.SolutionGuided
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlatZincSearchAnnotationTest {

    private fun tieredVar(program: FlatZincProgram): TieredVariableSelector =
        assertNotNull(program.defaultBacktrackParams).variableSelector as TieredVariableSelector

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
        assertEquals(IndomainMin, tier.valueSelector)
        assertEquals(SmallestDomain, varH.fallback)
    }

    @Test
    fun `max_regret and indomain_median map to their own selectors not approximations`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], max_regret, indomain_median, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val varH = tieredVar(program)
        assertEquals(TierVarSelect.MaxRegret, varH.tiers[0].varSelect)
        // indomain_median is its own heuristic now, no longer conflated with indomain_middle.
        assertEquals(IndomainMedian, varH.tiers[0].valueSelector)
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
        assertEquals(IndomainMax, varH.tiers[0].valueSelector)
        assertContentEquals(intArrayOf(assertNotNull(program.intVarsByName["x"])), varH.tiers[0].intVars)
        assertEquals(TierVarSelect.SmallestDomain, varH.tiers[1].varSelect)
        assertEquals(IndomainMin, varH.tiers[1].valueSelector)
        assertContentEquals(intArrayOf(assertNotNull(program.boolVarsByName["y"])), varH.tiers[1].boolVars)
        val valH = assertNotNull(program.defaultBacktrackParams).valueSelector
        assertTrue(valH is TieredValueSelector)
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
        assertEquals(IndomainSplit, varH.tiers[0].valueSelector)
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
        assertTrue(params.valueSelector is SolutionGuided)
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
        assertTrue(params.valueSelector is TieredValueSelector)
    }
}
