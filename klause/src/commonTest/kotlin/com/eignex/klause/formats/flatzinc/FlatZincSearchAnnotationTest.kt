package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.backtrack.IndomainMax
import com.eignex.klause.solver.backtrack.IndomainMin
import com.eignex.klause.solver.backtrack.IndomainRandom
import com.eignex.klause.solver.backtrack.InputOrder
import com.eignex.klause.solver.backtrack.SmallestDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class FlatZincSearchAnnotationTest {

    @Test
    fun `int_search annotation maps to BacktrackParams heuristics`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], first_fail, indomain_min, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val params = assertNotNull(program.defaultBacktrackParams)
        assertEquals(SmallestDomain, params.variableHeuristic)
        assertEquals(IndomainMin, params.valueHeuristic)
    }

    @Test
    fun `bool_search with random_order indomain_random`() {
        val src = """
            var bool: a;
            var bool: b;
            constraint bool_clause([a, b], []);
            solve :: bool_search([a, b], random_order, indomain_random, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val params = assertNotNull(program.defaultBacktrackParams)
        assertEquals(com.eignex.klause.solver.backtrack.RandomVariable, params.variableHeuristic)
        assertEquals(IndomainRandom, params.valueHeuristic)
    }

    @Test
    fun `seq_search picks the first block's strategies`() {
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
        val params = assertNotNull(program.defaultBacktrackParams)
        assertEquals(InputOrder, params.variableHeuristic)
        assertEquals(IndomainMax, params.valueHeuristic)
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
    fun `minimize wraps value heuristic in SolutionGuided`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], input_order, indomain_min, complete) minimize x;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val params = assertNotNull(program.defaultBacktrackParams)
        val sg = params.valueHeuristic as? com.eignex.klause.solver.backtrack.SolutionGuided
        assertNotNull(sg, "minimize should wrap valueHeuristic in SolutionGuided")
    }

    @Test
    fun `maximize wraps value heuristic in SolutionGuided`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], input_order, indomain_max, complete) maximize x;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val params = assertNotNull(program.defaultBacktrackParams)
        val sg = params.valueHeuristic as? com.eignex.klause.solver.backtrack.SolutionGuided
        assertNotNull(sg, "maximize should wrap valueHeuristic in SolutionGuided")
    }

    @Test
    fun `satisfy does not wrap value heuristic`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], input_order, indomain_min, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val params = assertNotNull(program.defaultBacktrackParams)
        assertEquals(IndomainMin, params.valueHeuristic)
    }

    @Test
    fun `unrecognised strategy names yield null`() {
        val src = """
            var 0..5: x;
            constraint int_lin_le([1], [x], 3);
            solve :: int_search([x], domwdeg_xyz, indomain_min, complete) satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertNull(program.defaultBacktrackParams)
    }
}
