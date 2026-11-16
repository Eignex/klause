package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtBitVectorReductionTest {
    @Test
    fun `all bitvector operators should lower through existing scalar services`() {
        val examples = listOf(
            "(bvnot #b1010)" to "#b0101",
            "(bvneg #b1010)" to "#b0110",
            "(bvand #b1010 #b1100 #b1111)" to "#b1000",
            "(bvor #b1010 #b1100 #b0001)" to "#b1111",
            "(bvxor #b1010 #b1100)" to "#b0110",
            "(bvxor #b1010 #b1100 #b0011)" to "#b0101",
            "(bvnand #b1010 #b1100)" to "#b0111",
            "(bvnor #b1010 #b1100)" to "#b0001",
            "(bvxnor #b1010 #b1100)" to "#b1001",
            "(bvadd #b1010 #b1100 #b0001)" to "#b0111",
            "(bvsub #b1010 #b1100)" to "#b1110",
            "(bvmul #b1010 #b1100 #b0011)" to "#b1000",
            "(bvudiv #b1010 #b0011)" to "#b0011",
            "(bvurem #b1010 #b0011)" to "#b0001",
            "(bvsdiv #b1010 #b0011)" to "#b1110",
            "(bvsrem #b1010 #b0100)" to "#b1110",
            "(bvsmod #b1010 #b0100)" to "#b0010",
            "(bvshl #b1010 #b0001)" to "#b0100",
            "(bvlshr #b1010 #b0001)" to "#b0101",
            "(bvashr #b1010 #b0001)" to "#b1101",
            "(bvcomp #b1010 #b1010)" to "#b1",
            "(concat #b1010 #b1100)" to "#xac",
            "((_ extract 2 1) #b1010)" to "#b01",
            "((_ zero_extend 2) #b1010)" to "#b001010",
            "((_ sign_extend 2) #b1010)" to "#b111010",
            "((_ repeat 2) #b10)" to "#b1010",
            "((_ rotate_left 1) #b1010)" to "#b0101",
            "((_ rotate_right 100000000000000000001) #b1010)" to "#b0101",
            "(ite true #b1010 #b1100)" to "#b1010",
        )
        val source = examples.joinToString("\n") { (expression, value) -> "(assert (= $expression $value))" }
        val (reduced, parsed) = lower(source)

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.model).solve()).assignment

        assertTrue(reduced.checks.all { check -> check.check(
            { assignment.boolValue(parsed.encodingBoolVarNames.getValue(it)) },
            { assignment.intValue(parsed.encodingIntVarNames.getValue(it)) },
        ) })
    }

    @Test
    fun `bitvector predicates should retain their Boolean sort`() {
        val predicates = listOf(
            "(bvult #b0011 #b1010)", "(bvule #b0011 #b0011)", "(bvugt #b1010 #b0011)", "(bvuge #b0011 #b0011)",
            "(bvslt #b1010 #b0011)", "(bvsle #b1010 #b1010)", "(bvsgt #b0011 #b1010)", "(bvsge #b1010 #b1010)",
            "(bvnego #b1000)", "(bvuaddo #b1111 #b0001)", "(bvsaddo #b0111 #b0001)",
            "(bvumulo #b1000 #b0010)", "(bvsmulo #b0100 #b0010)", "(distinct #b0000 #b0001 #b0010)",
            "(bvusubo #b0000 #b0001)", "(bvssubo #b1000 #b0001)", "(bvsdivo #b1000 #b1111)",
        )
        val (reduced, parsed) = lower(predicates.joinToString("\n") { "(assert $it)" })

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.model).solve()).assignment

        assertTrue(reduced.checks.all { check -> check.check(
            { assignment.boolValue(parsed.encodingBoolVarNames.getValue(it)) },
            { assignment.intValue(parsed.encodingIntVarNames.getValue(it)) },
        ) })
    }

    @Test
    fun `wide declared values should wrap without machine truncation`() {
        val ones = "1".repeat(129)
        val zeros = "0".repeat(129)
        val (reduced, parsed) = lower(
            "(declare-const x (_ BitVec 129)) (assert (= x #b$ones)) " +
                "(assert (= (bvadd x (_ bv1 129)) #b$zeros))",
        )

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.model).solve()).assignment

        assertEquals(129, reduced.bitNames.getValue("x").size)
        assertTrue(reduced.bitNames.getValue("x").all {
            assignment.boolValue(parsed.encodingBoolVarNames.getValue(it))
        })
        assertTrue(reduced.checks.all { check -> check.check(
            { assignment.boolValue(parsed.encodingBoolVarNames.getValue(it)) },
            { assignment.intValue(parsed.encodingIntVarNames.getValue(it)) },
        ) })
    }

    @Test
    fun `integer conversions should preserve negative modular input and signed output`() {
        val (reduced, parsed) = lower(
            "(declare-const x Int) (assert (= x (- 7))) " +
                "(assert (= ((_ int_to_bv 4) x) #b1001)) " +
                "(assert (= (ubv_to_int #b1001) 9)) (assert (= (sbv_to_int #b1001) (- 7)))",
        )

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.model).solve()).assignment

        assertTrue(reduced.checks.all { check -> check.check(
            { assignment.boolValue(parsed.encodingBoolVarNames.getValue(it)) },
            { assignment.intValue(parsed.encodingIntVarNames.getValue(it)) },
        ) })
    }

    @Test
    fun `source checks should reject a bit assignment that disagrees with an original operation`() {
        val (reduced, parsed) = lower(
            "(declare-const x (_ BitVec 2)) (assert (= x #b01)) (assert (= (bvadd x #b01) #b10))",
        )
        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.model).solve()).assignment
        val flipped = reduced.bitNames.getValue("x")[0]

        val valid = reduced.checks.all { check -> check.check(
            { name -> assignment.boolValue(parsed.encodingBoolVarNames.getValue(name)) != (name == flipped) },
            { assignment.intValue(parsed.encodingIntVarNames.getValue(it)) },
        ) }

        assertFalse(valid)
    }

    @Test
    fun `helpers and parallel lets should retain lexical bitvector bindings`() {
        val (reduced, parsed) = lower(
            "(declare-const x (_ BitVec 4)) (assert (= x #b0001)) " +
                "(define-fun f ((a (_ BitVec 4))) (_ BitVec 4) (bvadd x a)) " +
                "(assert (let ((x #b1000) (a x)) (= (f a) #b0010)))",
        )

        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.model).solve()).assignment

        assertTrue(reduced.checks.all { check -> check.check(
            { assignment.boolValue(parsed.encodingBoolVarNames.getValue(it)) },
            { assignment.intValue(parsed.encodingIntVarNames.getValue(it)) },
        ) })
    }

    private fun lower(source: String): Pair<SmtBitVectorReduction, SmtLibProblem> {
        val reduced = reduceSmtBitVectors(SExprReader(source).readAll(), ExecutionControl.Never)
        return reduced to SmtLib.parse(source)
    }
}
