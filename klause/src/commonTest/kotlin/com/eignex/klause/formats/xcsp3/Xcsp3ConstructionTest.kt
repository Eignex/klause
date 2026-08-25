package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Xcsp3ConstructionTest {

    @Test
    fun `a sum condition constructs its declared variables and normalized row`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><var id="x">1..3</var><var id="y">2..4</var></variables>
                <constraints><sum><list>x y</list><coeffs>2 -1</coeffs><condition>(eq, 1)</condition></sum></constraints>
                </instance>""",
        )

        assertEquals(mapOf("x" to 0, "y" to 1), parsed.intVarNames)
        assertEquals(1L, parsed.problem.intDomainOrNull(0)!!.min)
        assertEquals(4L, parsed.problem.intDomainOrNull(1)!!.max)
        val row = parsed.problem.factors.single() as Linear
        assertContentEquals(intArrayOf(0, 1), row.vars)
        assertContentEquals(longArrayOf(2, -1), row.integerConstants!!.coeffs)
        assertEquals(LinearOp.EQ, row.op)
        assertEquals(1L, row.integerConstants!!.bound)
    }

    @Test
    fun `a malformed streamed closing tag reports an XCSP3 error`() {
        val error = assertFailsWith<UnsupportedXcsp3Exception> {
            Xcsp3.parse("<instance><variables></constraints></instance>")
        }

        assertTrue(error.message.orEmpty().contains("mismatched closing tag"))
    }

    @Test
    fun `a processing instruction between streamed children is ignored`() {
        val parsed = Xcsp3.parse(
            "<instance><variables><?tool ignore?><var id=\"x\">0..1</var></variables></instance>",
        )

        assertEquals(mapOf("x" to 0), parsed.intVarNames)
    }
}
