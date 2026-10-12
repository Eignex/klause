package com.eignex.klause.formats.flatzinc

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
}
