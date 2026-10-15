package com.eignex.klause.formats.opb

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.perCharSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpbTest {

    private object Opb {
        fun parse(text: String): OpbProblem = com.eignex.klause.formats.opb.Opb.parse(text).toProblem()
    }

    @Test
    fun `a malformed opb instance should raise a catchable format exception`() {
        assertFailsWith<OpbFormatException> { Opb.parse("+1 x1 +2 x2 8 ;\n") }
    }

    @Test
    fun `mixed constraints without objective should parse`() {
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
        assertEquals(8L, first.bound)
        assertEquals(listOf(1L, 4L, 2L, 5L, 2L), first.weights.toList())
        assertEquals(
            listOf(0, 1, 2, 3, 4).map { Lit.make(it, true) },
            first.literals.toList(),
        )
    }

    @Test
    fun `a terminator glued to the preceding token should be accepted`() {
        val out = Opb.parse("+1 x1 +1 x2 >= 1;")
        val pb = out.problem.factors[0] as PseudoBoolean
        assertEquals(PbOp.GE, pb.op)
        assertEquals(1L, pb.bound)
    }

    @Test
    fun `a min objective should parse and fold its negation constant`() {
        val text = """
            min: 2 x1 +3 ~x2 ;
            +1 x1 +1 x2 >= 1 ;
        """.trimIndent()
        val out = Opb.parse(text)
        val obj = assertNotNull(out.objective)
        assertEquals(2L, obj.boolWeights[0])
        assertEquals(-3L, obj.boolWeights[1])
        assertEquals(3L, obj.constant)
        assertEquals(0, obj.intCoefficients.size)
    }

    @Test
    fun `objective coefficients beyond 53 bits of precision should be preserved`() {
        // 2^53 + 1 is the smallest positive integer a Double cannot represent exactly; routing the
        // coefficient through Double would round it to 2^53.
        val big = 9007199254740993L
        val out = Opb.parse("min: $big x1 ;\n+1 x1 >= 0 ;\n")
        val obj = assertNotNull(out.objective)
        assertEquals(big, obj.boolWeights[0])
    }

    @Test
    fun `a product term should reify to an and indicator`() {
        val text = "+1 x1 x2 >= 1 ;"
        val out = Opb.parse(text)
        // x1, x2, plus a fresh indicator for the product.
        assertEquals(3, out.problem.numBoolVars)
        val pb = out.problem.factors.filterIsInstance<PseudoBoolean>().single()
        assertEquals(listOf(Lit.make(2, true)), pb.literals.toList())
        assertEquals(listOf(1L), pb.weights.toList())
        // AND of two literals lowers to three Tseitin clauses.
        assertEquals(3, out.problem.factors.filterIsInstance<Clause>().size)
    }

    @Test
    fun `equal products should share one indicator`() {
        val text = "+1 x1 x2 +1 x2 x1 >= 1 ;"
        val out = Opb.parse(text)
        assertEquals(3, out.problem.numBoolVars)
        assertEquals(3, out.problem.factors.filterIsInstance<Clause>().size)
        val pb = out.problem.factors.filterIsInstance<PseudoBoolean>().single()
        assertEquals(listOf(Lit.make(2, true), Lit.make(2, true)), pb.literals.toList())
    }

    @Test
    fun `a wbo soft constraint should reify into an objective penalty`() {
        val text = """
            soft: 5 ;
            [3] +1 x1 >= 1 ;
            +1 x2 >= 1 ;
        """.trimIndent()
        val out = Opb.parse(text)
        // x1, x2, plus the soft constraint's reifying indicator.
        assertEquals(3, out.problem.numBoolVars)
        val reif = out.problem.factors.filterIsInstance<ReifiedPseudoBoolean>().single()
        assertEquals(2, reif.auxBoolVar)
        val obj = assertNotNull(out.objective)
        // Violation costs 3, charged as 3*(1 - sat).
        assertEquals(-3L, obj.boolWeights[2])
        assertEquals(3L, obj.constant)
    }

    @Test
    fun `violated cost should be bounded by the soft top`() {
        val text = """
            soft: 5 ;
            [3] +1 x1 >= 1 ;
        """.trimIndent()
        val out = Opb.parse(text)
        val bound = out.problem.factors.filterIsInstance<PseudoBoolean>().single { it.op == PbOp.LE }
        // Violated cost stays below the top of 5; the soft indicator is aux var 1.
        assertEquals(4L, bound.bound)
        assertEquals(listOf(3L), bound.weights.toList())
        assertEquals(listOf(Lit.make(1, false)), bound.literals.toList())
    }

    @Test
    fun `a constraint statement missing its terminator or operator should be rejected`() {
        val malformed = listOf("+1 x1 >= 1", "+1 x1 1 ;")
        for (text in malformed) {
            assertTrue(runCatching { Opb.parse(text) }.isFailure, text)
        }
    }

    @Test
    fun `tokens after a constraint right hand side should be rejected`() {
        val error = parseError("+1 x1 >= 1 unexpected ;")
        assertTrue("after its right-hand side" in error, error)
    }

    @Test
    fun `a wide coefficient or bound should route to a wide linear row`() {
        // 2^63 (one past signed Long) as a coefficient, and an over-Int64 bound: both kept in the wide lane.
        val out = Opb.parse("+9223372036854775808 x1 +1 x2 >= 99999999999999999999 ;")
        val lin = out.problem.factors.filterIsInstance<Linear>().single()
        assertTrue(lin.wideConstants != null)
        assertEquals(2, out.problem.numIntVars)
    }

    @Test
    fun `wide values should be rejected where the target cannot be wide`() {
        // Normalized objective coefficients and WBO soft costs must fit Long; wide soft rows are unsupported.
        assertTrue("64-bit range" in parseError("min: 99999999999999999999 x1 ;\n+1 x1 >= 0 ;\n"))
        assertTrue("64-bit range" in parseError("[99999999999999999999] +1 x1 >= 1 ;"))
        assertTrue("soft constraint" in parseError("soft: 5 ;\n[1] +18446744073709551616 x1 >= 1 ;"))
    }

    @Test
    fun `a negative wbo soft cost should be rejected`() {
        assertTrue("non-negative" in parseError("[-1] +1 x1 >= 1 ;"))
    }

    private fun parseError(text: String): String = runCatching { Opb.parse(text) }.exceptionOrNull()?.message.orEmpty()

    @Test
    fun `parse should not depend on source chunk boundaries`() {
        val text = "* #variable= 3 #constraint= 1\n+1 x1 +1 x2 +1 x3 >= 2 ;\n"
        fun shape(source: CharSource) = com.eignex.klause.formats.opb.Opb.parse(source).toProblem().problem
            .let { Triple(it.numBoolVars, it.numIntVars, it.factors.size) }
        assertEquals(shape(StringCharSource(text)), shape(perCharSource(text)))
    }
}
