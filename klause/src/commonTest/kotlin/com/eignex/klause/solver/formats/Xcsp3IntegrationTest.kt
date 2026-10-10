package com.eignex.klause.solver.formats

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.xcsp3.FExpr
import com.eignex.klause.formats.xcsp3.UnsupportedXcsp3Exception
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Xcsp3IntegrationTest {

    private fun sat(xml: String): IntArray {
        val r = BacktrackSolver(Xcsp3.parse(xml).problem.bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return IntArray(r.assignment.ints.size) { r.assignment.ints[it].toInt() }
    }

    @Test
    fun `an over-Int64 value that must be materialised is declined`() {
        // abs() materialises its operand into a variable, which cannot hold an over-Int64 value.
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables><var id="x"> 0..3 </var></variables>
              <constraints>
                <intension> le(abs(mul(2000000000,2000000000,2000000000,x)), 5) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        assertFailsWith<UnsupportedXcsp3Exception> { Xcsp3.parse(xml) }
    }

    @Test
    fun `parses 4-queens CSP with array allDifferent and intension and is SAT`() {
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables><array id="q" size="[4]"> 1..4 </array></variables>
              <constraints>
                <allDifferent> q[] </allDifferent>
                <intension> ne(add(q[0],0), add(q[1],1)) </intension>
                <intension> ne(sub(q[0],0), sub(q[1],1)) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        val p = Xcsp3.parse(xml).problem
        assertEquals(4, p.numIntVars)
        assertTrue(p.factors.any { it is AllDifferent })
        assertTrue(p.factors.count { it is Linear } >= 2)
        sat(xml)
    }

    @Test
    fun `a malformed document surfaces as a catchable format exception`() {
        // An unterminated start tag makes the XML scanner throw; it must reach the caller as an
        // UnsupportedXcsp3Exception, not the raw IllegalArgumentException from the scanner's require.
        assertFailsWith<UnsupportedXcsp3Exception> { Xcsp3.parse("<a") }
    }

    @Test
    fun `parses COP with sum constraint and maximize objective and optimizes`() {
        val xml = """
            <instance format="XCSP3" type="COP">
              <variables>
                <var id="a"> 0..2 </var><var id="b"> 0..2 </var><var id="c"> 0..2 </var>
              </variables>
              <constraints>
                <sum><list> a b c </list><coeffs> 1 1 1 </coeffs><condition> (le,4) </condition></sum>
              </constraints>
              <objectives><maximize type="sum"><list> a b c </list><coeffs> 3 2 1 </coeffs></maximize></objectives>
            </instance>
        """.trimIndent()
        val parsed = Xcsp3.parse(xml)
        val obj = requireNotNull(parsed.objective)
        val r = BacktrackSolver(parsed.problem.bake()).minimize(obj.toLinearObjective(), BacktrackParams())
        assertTrue(r is MinimizeResult.Optimal, "expected Optimal, got $r")
        assertEquals(-10.0, r.objective)
    }

    @Test
    fun `a star column over a wide domain is a short support and does not hit the cap`() {
        // The only allowed tuple is (any a, b=1). Expanding the '*' over a's 1.5M-value domain would
        // exceed the 1M cap; as a short support it is a single wildcard row, so it parses and pins b.
        val xml = """
            <instance type="CSP">
              <variables><var id="a"> 0..1500000 </var><var id="b"> 0..3 </var></variables>
              <constraints><extension><list> a b </list><supports> (*,1) </supports></extension></constraints>
            </instance>
        """.trimIndent()
        assertEquals(1, sat(xml)[Xcsp3.parse(xml).intVarNames.getValue("b")], "b pinned to 1 by the only support")
    }

    @Test
    fun `a very wide contiguous domain parses without enumerating every value`() {
        // A billion-value domain: materializing every value would exhaust the heap; a contiguous
        // interval parses in constant space and the pin still resolves.
        val xml = """
            <instance type="CSP">
              <variables><var id="a"> 0..1000000000 </var></variables>
              <constraints><intension> eq(a,999999999) </intension></constraints>
            </instance>
        """.trimIndent()
        assertEquals(999999999, sat(xml)[Xcsp3.parse(xml).intVarNames.getValue("a")], "the pin resolves")
    }

    @Test
    fun `a domain with an interior hole excludes the gap value`() {
        val decl = """<instance type="CSP"><variables><var id="x"> 1..3 5 </var></variables>"""
        fun solve(c: String): SolveResult = BacktrackSolver(
            Xcsp3.parse("$decl<constraints>$c</constraints></instance>").problem.bake(),
        ).solve(BacktrackParams())
        assertTrue(solve("<intension> eq(x,4) </intension>") is SolveResult.Unsat, "4 is a hole, not in the domain")
        assertTrue(solve("<intension> eq(x,5) </intension>") is SolveResult.Sat, "5 is in the domain")
    }

    @Test
    fun `allEqual with except is rejected`() {
        assertFailsWith<UnsupportedXcsp3Exception> {
            Xcsp3.parse(
                """
                <instance type="CSP"><variables><array id="x" size="[2]"> 0..2 </array></variables>
                <constraints><allEqual><list> x[] </list><except> 0 </except></allEqual></constraints></instance>
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `declares 2D array cells and resolves wildcard range and mixed index references`() {
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables><array id="x" size="[2][3]"> 0..9 </array></variables>
              <constraints>
                <allDifferent> x[][] </allDifferent>
                <allDifferent> x[0][] </allDifferent>
                <allDifferent> x[0..1][0] </allDifferent>
              </constraints>
            </instance>
        """.trimIndent()
        val p = Xcsp3.parse(xml).problem
        assertEquals(6, p.numIntVars) // 2 x 3 cells
        val alldiffs = p.factors.filterIsInstance<AllDifferent>()
        assertEquals(listOf(6, 3, 2), alldiffs.map { it.vars.size })
        sat(xml)
    }

    @Test
    fun `a group shares one extension tuple array across its rows`() {
        // Every row of the group is the same `<supports>` template, so the parsed tuple array is
        // cached by text identity and shared — re-allocating it per row exhausts the heap on a large,
        // high-arity group.
        val xml = """
            <instance type="CSP">
              <variables><array id="x" size="[3][2]"> 0..1 </array></variables>
              <constraints>
                <group>
                  <extension><list> %0 %1 </list><supports> (0,1)(1,0) </supports></extension>
                  <args> x[0][0] x[0][1] </args>
                  <args> x[1][0] x[1][1] </args>
                  <args> x[2][0] x[2][1] </args>
                </group>
              </constraints>
            </instance>
        """.trimIndent()
        val tables = Xcsp3.parse(xml).problem.factors.filterIsInstance<Table>()
        assertEquals(3, tables.size, "one table per args row")
        assertSame(tables[0].tuples, tables[1].tuples, "rows share the cached tuple array")
        assertSame(tables[1].tuples, tables[2].tuples, "rows share the cached tuple array")
    }

    @Test
    fun `a truncated intension call fails with a disciplined require error rather than an index out of bounds`() {
        // A call whose argument list is cut off after '(' must report the missing ')' like the file's
        // other guarded reads, rather than dereferencing past the end of the input.
        assertFailsWith<IllegalArgumentException> { FExpr.parse("eq(") }
    }
}
