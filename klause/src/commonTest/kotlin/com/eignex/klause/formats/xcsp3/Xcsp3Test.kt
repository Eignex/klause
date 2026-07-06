package com.eignex.klause.formats.xcsp3

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.LexLess
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertEquals(-10.0, r.objective)
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
    fun `instantiation ordered and allEqual constrain the listed variables`() {
        val inst = sat(
            """
            <instance type="CSP"><variables><array id="x" size="[3]"> 0..9 </array></variables>
            <constraints><instantiation><list> x[] </list><values> 5 3 8 </values></instantiation></constraints></instance>
            """.trimIndent(),
        )
        assertEquals(listOf(5, 3, 8), inst.take(3))
        val ord = sat(
            """
            <instance type="CSP"><variables><array id="x" size="[3]"> 0..9 </array></variables>
            <constraints><ordered><list> x[] </list><operator> lt </operator></ordered></constraints></instance>
            """.trimIndent(),
        )
        assertTrue(ord[0] < ord[1] && ord[1] < ord[2], "strictly increasing: ${ord.take(3)}")
    }

    @Test
    fun `div mod min max and if arithmetic evaluate correctly`() {
        fun one(constraints: String, resultVar: Int): Int = sat(
            "<instance type=\"CSP\"><variables>" +
                "<var id=\"a\"> 0..99 </var><var id=\"x\"> 0..9 </var><var id=\"r\"> 0..200 </var>" +
                "</variables><constraints>$constraints</constraints></instance>",
        )[resultVar]
        assertEquals(3, one("<intension> eq(a,17) </intension><intension> eq(r,div(a,5)) </intension>", 2))
        assertEquals(2, one("<intension> eq(a,17) </intension><intension> eq(r,mod(a,5)) </intension>", 2))
        assertEquals(8, one("<intension> eq(r,max(3,8,5)) </intension>", 2))
        assertEquals(3, one("<intension> eq(r,min(3,8,5)) </intension>", 2))
        assertEquals(7, one("<intension> eq(x,2) </intension><intension> eq(r,if(gt(x,5),100,7)) </intension>", 2))
    }

    @Test
    fun `a boolean subexpression used arithmetically counts as 0 or 1`() {
        val v = sat(
            """
            <instance type="CSP"><variables>
              <var id="x"> 0..9 </var><var id="y"> 0..9 </var><var id="r"> 0..2 </var>
            </variables><constraints>
              <intension> eq(r,add(gt(x,5),gt(y,5))) </intension>
              <intension> eq(x,7) </intension><intension> eq(y,2) </intension>
            </constraints></instance>
            """.trimIndent(),
        )
        assertEquals(1, v[2]) // (7>5)=1 + (2>5)=0
    }

    @Test
    fun `abs and dist arithmetic evaluate to absolute values`() {
        val a = sat(
            """
            <instance type="CSP"><variables><var id="x"> -9..9 </var><var id="r"> 0..20 </var></variables>
            <constraints><intension> eq(r,abs(x)) </intension><intension> eq(x,-5) </intension></constraints></instance>
            """.trimIndent(),
        )
        assertEquals(5, a[1])
        val d = sat(
            """
            <instance type="CSP"><variables><var id="a"> 0..9 </var><var id="b"> 0..9 </var></variables>
            <constraints><intension> eq(dist(a,b),3) </intension><intension> eq(a,7) </intension></constraints></instance>
            """.trimIndent(),
        )
        assertEquals(3, abs(d[0] - d[1]))
    }

    @Test
    fun `minimum with a condition constrains the least value`() {
        val v = sat(
            """
            <instance type="CSP"><variables>
              <var id="a"> 2..9 </var><var id="b"> 2..9 </var><var id="c"> 2..9 </var>
            </variables>
            <constraints><minimum><list> a b c </list><condition> (eq,2) </condition></minimum></constraints></instance>
            """.trimIndent(),
        )
        assertEquals(2, minOf(v[0], v[1], v[2]))
    }

    @Test
    fun `allDifferent over constants and expressions forbids collisions`() {
        val v = sat(
            """
            <instance type="CSP"><variables><var id="x"> 4..6 </var></variables>
            <constraints><allDifferent> x add(x,1) 5 </allDifferent></constraints></instance>
            """.trimIndent(),
        )
        assertEquals(6, v[0])
    }

    @Test
    fun `cardinality enforces the required value occurrences`() {
        val v = sat(
            """
            <instance type="CSP"><variables><array id="x" size="[3]"> 0..1 </array></variables>
            <constraints><cardinality><list> x[] </list><values> 1 </values><occurs> 2 </occurs></cardinality></constraints></instance>
            """.trimIndent(),
        )
        assertEquals(2, v.take(3).count { it == 1 })
    }

    @Test
    fun `mdd accepts only the values on a path to a sink`() {
        val sat = sat(
            """
            <instance type="CSP"><variables><array id="x" size="[1]"> 0..9 </array></variables>
            <constraints><mdd><list> x[] </list><transitions> (root,5,t)(root,6,t) </transitions></mdd></constraints></instance>
            """.trimIndent(),
        )
        assertTrue(sat[0] in setOf(5, 6))
        val r = BacktrackSolver(
            Xcsp3.parse(
                """
                <instance type="CSP"><variables><array id="x" size="[1]"> 0..9 </array></variables>
                <constraints><mdd><list> x[] </list><transitions> (root,5,t)(root,6,t) </transitions></mdd>
                <intension> eq(x[0],3) </intension></constraints></instance>
                """.trimIndent(),
            ).problem,
        ).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "3 is not on an MDD path: $r")
    }

    @Test
    fun `knapsack satisfies both the weight and profit conditions`() {
        val v = sat(
            """
            <instance type="CSP"><variables><array id="x" size="[3]"> 0..1 </array></variables>
            <constraints><knapsack><list> x[] </list>
              <weights> 2 3 4 </weights><condition> (le,5) </condition>
              <profits> 5 6 7 </profits><condition> (ge,6) </condition>
            </knapsack></constraints></instance>
            """.trimIndent(),
        )
        val w = (0..2).sumOf { listOf(2, 3, 4)[it] * v[it] }
        val p = (0..2).sumOf { listOf(5, 6, 7)[it] * v[it] }
        assertTrue(w <= 5 && p >= 6, "w=$w p=$p")
    }

    @Test
    fun `binPacking keeps each bin within capacity`() {
        val v = sat(
            """
            <instance type="CSP"><variables><array id="x" size="[3]"> 0..1 </array></variables>
            <constraints><binPacking><list> x[] </list><sizes> 4 4 4 </sizes><condition> (le,8) </condition></binPacking></constraints></instance>
            """.trimIndent(),
        )
        val load = IntArray(2)
        for (i in 0..2) load[v[i]] += 4
        assertTrue(load.all { it <= 8 }, "bin over capacity: ${load.toList()}")
    }

    @Test
    fun `nValues counts distinct values`() {
        val v = sat(
            """
            <instance type="CSP"><variables><array id="x" size="[3]"> 0..2 </array><var id="z"> 2..2 </var></variables>
            <constraints><nValues><list> x[] </list><condition> (eq,z) </condition></nValues></constraints></instance>
            """.trimIndent(),
        )
        assertEquals(2, setOf(v[0], v[1], v[2]).size)
    }

    @Test
    fun `noOverlap keeps unit-resource tasks disjoint`() {
        val v = sat(
            """
            <instance type="CSP"><variables><array id="s" size="[2]"> 0..4 </array></variables>
            <constraints><noOverlap><origins> s[] </origins><lengths> 3 3 </lengths></noOverlap></constraints></instance>
            """.trimIndent(),
        )
        val (s0, s1) = v[0] to v[1]
        assertTrue(s0 + 3 <= s1 || s1 + 3 <= s0, "tasks overlap: s0=$s0 s1=$s1")
    }

    @Test
    fun `an interval sum condition bounds the total between two values`() {
        val v = sat(
            """
            <instance type="CSP"><variables>
              <var id="a"> 0..3 </var><var id="b"> 0..3 </var><var id="c"> 0..3 </var>
            </variables><constraints>
              <sum><list> a b c </list><condition> (in,4..5) </condition></sum>
              <intension> eq(a,2) </intension><intension> eq(b,1) </intension>
            </constraints></instance>
            """.trimIndent(),
        )
        assertTrue(v[0] + v[1] + v[2] in 4..5, "sum out of range: ${v.take(3)}")
    }

    @Test
    fun `slide applies its template over each sliding window`() {
        // conflicts (1,0) forbid a 1 immediately followed by a 0; with x[0]=1 all must be 1.
        val v = sat(
            """
            <instance type="CSP"><variables><array id="x" size="[3]"> 0..1 </array></variables>
            <constraints>
              <slide><list collect="2"> x[] </list>
                <extension><list> %0 %1 </list><conflicts> (1,0) </conflicts></extension>
              </slide>
              <intension> eq(x[0],1) </intension>
            </constraints></instance>
            """.trimIndent(),
        )
        assertEquals(listOf(1, 1, 1), v.take(3))
    }

    @Test
    fun `unequal-length channel enforces the defining biconditional`() {
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables><array id="x" size="[2]"> 0..2 </array><array id="y" size="[3]"> 0..2 </array></variables>
              <constraints>
                <channel><list> x[] </list><list> y[] </list></channel>
                <intension> eq(x[0],1) </intension>
                <intension> eq(x[1],2) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        val v = sat(xml)
        val x = intArrayOf(v[0], v[1])
        val y = intArrayOf(v[2], v[3], v[4])
        // x[i]=j  ⟺  y[j]=i, for all i in 0..1, j in 0..2.
        for (i in 0..1) for (j in 0..2) assertEquals(x[i] == j, y[j] == i, "biconditional i=$i j=$j")
    }

    @Test
    fun `intension with a variable product posts a Product and solves`() {
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables><var id="x"> 1..6 </var><var id="y"> 1..6 </var></variables>
              <constraints>
                <intension> eq(mul(x,y),12) </intension>
                <intension> eq(x,3) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        val v = sat(xml)
        assertEquals(3, v[0])
        assertEquals(4, v[1])
    }

    @Test
    fun `element over a constant matrix selects the two-indexed cell`() {
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables>
                <var id="i"> 0..2 </var><var id="j"> 0..2 </var><var id="v"> 0..99 </var>
              </variables>
              <constraints>
                <element>
                  <matrix> (10,11,12)(20,21,22)(30,31,32) </matrix>
                  <index> i j </index><value> v </value>
                </element>
                <intension> eq(i,2) </intension>
                <intension> eq(j,1) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        assertTrue(Xcsp3.parse(xml).problem.factors.any { it is Table })
        val vals = sat(xml)
        assertEquals(31, vals[2]) // M[2][1]
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
    fun `lex solve terminates under every search ordering`() {
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
        val problem = Xcsp3.parse(xml).problem
        for (seed in 0L until 200L) {
            val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = seed))
            assertTrue(r is SolveResult.Sat, "seed=$seed expected SAT, got $r")
            val v = r.assignment.ints
            assertTrue(v[0] == v[2] && v[1] < v[3], "seed=$seed bad model a=${v[0]},${v[1]} b=${v[2]},${v[3]}")
        }
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
        assertEquals(2.0, r.objective)
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
    fun `expands a group template with mid-index range args`() {
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables><array id="x" size="[2][2]"> 0..1 </array></variables>
              <constraints>
                <group>
                  <intension> ne(%0,%1) </intension>
                  <args> x[0..1][0] </args>
                  <args> x[0..1][1] </args>
                </group>
              </constraints>
            </instance>
        """.trimIndent()
        val p = Xcsp3.parse(xml).problem
        assertEquals(2, p.factors.filterIsInstance<Linear>().size)
        sat(xml)
    }

    @Test
    fun `parses a unary extension table written as bare values`() {
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables><var id="a"> 0..9 </var></variables>
              <constraints><extension><list> a </list><supports> 3 5 7 </supports></extension></constraints>
            </instance>
        """.trimIndent()
        val p = Xcsp3.parse(xml).problem
        assertTrue(p.factors.any { it is Table })
        assertTrue(sat(xml)[0] in setOf(3, 5, 7))
    }

    @Test
    fun `a conflicts table forbidding every tuple makes the instance unsatisfiable`() {
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables><var id="a"> 0..1 </var><var id="b"> 0..1 </var></variables>
              <constraints>
                <extension><list> a b </list><conflicts> (0,0)(0,1)(1,0)(1,1) </conflicts></extension>
              </constraints>
            </instance>
        """.trimIndent()
        val r = BacktrackSolver(Xcsp3.parse(xml).problem).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
    }

    @Test
    fun `an empty supports table makes the instance unsatisfiable`() {
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables><var id="a"> 0..3 </var><var id="b"> 0..3 </var></variables>
              <constraints><extension><list> a b </list><supports></supports></extension></constraints>
            </instance>
        """.trimIndent()
        val r = BacktrackSolver(Xcsp3.parse(xml).problem).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
    }

    @Test
    fun `sums reified relation terms against a variable condition`() {
        // z = number of nonzero cells among a, b, c; forced to 2.
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables>
                <var id="a"> 0..1 </var><var id="b"> 0..1 </var><var id="c"> 0..1 </var><var id="z"> 2..2 </var>
              </variables>
              <constraints>
                <sum><list> ne(a,0) ne(b,0) ne(c,0) </list><condition> (eq,z) </condition></sum>
              </constraints>
            </instance>
        """.trimIndent()
        val ints = sat(xml)
        assertEquals(2, ints[0] + ints[1] + ints[2])
    }

    @Test
    fun `expands run-length encoded constant lists in cumulative`() {
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables><array id="s" size="[4]"> 0..10 </array></variables>
              <constraints>
                <cumulative>
                  <origins> s[] </origins>
                  <lengths> 2x4 </lengths>
                  <heights> 1 1 1 1 </heights>
                  <condition> (le,2) </condition>
                </cumulative>
              </constraints>
            </instance>
        """.trimIndent()
        val p = Xcsp3.parse(xml).problem
        val cumulative = p.factors.filterIsInstance<Cumulative>().single()
        assertEquals(listOf(2, 2, 2, 2), cumulative.durations.toList())
        sat(xml)
    }
}
