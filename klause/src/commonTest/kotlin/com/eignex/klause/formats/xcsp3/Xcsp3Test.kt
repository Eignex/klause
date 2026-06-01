package com.eignex.klause.formats.xcsp3

import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parser tests for the XCSP3 frontend, solved with klause's own backtrack engine. */
class Xcsp3Test {

    private fun sat(xml: String): IntArray {
        val r = BacktrackSolver(Xcsp3.parse(xml).problem).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return r.assignment.ints
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
        val r = BacktrackSolver(parsed.problem).minimize(obj, BacktrackParams())
        assertTrue(r is MinimizeResult.Optimal, "expected Optimal, got $r")
        assertEquals(-10.0, r.objective) // internal min of negated maximize (max of 10).
    }

    @Test
    fun `negative conflicts table lowers to a positive Table over the complement`() {
        val xml = """
            <instance type="CSP">
              <variables><var id="a"> 1..2 </var><var id="b"> 1..2 </var></variables>
              <constraints>
                <extension><list> a b </list><conflicts> (1,1)(2,2) </conflicts></extension>
              </constraints>
            </instance>
        """.trimIndent()
        assertTrue(Xcsp3.parse(xml).problem.factors.any { it is Table })
        val v = sat(xml)
        assertTrue(v[0] != v[1], "conflicts forbid equal pairs: a=${v[0]} b=${v[1]}")
    }

    @Test
    fun `boolean intension or of relations is reified and solved`() {
        val xml = """
            <instance type="CSP">
              <variables><var id="x"> 0..1 </var><var id="y"> 0..1 </var></variables>
              <constraints>
                <intension> or(eq(x,1),eq(y,1)) </intension>
                <intension> not(and(eq(x,1),eq(y,1))) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        val v = sat(xml)
        assertTrue((v[0] == 1) != (v[1] == 1), "exactly one of x,y must be 1: x=${v[0]} y=${v[1]}")
    }

    @Test
    fun `count maps to Count + condition`() {
        val xml = """
            <instance type="CSP">
              <variables><array id="x" size="[3]"> 0..2 </array></variables>
              <constraints>
                <count><list> x[] </list><values> 1 </values><condition> (eq,2) </condition></count>
              </constraints>
            </instance>
        """.trimIndent()
        val v = sat(xml)
        assertEquals(2, (0..2).count { v[it] == 1 }, "exactly two ones expected")
    }

    @Test
    fun `element selects the indexed array cell`() {
        val xml = """
            <instance type="CSP">
              <variables>
                <array id="t" size="[3]"> 1..3 </array>
                <var id="i"> 0..2 </var><var id="v"> 1..3 </var>
              </variables>
              <constraints>
                <element startIndex="0"><list> t[] </list><index> i </index><value> v </value></element>
                <intension> eq(i,1) </intension>
                <intension> eq(v,2) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        assertTrue(Xcsp3.parse(xml).problem.factors.any { it is Element })
        val v = sat(xml)
        // i=1, v=2 ⇒ t[1] must equal 2. ids: t[0]=0,t[1]=1,t[2]=2,i=3,v=4
        assertEquals(2, v[1])
        assertEquals(1, v[3])
        assertEquals(2, v[4])
    }

    @Test
    fun `channel maps to Inverse and the permutation is consistent`() {
        val xml = """
            <instance type="CSP">
              <variables>
                <array id="x" size="[3]"> 0..2 </array><array id="y" size="[3]"> 0..2 </array>
              </variables>
              <constraints>
                <channel><list> x[] </list><list> y[] </list></channel>
                <intension> eq(x[0],1) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        assertTrue(Xcsp3.parse(xml).problem.factors.any { it is Inverse })
        val v = sat(xml)
        for (i in 0..2) {
            val j = v[i]
            assertEquals(i, v[3 + j], "y[$j] should equal $i")
        }
    }

    @Test
    fun `circuit forms a single Hamiltonian cycle`() {
        val xml = """
            <instance type="CSP">
              <variables><array id="s" size="[3]"> 0..2 </array></variables>
              <constraints><circuit><list> s[] </list></circuit></constraints>
            </instance>
        """.trimIndent()
        assertTrue(Xcsp3.parse(xml).problem.factors.any { it is Circuit })
        val v = sat(xml)
        val seen = HashSet<Int>()
        var cur = 0
        repeat(3) {
            seen.add(cur)
            cur = v[cur]
        }
        assertEquals(setOf(0, 1, 2), seen)
        assertEquals(0, cur)
    }

    @Test
    fun `lex orders two lists strictly`() {
        val xml = """
            <instance type="CSP">
              <variables>
                <array id="a" size="[2]"> 0..1 </array><array id="b" size="[2]"> 0..1 </array>
              </variables>
              <constraints>
                <lex><list> a[] </list><list> b[] </list><operator> lt </operator></lex>
                <intension> eq(a[0],b[0]) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        assertTrue(Xcsp3.parse(xml).problem.factors.any { it is LexLess })
        val v = sat(xml)
        assertTrue(v[0] == v[2] && v[1] < v[3], "a=${v[0]},${v[1]} b=${v[2]},${v[3]}")
    }

    @Test
    fun `cumulative keeps resource use within capacity`() {
        val xml = """
            <instance type="CSP">
              <variables><array id="o" size="[2]"> 0..3 </array></variables>
              <constraints>
                <cumulative>
                  <origins> o[] </origins><lengths> 2 2 </lengths><heights> 1 1 </heights>
                  <condition> (le,1) </condition>
                </cumulative>
              </constraints>
            </instance>
        """.trimIndent()
        assertTrue(Xcsp3.parse(xml).problem.factors.any { it is Cumulative })
        val v = sat(xml)
        assertTrue(v[0] + 2 <= v[1] || v[1] + 2 <= v[0], "tasks overlap: starts ${v[0]},${v[1]}")
    }

    @Test
    fun `regular accepts exactly via the DFA with 0-based symbols shifted`() {
        val xml = """
            <instance type="CSP">
              <variables><array id="w" size="[3]"> 0..1 </array></variables>
              <constraints>
                <regular>
                  <list> w[] </list>
                  <transitions> (q0,0,q0)(q0,1,q1)(q1,0,q1)(q1,1,q0) </transitions>
                  <start> q0 </start><final> q1 </final>
                </regular>
              </constraints>
            </instance>
        """.trimIndent()
        assertTrue(Xcsp3.parse(xml).problem.factors.any { it is Regular })
        val v = sat(xml)
        assertEquals(1, (0..2).count { v[it] == 1 } % 2, "DFA accepts an odd number of 1s")
    }

    @Test
    fun `group instantiates a template per args row with range refs and placeholders`() {
        val xml = """
            <instance type="CSP">
              <variables><array id="x" size="[4]"> 0..3 </array></variables>
              <constraints>
                <allDifferent> x[] </allDifferent>
                <group>
                  <sum><list> %0 %1 </list><condition> (le,3) </condition></sum>
                  <args> x[0..1] </args>
                  <args> x[2..3] </args>
                </group>
              </constraints>
            </instance>
        """.trimIndent()
        val p = Xcsp3.parse(xml).problem
        assertEquals(4, p.numIntVars)
        assertEquals(2, p.factors.count { it is Linear }, "one sum per <args> row")
        assertTrue(p.factors.any { it is AllDifferent })
        val v = sat(xml)
        assertTrue(v[0] + v[1] <= 3 && v[2] + v[3] <= 3, "row sums: ${v.toList()}")
    }

    @Test
    fun `objective minimizes the maximum of a list`() {
        val xml = """
            <instance type="COP">
              <variables>
                <var id="a"> 0..5 </var><var id="b"> 0..5 </var><var id="c"> 0..5 </var>
              </variables>
              <constraints>
                <sum><list> a b c </list><condition> (ge,6) </condition></sum>
              </constraints>
              <objectives><minimize type="maximum"><list> a b c </list></minimize></objectives>
            </instance>
        """.trimIndent()
        val parsed = Xcsp3.parse(xml)
        val obj = requireNotNull(parsed.objective)
        val r = BacktrackSolver(parsed.problem).minimize(obj, BacktrackParams())
        assertTrue(r is MinimizeResult.Optimal, "expected Optimal, got $r")
        assertEquals(2.0, r.objective) // sum >= 6 over three vars ⇒ max minimized at 2.
    }
}
