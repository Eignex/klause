package com.eignex.klause.formats.xcsp3

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Xcsp3TableTest {

    private fun rows(text: String, arity: Int): ShortRows = Compiler.Builder().parseShortRows(text, arity)

    @Test
    fun `a ground table should parse point bounds`() {
        val r = rows("(0,1)(2,3)", 2)

        assertFalse(r.short)
        assertEquals(listOf(0L, 1L, 2L, 3L), r.lo.toList())
        assertEquals(listOf(0L, 1L, 2L, 3L), r.hi.toList())
    }

    @Test
    fun `an interval cell should give the table distinct bounds arrays`() {
        val r = rows("(0,1)(2,4..7)(*,3)", 2)

        assertTrue(r.short)
        assertEquals(listOf(0L, 1L, 2L, 4L, Long.MIN_VALUE, 3L), r.lo.toList())
        assertEquals(listOf(0L, 1L, 2L, 7L, Long.MAX_VALUE, 3L), r.hi.toList())
    }

    @Test
    fun `points read before the first interval should keep their bounds when the upper array appears`() {
        val r = rows("(0,1)(2,3)(4,5..9)", 2)

        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), r.lo.toList())
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 9L), r.hi.toList())
    }

    @Test
    fun `tuple and unary tables should preserve every parsed value`() {
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), rows("(0,1,2)(3,4,5)", 3).lo.toList())
        assertEquals(listOf(7L, 8L, 9L, 10L), rows(" 7 8 9 10 ", 1).lo.toList())
    }

    @Test
    fun `negative tables should preserve values beyond the 32-bit range`() {
        val source = Xcsp3.parse("""
            <instance type="CSP">
              <variables><var id="x">4294967296..4294967297</var></variables>
              <constraints><extension><list>x</list><conflicts>4294967296</conflicts></extension></constraints>
            </instance>
        """.trimIndent())

        val result = BacktrackSolver(source.problem.bake()).solve(BacktrackParams())

        assertTrue(result is SolveResult.Sat)
        assertEquals(4294967297L, result.assignment.ints[source.intVarNames.getValue("x")])
    }

    @Test
    fun `negative table clauses should forbid intervals at integer limits`() {
        val intervals = listOf(
            "-2147483648..-2147483647" to -2147483648L,
            "2147483646..2147483647" to 2147483647L,
            "${Long.MIN_VALUE}..${Long.MIN_VALUE + 1}" to Long.MIN_VALUE,
            "${Long.MAX_VALUE - 1}..${Long.MAX_VALUE}" to Long.MAX_VALUE,
        )
        for ((interval, value) in intervals) {
            val builder = Compiler.Builder()
            builder.addVar("x", IntDomain(value, value))
            builder.postConflictClauses(intArrayOf(0), builder.parseShortRows(interval, 1))

            val result = BacktrackSolver(builder.build().problem.bake()).solve(BacktrackParams())

            assertTrue(result is SolveResult.Unsat, interval)
        }
    }

    @Test
    fun `table cells should retain signed 64-bit limits`() {
        assertEquals(listOf(Long.MIN_VALUE, Long.MAX_VALUE),
            rows("${Long.MIN_VALUE} ${Long.MAX_VALUE}", 1).lo.toList())
    }

    @Test
    fun `table cells should reject overflowing integers`() {
        for (text in listOf("9223372036854775808", "-9223372036854775809", "0..9223372036854775808")) {
            assertFailsWith<IllegalArgumentException>(text) { rows(text, 1) }
        }
    }

    @Test
    fun `table cells should reject reversed intervals`() {
        assertFailsWith<IllegalArgumentException> { rows("2..1", 1) }
    }
}
