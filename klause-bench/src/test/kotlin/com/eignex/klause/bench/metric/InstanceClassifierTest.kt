package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstanceClassifierTest {
    @ParameterizedTest
    @CsvSource(
        "QF_ABV, arrays bitvectors",
        "QF_ALIA, arrays open-int",
        "QF_ANIA, arrays nonlinear-int open-int",
        "QF_AUFBV, arrays bitvectors free-functions",
        "QF_AUFLIA, arrays free-functions open-int",
        "QF_AUFNIA, arrays free-functions nonlinear-int open-int",
        "QF_AX, arrays",
        "QF_BV, bitvectors",
        "QF_DT, datatypes",
        "QF_NIA, nonlinear-int open-int",
        "QF_NIRA, nonlinear-int nonlinear-real open-int",
        "QF_UF, free-functions",
        "QF_UFBV, bitvectors free-functions",
        "QF_UFBVDT, bitvectors datatypes free-functions",
        "QF_UFDT, datatypes free-functions",
        "QF_UFDTLIA, datatypes free-functions open-int",
        "QF_UFDTLIRA, datatypes free-functions linear-real open-int",
        "QF_UFDTNIA, datatypes free-functions nonlinear-int open-int",
        "QF_UFIDL, free-functions open-int",
        "QF_UFLIA, free-functions open-int",
        "QF_UFLRA, free-functions linear-real",
        "QF_UFNIA, free-functions nonlinear-int open-int",
        "QF_UFNRA, free-functions nonlinear-real",
    )
    fun `ground SMT logics expose each declared theory theme`(logic: String, expected: String) {
        val features = InstanceClassifier.fromSource(Format.SMTLIB, "(set-logic $logic)\n(assert true)")

        assertEquals(expected.split(' ').toSet(), features.themes)
    }

    @Test
    fun `a minizinc model with a global constraint should classify as global`() {
        val f = InstanceClassifier.fromSource(Format.MINIZINC, "var 1..9: x;\nconstraint all_different([x]);")
        assertEquals("global", f.structure)
        assertTrue(f.numGlobal >= 1)
    }

    @Test
    fun `a bool-dominated minizinc model should classify as pseudo-boolean`() {
        val f = InstanceClassifier.fromSource(Format.MINIZINC, "var bool: a;\nvar bool: b;\nconstraint a \\/ b;")
        assertTrue(f.boolHeavy)
        assertEquals("pseudo-boolean", f.structure)
    }

    @Test
    fun `an xcsp3 instance with allDifferent should classify as global`() {
        val f = InstanceClassifier.fromSource(
            Format.XCSP3,
            "<instance><constraints><allDifferent>x1 x2 x3</allDifferent></constraints></instance>",
        )
        assertEquals("global", f.structure)
        assertTrue(f.numGlobal >= 1)
    }

    @Test
    fun `an smtlib instance should report its declared logic`() {
        val f = InstanceClassifier.fromSource(
            Format.SMTLIB,
            "(set-logic QF_LIA)\n(declare-fun x () Int)\n(assert (>= x 0))\n(check-sat)",
        )
        assertEquals("QF_LIA", f.logic)
    }

    @Test
    fun `an mps instance with an INTORG marker should classify as MIP`() {
        val f = InstanceClassifier.fromSource(Format.MPS, "MARKER\n    M1 'MARKER' 'INTORG'\n")
        assertEquals("MIP", f.logic)
    }

    @Test
    fun `themes should name what an instance asks of the solver`() {
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
        assertEquals(setOf("nonlinear-real"), themes(Format.SMTLIB, "(set-logic QF_NRA)\n(assert true)"))
        assertEquals(setOf("maxsat"), themes(Format.WCNF, "p wcnf 1 1 2\n1 1 0\n"))
    }

    @Test
    fun `an mps model with columns outside its integer markers should have linear reals`() {
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
