package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstanceClassifierTest {
    @Test
    fun `a minizinc model with a global constraint is global`() {
        val f = InstanceClassifier.fromSource(Format.MINIZINC, "var 1..9: x;\nconstraint all_different([x]);")
        assertEquals("global", f.structure)
        assertTrue(f.numGlobal >= 1, "counts the global use")
    }

    @Test
    fun `a bool-dominated minizinc model is pseudo-boolean`() {
        val f = InstanceClassifier.fromSource(Format.MINIZINC, "var bool: a;\nvar bool: b;\nconstraint a \\/ b;")
        assertTrue(f.boolHeavy, "more bool than int decls")
        assertEquals("pseudo-boolean", f.structure)
    }

    @Test
    fun `an xcsp3 instance with allDifferent is global`() {
        val f = InstanceClassifier.fromSource(
            Format.XCSP3,
            "<instance><constraints><allDifferent>x1 x2 x3</allDifferent></constraints></instance>",
        )
        assertEquals("global", f.structure)
        assertTrue(f.numGlobal >= 1)
    }

    @Test
    fun `a dimacs cnf is sat`() {
        val f = InstanceClassifier.fromSource(Format.DIMACS, "p cnf 2 1\n1 -2 0\n")
        assertEquals("sat", f.structure)
        assertTrue(f.boolHeavy)
    }
}
