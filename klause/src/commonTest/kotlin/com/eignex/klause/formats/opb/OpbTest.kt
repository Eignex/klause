package com.eignex.klause.formats.opb

import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpbTest {

    @Test
    fun `a malformed opb instance is a catchable format exception`() {
        // A constraint with no relational operator must surface as an OpbFormatException, catchable via
        // the shared FormatException supertype rather than a raw IllegalStateException.
        assertFailsWith<OpbFormatException> { Opb.parse("+1 x1 +2 x2 8 ;\n") }
    }

    @Test
    fun `parses mixed constraints without objective`() {
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
    fun `accepts a terminator glued to the preceding token`() {
        // The `;` need not be whitespace-separated; `1;` is a well-formed end of statement.
        val out = Opb.parse("+1 x1 +1 x2 >= 1;")
        val pb = out.problem.factors[0] as PseudoBoolean
        assertEquals(PbOp.GE, pb.op)
        assertEquals(1L, pb.bound)
    }

    @Test
    fun `parses negated literals`() {
        val text = """
            +1 ~x1 +2 x2 <= 1 ;
        """.trimIndent()
        val out = Opb.parse(text)
        val pb = out.problem.factors[0] as PseudoBoolean
        assertEquals(Lit.make(0, false), pb.literals[0])
        assertEquals(Lit.make(1, true), pb.literals[1])
    }

    @Test
    fun `parses min objective and folds negation constant`() {
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
    fun `preserves objective coefficients beyond 53 bits of precision`() {
        // 2^53 + 1 is the smallest positive integer a Double cannot represent exactly; routing the
        // coefficient through Double would round it to 2^53.
        val big = 9007199254740993L
        val out = Opb.parse("min: $big x1 ;\n+1 x1 >= 0 ;\n")
        val obj = assertNotNull(out.objective)
        assertEquals(big, obj.boolWeights[0])
    }

    @Test
    fun `reifies a product term to an and indicator`() {
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
    fun `reifies products over negated literals`() {
        val text = "+1 ~x1 x2 >= 1 ;"
        val out = Opb.parse(text)
        assertEquals(3, out.problem.numBoolVars)
        val pb = out.problem.factors.filterIsInstance<PseudoBoolean>().single()
        assertEquals(listOf(Lit.make(2, true)), pb.literals.toList())
    }

    @Test
    fun `shares one indicator across equal products`() {
        val text = "+1 x1 x2 +1 x2 x1 >= 1 ;"
        val out = Opb.parse(text)
        // A single shared indicator, not one per occurrence.
        assertEquals(3, out.problem.numBoolVars)
        assertEquals(3, out.problem.factors.filterIsInstance<Clause>().size)
        val pb = out.problem.factors.filterIsInstance<PseudoBoolean>().single()
        assertEquals(listOf(Lit.make(2, true), Lit.make(2, true)), pb.literals.toList())
    }

    @Test
    fun `reifies a product term in the objective`() {
        val text = "min: 3 x1 x2 ;"
        val out = Opb.parse(text)
        val obj = assertNotNull(out.objective)
        assertEquals(3, out.problem.numBoolVars)
        assertEquals(3L, obj.boolWeights[2])
    }

    @Test
    fun `reifies a wbo soft constraint into an objective penalty`() {
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
    fun `bounds violated cost with the soft top`() {
        val text = """
            soft: 5 ;
            [3] +1 x1 >= 1 ;
        """.trimIndent()
        val out = Opb.parse(text)
        val bound = out.problem.factors.filterIsInstance<PseudoBoolean>().single { it.op == PbOp.LE }
        // Total violated cost must stay strictly below the top of 5. The soft indicator is the
        // sole aux var (id 1); its violation is the negated indicator literal.
        assertEquals(4L, bound.bound)
        assertEquals(listOf(3L), bound.weights.toList())
        assertEquals(listOf(Lit.make(1, false)), bound.literals.toList())
    }

    @Test
    fun `omits the cost bound when no soft top is given`() {
        val text = "[3] +1 x1 >= 1 ;"
        val out = Opb.parse(text)
        assertTrue(out.problem.factors.none { it is PseudoBoolean })
        assertNotNull(out.objective)
    }

    @Test
    fun `rejects missing terminator`() {
        val text = "+1 x1 >= 1"
        assertTrue(runCatching { Opb.parse(text) }.isFailure)
    }

    @Test
    fun `rejects missing operator`() {
        val text = "+1 x1 1 ;"
        assertTrue(runCatching { Opb.parse(text) }.isFailure)
    }

    @Test
    fun `rejects a coefficient beyond the 64-bit range with a range error`() {
        // 2^63 overflows a signed Long by one.
        assertTrue("64-bit range" in parseError("+9223372036854775808 x1 >= 1 ;"))
    }

    @Test
    fun `rejects an out-of-range right-hand side and soft cost`() {
        assertTrue("64-bit range" in parseError("+1 x1 >= 99999999999999999999 ;"))
        assertTrue("64-bit range" in parseError("[99999999999999999999] +1 x1 >= 1 ;"))
    }

    @Test
    fun `still reports a non-numeric coefficient as not an integer`() {
        assertTrue("not an integer" in parseError("abc x1 >= 1 ;"))
    }

    private fun parseError(text: String): String = runCatching { Opb.parse(text) }.exceptionOrNull()?.message.orEmpty()
}
