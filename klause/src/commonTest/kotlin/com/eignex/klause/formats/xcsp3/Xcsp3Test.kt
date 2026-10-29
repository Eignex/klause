package com.eignex.klause.formats.xcsp3

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.perCharSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Xcsp3Test {
    @Test
    fun `string and streaming sources should retain the same declared semantics`() {
        val xml = """
            <instance format="XCSP3" type="CSP"><variables><var id="x">0..2</var></variables></instance>
        """.trimIndent()

        val sources = listOf(Xcsp3.parse(xml), Xcsp3.parse(StringCharSource(xml)))

        for (source in sources) {
            assertEquals(xcsp3Semantics, source.semantics.declared)
            assertEquals(xcsp3Semantics, source.problem.semantics.effective)
        }
    }

    @Test
    fun `XCSP3 should reject an undeclared integer range override`() {
        val xml = """
            <instance format="XCSP3" type="CSP"><variables><var id="x">0..2</var></variables></instance>
        """.trimIndent()

        val error = assertFailsWith<UnsupportedXcsp3Exception> {
            Xcsp3.parse(xml, semanticOverrides = SemanticOverrides(
                undeclaredIntegers = BoundsOverride(lower = BoundOverride.Open),
            ))
        }

        assertTrue("undeclared integer bounds do not apply" in error.message.orEmpty())
    }

    @Test
    fun `XCSP3 declaration builders should capture the same ambient resources as parsing`() {
        val saved = KlauseConfig.current
        try {
            KlauseConfig.current = saved.copy(largeSpanThreshold = 17)

            val built = Compiler.Builder().build()
            val parsed = Xcsp3.parse(
                """<instance format="XCSP3" type="CSP"><variables><var id="x">0..2</var></variables></instance>""",
            )

            assertEquals(17L, built.problem.settings.largeSpanThreshold)
            assertEquals(parsed.problem.settings, built.problem.settings)
        } finally {
            KlauseConfig.current = saved
        }
    }


    private fun sat(xml: String): IntArray {
        val r = BacktrackSolver(Xcsp3.parse(xml).problem.bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return IntArray(r.assignment.ints.size) { r.assignment.ints[it].toInt() }
    }

    @Test
    fun `an over-Int64 value that must be materialised should be declined`() {
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
    fun `a 4-queens CSP with allDifferent and intension should parse and solve SAT`() {
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
    fun `a malformed document should surface as a catchable format exception`() {
        assertFailsWith<UnsupportedXcsp3Exception> { Xcsp3.parse("<a") }
    }

    @Test
    fun `a COP with a sum constraint and maximize objective should solve to its optimum`() {
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
    fun `maximizing the minimum 32-bit coefficient should preserve objective direction`() {
        val parsed = Xcsp3.parse(
            """
            <instance type="COP"><variables><var id="x">0..1</var></variables>
              <objectives><maximize><list>x</list><coeffs>-2147483648</coeffs></maximize></objectives>
            </instance>
            """.trimIndent(),
        )

        val result = BacktrackSolver(parsed.problem.bake()).minimize(requireNotNull(parsed.objective).toLinearObjective())

        assertTrue(result is MinimizeResult.Optimal, "expected Optimal, got $result")
        assertEquals(0.0, result.objective)
    }

    @Test
    fun `a star column over a wide domain should parse as a short support below the cap`() {
        // Expanding '*' over a's 1.5M values would exceed the 1M tuple cap.
        val xml = """
            <instance type="CSP">
              <variables><var id="a"> 0..1500000 </var><var id="b"> 0..3 </var></variables>
              <constraints><extension><list> a b </list><supports> (*,1) </supports></extension></constraints>
            </instance>
        """.trimIndent()
        assertEquals(1, sat(xml)[Xcsp3.parse(xml).intVarNames.getValue("b")])
    }

    @Test
    fun `a very wide contiguous domain should parse without enumerating every value`() {
        val xml = """
            <instance type="CSP">
              <variables><var id="a"> 0..1000000000 </var></variables>
              <constraints><intension> eq(a,999999999) </intension></constraints>
            </instance>
        """.trimIndent()
        assertEquals(999999999, sat(xml)[Xcsp3.parse(xml).intVarNames.getValue("a")])
    }

    @Test
    fun `a domain with an interior hole should exclude the gap value`() {
        val decl = """<instance type="CSP"><variables><var id="x"> 1..3 5 </var></variables>"""
        fun solve(c: String): SolveResult = BacktrackSolver(
            Xcsp3.parse("$decl<constraints>$c</constraints></instance>").problem.bake(),
        ).solve(BacktrackParams())
        assertTrue(solve("<intension> eq(x,4) </intension>") is SolveResult.Unsat)
        assertTrue(solve("<intension> eq(x,5) </intension>") is SolveResult.Sat)
    }

    @Test
    fun `allEqual with except should be rejected`() {
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
    fun `a 2D array should resolve wildcard range and mixed index references`() {
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
    fun `a group should share one extension tuple array across its rows`() {
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
        assertEquals(3, tables.size)
        assertSame(tables[0].tuples, tables[1].tuples)
        assertSame(tables[1].tuples, tables[2].tuples)
    }

    @Test
    fun `parse should not depend on source chunk boundaries`() {
        val text = "<instance format='XCSP3' type='CSP'><variables><var id='x'> 0..4 </var>" +
            "<var id='y'> 0..4 </var></variables><constraints><intension> le(add(x,y),4) </intension>" +
            "</constraints></instance>"
        fun shape(source: CharSource) =
            Xcsp3.parse(source).problem.let { Triple(it.numBoolVars, it.numIntVars, it.factors.size) }
        assertEquals(shape(StringCharSource(text)), shape(perCharSource(text)))
    }
}
