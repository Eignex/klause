package com.eignex.klause.formats.flatzinc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The klause MZN library tags `redundant_constraint` / `symmetry_breaking_constraint` with the
 * `klause_redundant` / `klause_symmetry` annotations so the marker survives flatten. These tests
 * pin the parser→compiler path that lifts those tags onto [com.eignex.klause.solver.Problem]:
 * `impliedFactorMask` for the local-search weight seed, `hasSymmetryBreaking` for the presolve gate.
 */
class FlatZincCompilerImpliedConstraintTest {

    @Test
    fun `unannotated model has no implied mask and no symmetry flag`() {
        val src = """
            var 0..10: x;
            var 0..10: y;
            constraint int_lin_le([1, 1], [x, y], 5);
            solve satisfy;
        """.trimIndent()
        val problem = parseFlatZinc(src).problem
        assertNull(problem.impliedFactorMask)
        assertFalse(problem.hasSymmetryBreaking)
    }

    @Test
    fun `redundant annotation marks the factor implied without setting the symmetry flag`() {
        val src = """
            var 0..10: x;
            var 0..10: y;
            constraint int_lin_le([1, 1], [x, y], 5);
            constraint int_lin_eq([1, -1], [x, y], 0) :: klause_redundant;
            solve satisfy;
        """.trimIndent()
        val problem = parseFlatZinc(src).problem
        val mask = problem.impliedFactorMask
        assertTrue(mask != null && mask.size == problem.numFactors)
        // The structural inequality stays at full weight; only the redundant equality is implied.
        assertFalse(mask[0])
        assertTrue(mask[1])
        assertFalse(problem.hasSymmetryBreaking)
    }

    @Test
    fun `symmetry annotation marks the factor implied and sets the symmetry flag`() {
        val src = """
            var 0..10: x;
            var 0..10: y;
            constraint int_lin_le([1, 1], [x, y], 5);
            constraint int_lin_le([1, -1], [x, y], 0) :: klause_symmetry;
            solve satisfy;
        """.trimIndent()
        val problem = parseFlatZinc(src).problem
        val mask = problem.impliedFactorMask
        assertTrue(mask != null && mask.size == problem.numFactors)
        assertFalse(mask[0])
        assertTrue(mask[1])
        assertTrue(problem.hasSymmetryBreaking)
    }

    @Test
    fun `mask size tracks every factor`() {
        val src = """
            var 0..10: x;
            var 0..10: y;
            var 0..10: z;
            constraint int_lin_le([1, 1], [x, y], 5);
            constraint int_lin_eq([1, 1, -1], [x, y, z], 0) :: klause_redundant;
            constraint int_lin_le([1, 1], [y, z], 9);
            solve satisfy;
        """.trimIndent()
        val problem = parseFlatZinc(src).problem
        val mask = assertNotNull(problem.impliedFactorMask)
        assertEquals(problem.numFactors, mask.size)
        assertEquals(1, mask.count { it })
    }
}
