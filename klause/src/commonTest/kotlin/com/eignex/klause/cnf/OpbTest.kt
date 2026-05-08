package com.eignex.klause.cnf

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.PseudoBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpbTest {

    @Test
    fun parsesMixedConstraintsWithoutObjective() {
        val text = """
            * #variable= 5 #constraint= 3
            +1 x1 +4 x2 +2 x3 +5 x4 +2 x5 >= 8 ;
            -1 x1 +2 x2 -3 x3 +4 x4 +5 x5 <= 6 ;
            +1 x1 +1 x3 +2 x5 = 1 ;
        """.trimIndent()
        val out = Opb.parse(text)
        assertNull(out.objective)
        assertEquals(5, out.problem.numBoolVars)
        assertEquals(0, out.problem.numIntVars)
        assertEquals(3, out.problem.factors.size)
        val first = out.problem.factors[0] as PseudoBoolean
        assertEquals(PbOp.GE, first.op)
        assertEquals(8, first.bound)
        assertEquals(listOf(1, 4, 2, 5, 2), first.weights.toList())
        assertEquals(
            listOf(0, 1, 2, 3, 4).map { Lit.make(it, true) },
            first.literals.toList(),
        )
    }

    @Test
    fun parsesNegatedLiterals() {
        val text = """
            +1 ~x1 +2 x2 <= 1 ;
        """.trimIndent()
        val out = Opb.parse(text)
        val pb = out.problem.factors[0] as PseudoBoolean
        assertEquals(Lit.make(0, false), pb.literals[0])
        assertEquals(Lit.make(1, true), pb.literals[1])
    }

    @Test
    fun parsesMinObjectiveAndFoldsNegationConstant() {
        // `min: 2 x1 +3 ~x2 ;` → minimize 2·x1 + 3·(1-x2) = 2·x1 - 3·x2 + 3.
        val text = """
            min: 2 x1 +3 ~x2 ;
            +1 x1 +1 x2 >= 1 ;
        """.trimIndent()
        val out = Opb.parse(text)
        val obj = assertNotNull(out.objective)
        assertEquals(2.0, obj.boolWeights[0])
        assertEquals(-3.0, obj.boolWeights[1])
        assertEquals(3.0, obj.constant)
        assertEquals(0, obj.intCoefficients.size)
    }

    @Test
    fun rejectsMissingTerminator() {
        val text = "+1 x1 >= 1"
        assertTrue(runCatching { Opb.parse(text) }.isFailure)
    }

    @Test
    fun rejectsMissingOperator() {
        val text = "+1 x1 1 ;"
        assertTrue(runCatching { Opb.parse(text) }.isFailure)
    }
}
