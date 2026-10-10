package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SolveEvidenceTest {
    @Test
    fun `only the last complete minizinc candidate is retained`() {
        val raw = "x = 1;\n----------\nx = 2;\n----------\nx = 3;\n"

        val witness = SolveEvidence.finalWitness(Format.MINIZINC, raw)

        assertEquals("x = 2;", witness)
    }

    @Test
    fun `competition records retain the final rendered witness`() {
        for (format in listOf(Format.OPB, Format.XCSP3, Format.DIMACS, Format.WCNF, Format.MPS)) {
            val witness = SolveEvidence.finalWitness(format, "v x1\no 2\nv -x1 x2\nc solveTime=1\n")

            assertEquals("v -x1 x2", witness)
        }
    }

    @Test
    fun `a stream without a complete candidate carries no witness`() {
        for (format in listOf(Format.MINIZINC, Format.OPB, Format.XCSP3)) {
            assertNull(SolveEvidence.finalWitness(format, "=====UNKNOWN=====\n"))
        }
    }

    @Test
    fun `SMT feasible verdicts retain only the exact rendered model`() {
        val model = """
            (
              (define-fun wide () Int 18446744073709551616)
              (define-fun negative () Int (- 18446744073709551616))
              (define-fun fraction () Real (- (/ 2.0 3.0)))
              (define-fun whole () Real 5.0)
              (define-fun |flag ()| () Bool true)
            )
        """.trimIndent()
        for (status in listOf(null, "optimal", "best-found", "unbounded")) {
            val metadata = status?.let { "; objective=-2/3\nsat\n; optimizationStatus=$it\n" } ?: "sat\n"
            val raw = "$metadata$model\n; solveTime=0.01\n; solutions=1\n"

            val witness = SolveEvidence.finalWitness(Format.SMTLIB, raw)

            assertEquals(model, witness, status)
        }
    }

    @Test
    fun `only the last complete SMT model is retained`() {
        val model = "(\n  (define-fun x () Int 2)\n)"
        val raw = "sat\n(\n  (define-fun x () Int 1)\n)\nsat\n$model\n(\n  (define-fun x () Int 3)\n"

        val witness = SolveEvidence.finalWitness(Format.SMTLIB, raw)

        assertEquals(model, witness)
    }

    @Test
    fun `model free SMT results carry no witness`() {
        for (raw in listOf("", "unsat\n", "unknown\n", "sat\n", "; objective=7\n; optimizationStatus=optimal\n")) {
            assertNull(SolveEvidence.finalWitness(Format.SMTLIB, raw), raw)
        }
    }

    @Test
    fun `an incomplete SMT model carries no witness`() {
        val raw = "sat\n(\n  (define-fun x () Real (/ 1.0 3.0))\n"

        val witness = SolveEvidence.finalWitness(Format.SMTLIB, raw)

        assertNull(witness)
    }

    @Test
    fun `an empty SMT model is retained`() {
        val witness = SolveEvidence.finalWitness(Format.SMTLIB, "sat\n(\n)\n; solveTime=0.01\n")

        assertEquals("(\n)", witness)
    }

    @Test
    fun `SMT witness size is limited by the complete model length`() {
        val prefix = "(\n  (define-fun x () Int "
        val suffix = ")\n)"
        val digits = "1".repeat(8 * 1024 * 1024 - prefix.length - suffix.length)
        for (extra in listOf("", "1")) {
            val model = "$prefix$digits$extra$suffix"
            val raw = "sat\n(\n)\n$model\n"

            val witness = SolveEvidence.finalWitness(Format.SMTLIB, raw)

            assertEquals(if (extra.isEmpty()) model else null, witness)
        }
    }
}
