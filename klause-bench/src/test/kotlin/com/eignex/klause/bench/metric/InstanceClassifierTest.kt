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

    @Test
    fun `an smtlib instance reports its declared logic`() {
        val f = InstanceClassifier.fromSource(
            Format.SMTLIB,
            "(set-logic QF_LIA)\n(declare-fun x () Int)\n(assert (>= x 0))\n(check-sat)",
        )
        assertEquals("QF_LIA", f.logic)
    }

    @Test
    fun `an smtlib instance with no set-logic reports a blank logic`() {
        val f = InstanceClassifier.fromSource(Format.SMTLIB, "(declare-fun x () Int)\n(check-sat)")
        assertEquals("", f.logic)
    }

    @Test
    fun `an mps instance with an INTORG marker is a MIP`() {
        val f = InstanceClassifier.fromSource(Format.MPS, "MARKER\n    M1 'MARKER' 'INTORG'\n")
        assertEquals("MIP", f.logic)
    }

    @Test
    fun `an mps instance with no marker is an LP`() {
        val f = InstanceClassifier.fromSource(Format.MPS, "ROWS\n N obj\nCOLUMNS\n")
        assertEquals("LP", f.logic)
    }

    @Test
    fun `themes name what an instance asks of the solver`() {
        fun themes(format: Format, text: String) = InstanceClassifier.fromSource(format, text).themes

        assertEquals(
            setOf("open-int", "scheduling"),
            themes(Format.MINIZINC, "var int: s;\nconstraint cumulative([s], [1], [1], 1);"),
        )
        assertEquals(setOf("globals"), themes(Format.MINIZINC, "var 1..9: x;\nconstraint all_different([x]);"))
        assertEquals(setOf("linear-real"), themes(Format.MINIZINC, "var 0.0..1.0: x;\nconstraint x >= 0.5;"))
        assertEquals(
            setOf("routing"),
            themes(Format.XCSP3, "<instance><constraints><circuit>x</circuit></constraints></instance>"),
        )
        assertEquals(setOf("open-int", "linear-real"), themes(Format.SMTLIB, "(set-logic QF_LIRA)\n(assert true)"))
        assertEquals(setOf("maxsat"), themes(Format.WCNF, "p wcnf 1 1 2\n1 1 0\n"))
    }

    @Test
    fun `an mps model with columns outside its integer markers has linear reals`() {
        val mixed = """
            NAME m
            ROWS
             N obj
            COLUMNS
                MARKER 'MARKER' 'INTORG'
                x obj 1
                MARKER 'MARKER' 'INTEND'
                y obj 1
            ENDATA
        """.trimIndent()
        val integer = mixed.lines().filterNot { it.trim().startsWith("y ") }.joinToString("\n")

        assertEquals(setOf("mip", "linear-real"), InstanceClassifier.fromSource(Format.MPS, mixed).themes)
        assertEquals(setOf("mip"), InstanceClassifier.fromSource(Format.MPS, integer).themes)
    }
}
