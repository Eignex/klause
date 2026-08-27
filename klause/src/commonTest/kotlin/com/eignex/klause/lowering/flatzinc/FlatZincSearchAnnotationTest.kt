package com.eignex.klause.lowering.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.TierVarSelect
import com.eignex.klause.backtrack.TieredValueSelector
import com.eignex.klause.backtrack.TieredVariableSelector
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMedian
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.IndomainSplit
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.toBacktrackParams
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlatZincSearchAnnotationTest {

    private fun searchHints(program: FlatZincProgram): FlatZincSearchHints = assertNotNull(program.searchHints)

    private fun tieredVar(program: FlatZincProgram): TieredVariableSelector =
        toParams(program).variableSelector as TieredVariableSelector

    private fun toParams(program: FlatZincProgram): BacktrackParams {
        val hints = searchHints(program)
        return hints.toBacktrackParams(program.problem.numBoolVars, program.problem.numIntVars)
    }

    @Test
    fun `int_search becomes one tier over the annotated array`() {
        val src = """
            var 0..5: x;
            var 0..5: y;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], first_fail, indomain_min, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val hints = searchHints(program)
        assertEquals(1, hints.tiers.size)
        val tier = hints.tiers[0]
        assertEquals(FlatZincSearchVarSelector.SmallestDomain, tier.varSelector)
        assertEquals(FlatZincSearchValueSelector.IndomainMin, tier.valueSelector)
        val varH = tieredVar(program)
        assertEquals(1, varH.tiers.size)
        assertContentEquals(intArrayOf(assertNotNull(program.intVarsByName["x"])), varH.tiers[0].intVars)
        assertEquals(TierVarSelect.SmallestDomain, varH.tiers[0].varSelect)
        assertEquals(IndomainMin, varH.tiers[0].valueSelector)
    }

    @Test
    fun `annotated search enables phase saving so re-descents reuse the last working values`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], first_fail, indomain_min, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertTrue(
            toParams(program).phaseSaving,
            "annotated track should phase-save (#543)",
        )
    }

    @Test
    fun `max_regret and indomain_median map to their own selectors not approximations`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], max_regret, indomain_median, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val hints = searchHints(program)
        assertEquals(FlatZincSearchVarSelector.MaxRegret, hints.tiers[0].varSelector)
        assertEquals(FlatZincSearchValueSelector.IndomainMedian, hints.tiers[0].valueSelector)
        val varH = tieredVar(program)
        assertEquals(TierVarSelect.MaxRegret, varH.tiers[0].varSelect)
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
        val hints = searchHints(program)
        assertEquals(FlatZincSearchVarSelector.InputOrder, hints.tiers[0].varSelector)
        assertEquals(FlatZincSearchValueSelector.IndomainMax, hints.tiers[0].valueSelector)
        assertEquals(FlatZincSearchVarSelector.SmallestDomain, hints.tiers[1].varSelector)
        assertEquals(FlatZincSearchValueSelector.IndomainMin, hints.tiers[1].valueSelector)
        val varH = tieredVar(program)
        assertEquals(2, varH.tiers.size)
        assertEquals(TierVarSelect.InputOrder, varH.tiers[0].varSelect)
        assertEquals(IndomainMax, varH.tiers[0].valueSelector)
        assertContentEquals(intArrayOf(assertNotNull(program.intVarsByName["x"])), varH.tiers[0].intVars)
        assertEquals(TierVarSelect.SmallestDomain, varH.tiers[1].varSelect)
        assertEquals(IndomainMin, varH.tiers[1].valueSelector)
        assertContentEquals(intArrayOf(assertNotNull(program.boolVarsByName["y"])), varH.tiers[1].boolVars)
        val valH = toParams(program).valueSelector
        assertTrue(valH is TieredValueSelector)
    }

    @Test
    fun `smallest maps to the lower-bound heuristic`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], smallest, indomain_split, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val hints = searchHints(program)
        assertEquals(FlatZincSearchVarSelector.SmallestLowerBound, hints.tiers[0].varSelector)
        assertEquals(FlatZincSearchValueSelector.IndomainSplit, hints.tiers[0].valueSelector)
        val varH = tieredVar(program)
        assertEquals(TierVarSelect.SmallestLowerBound, varH.tiers[0].varSelect)
        assertEquals(IndomainSplit, varH.tiers[0].valueSelector)
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
        val hints = searchHints(program)
        assertEquals(FlatZincSearchVarSelector.InputOrder, hints.tiers[0].varSelector)
    }

    @Test
    fun `occurrence strategy keeps fallback and tier differ as legacy mapping`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], occurrence, indomain_min, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val hints = searchHints(program)
        assertEquals(FlatZincSearchVarSelector.SmallestDomain, hints.tiers[0].varSelector)
        assertEquals(FlatZincSearchVarSelector.LargestDomain, hints.fallbackVarSelector)
    }

    @Test
    fun `no search annotation leaves searchHints null`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertNull(program.searchHints)
    }

    @Test
    fun `minimize wraps value search in SolutionGuided`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], input_order, indomain_min, complete) minimize x;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val params = toParams(program)
        assertTrue(params.valueSelector is SolutionGuided)
    }

    @Test
    fun `satisfy does not wrap the value search`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], input_order, indomain_min, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val params = toParams(program)
        assertTrue(params.valueSelector is TieredValueSelector)
    }
}
