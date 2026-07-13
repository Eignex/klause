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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Xcsp3Test {

    private fun sat(xml: String): IntArray {
        val r = BacktrackSolver(Xcsp3.parse(xml).problem).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return IntArray(r.assignment.ints.size) { r.assignment.ints[it].toInt() }
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
    fun `matrix allDifferent constrains each row and column not the whole matrix`() {
        // A 3x3 matrix over 1..3 is SAT as a Latin square (distinct on each row and each column). It
        // would be UNSAT if flattened to one all-different over all nine cells (nine values from 1..3).
        val xml = """
            <instance format="XCSP3" type="CSP">
              <variables><array id="x" size="[3][3]"> 1..3 </array></variables>
              <constraints>
                <allDifferent><matrix> x[][] </matrix></allDifferent>
              </constraints>
            </instance>
        """.trimIndent()
        val names = Xcsp3.parse(xml).intVarNames
        val g = sat(xml)
        fun cell(r: Int, c: Int) = g[names.getValue("x[$r][$c]")]
        for (i in 0..2) {
            assertEquals(3, (0..2).map { cell(i, it) }.toSet().size, "row $i must be all-different")
            assertEquals(3, (0..2).map { cell(it, i) }.toSet().size, "column $i must be all-different")
        }
    }

    @Test
    fun `matrix allDifferent over a sub-range binds the referenced cells`() {
        // The 2x2 top-left block of a 3x3 array is row/column all-different; pinning x[0][0] = x[0][1]
        // (same row, both inside the block) must conflict — verifying the `x[0..1][0..1]` axes resolve.
        val decl = """<instance type="CSP"><variables><array id="x" size="[3][3]"> 1..3 </array></variables>"""
        val cons = "<allDifferent><matrix> x[0..1][0..1] </matrix></allDifferent>" +
            "<instantiation><list> x[0][0] x[0][1] </list><values> 1 1 </values></instantiation>"
        val r = BacktrackSolver(Xcsp3.parse("$decl<constraints>$cons</constraints></instance>").problem)
            .solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "same-row cells in the sub-range block must be all-different, got $r")
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
    fun `negative conflicts table forbids the listed tuples`() {
        val decl = "<instance type=\"CSP\"><variables><var id=\"a\"> 1..2 </var><var id=\"b\"> 1..2 </var></variables>"
        val cons = "<extension><list> a b </list><conflicts> (1,1)(2,2) </conflicts></extension>"
        val v = sat("$decl<constraints>$cons</constraints></instance>")
        assertTrue(v[0] != v[1], "conflicts forbid equal pairs: a=${v[0]} b=${v[1]}")
        // Pinning a forbidden tuple (1,1) must be rejected.
        val bad = "$decl<constraints>$cons" +
            "<instantiation><list> a b </list><values> 1 1 </values></instantiation></constraints></instance>"
        assertTrue(
            BacktrackSolver(Xcsp3.parse(bad).problem).solve(BacktrackParams()) is SolveResult.Unsat,
            "the forbidden tuple (1,1) must be unsatisfiable",
        )
    }

    @Test
    fun `star in a support tuple allows any value in that column`() {
        val v = sat(
            """
            <instance type="CSP"><variables><var id="a"> 0..1 </var><var id="b"> 0..1 </var></variables>
            <constraints>
              <extension><list> a b </list><supports> (0,*)(1,1) </supports></extension>
              <intension> eq(a,1) </intension>
            </constraints></instance>
            """.trimIndent(),
        )
        assertEquals(1, v[1]) // a=1 leaves only the (1,1) tuple
    }

    @Test
    fun `star in a conflict tuple forbids the whole column`() {
        val v = sat(
            """
            <instance type="CSP"><variables><var id="a"> 0..1 </var><var id="b"> 0..1 </var></variables>
            <constraints><extension><list> a b </list><conflicts> (1,*) </conflicts></extension></constraints></instance>
            """.trimIndent(),
        )
        assertEquals(0, v[0]) // every (1,*) tuple is forbidden
    }

    @Test
    fun `a small-domain conflict table complements to a positive table for GAC`() {
        val xml = "<instance type=\"CSP\"><variables><var id=\"a\"> 0..2 </var><var id=\"b\"> 0..2 </var></variables>" +
            "<constraints><extension><list> a b </list><conflicts> (1,1) </conflicts></extension></constraints></instance>"
        assertTrue(Xcsp3.parse(xml).problem.factors.any { it is Table }, "small conflict should complement to a Table")
    }

    @Test
    fun `a conflict table whose complement exceeds the cap stays clauses`() {
        val xml = "<instance type=\"CSP\"><variables><var id=\"a\"> 0..2 </var><var id=\"b\"> 0..2 </var></variables>" +
            "<constraints><extension><list> a b </list><conflicts> (1,1) </conflicts></extension></constraints></instance>"
        // The 3x3 domain product exceeds a cap of 4, so it lowers to nogood clauses instead of a Table.
        assertTrue(Xcsp3.parse(xml, negTableCap = 4).problem.factors.none { it is Table }, "over-cap ⇒ clauses")
    }

    @Test
    fun `conflict tables enumerate exactly the complement of the forbidden set`() {
        // Soundness gate for the nogood-clause lowering of <conflicts>: klause must accept exactly the
        // assignments NOT listed as forbidden. Compared against a brute-force complement.
        val rng = Random(0xC0FFEE)
        repeat(40) { trial ->
            val arity = rng.nextInt(2, 4)
            val hi = rng.nextInt(1, 3)
            val forbidden = HashSet<List<Int>>()
            repeat(rng.nextInt(0, 6)) { forbidden.add(List(arity) { rng.nextInt(0, hi + 1) }) }
            val varsDecl = (0 until arity).joinToString("") { "<var id=\"v$it\"> 0..$hi </var>" }
            val listRef = (0 until arity).joinToString(" ") { "v$it" }
            val confBody = forbidden.joinToString("") { t -> "(" + t.joinToString(",") + ")" }
            val cons = if (confBody.isEmpty()) {
                ""
            } else {
                "<extension><list> $listRef </list><conflicts> $confBody </conflicts></extension>"
            }
            val xml = "<instance type=\"CSP\"><variables>$varsDecl</variables>" +
                "<constraints>$cons</constraints></instance>"
            val found = BacktrackSolver(Xcsp3.parse(xml).problem)
                .enumerate(BacktrackParams(randomSeed = trial.toLong()))
                .map { s -> (0 until arity).map { s.ints[it].toInt() } }.toHashSet()
            var all = listOf(emptyList<Int>())
            repeat(arity) { all = all.flatMap { p -> (0..hi).map { p + it } } }
            val allowed = all.filterNot { it in forbidden }.toHashSet()
            assertEquals(allowed, found, "trial #$trial (arity=$arity hi=$hi forbidden=$forbidden)")
        }
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
    fun `a range column in a support tuple restricts to the interval`() {
        val xml = """
            <instance type="CSP">
              <variables><var id="a"> 0..1 </var><var id="b"> 0..5 </var></variables>
              <constraints><extension><list> a b </list><supports> (1,2..3) </supports></extension></constraints>
            </instance>
        """.trimIndent()
        val names = Xcsp3.parse(xml).intVarNames
        val v = sat(xml)
        assertEquals(1, v[names.getValue("a")])
        assertTrue(v[names.getValue("b")] in 2..3, "b must lie in the support interval 2..3")
    }

    @Test
    fun `a range column over a wide domain is a short interval and does not hit the cap`() {
        // Expanding (1, 0..1000000) would exceed the 1M cap; as an interval it is a single row.
        val xml = """
            <instance type="CSP">
              <variables><var id="a"> 0..1 </var><var id="b"> 0..1000000 </var></variables>
              <constraints>
                <extension><list> a b </list><supports> (1,0..1000000) </supports></extension>
                <intension> eq(a,1) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        assertEquals(1, sat(xml)[Xcsp3.parse(xml).intVarNames.getValue("a")], "the only support pins a to 1")
    }

    @Test
    fun `an extension table exceeding the row cap is rejected cleanly instead of exhausting the heap`() {
        // Six rows against a cap of 3 must throw a clean UnsupportedXcsp3Exception, not build the arrays.
        val xml = """
            <instance type="CSP"><variables><var id="a"> 0..9 </var></variables>
            <constraints><extension><list> a </list><supports> 0 1 2 3 4 5 </supports></extension></constraints></instance>
        """.trimIndent()
        assertFailsWith<UnsupportedXcsp3Exception> { Xcsp3.parse(xml, negTableCap = 3) }
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
    fun `in set membership intension restricts the variable to the set`() {
        val v = sat(
            """
            <instance type="CSP"><variables><var id="x"> 0..9 </var></variables>
            <constraints><intension> in(x,{2,4,6}) </intension><intension> gt(x,3) </intension></constraints></instance>
            """.trimIndent(),
        )
        assertTrue(v[0] in setOf(4, 6), "x must be in {2,4,6} and > 3: x=${v[0]}")
    }

    @Test
    fun `in accepts a set-call operand`() {
        val v = sat(
            """
            <instance type="CSP"><variables><var id="x"> 0..9 </var></variables>
            <constraints><intension> in(x,set(2,4,6)) </intension><intension> gt(x,3) </intension></constraints></instance>
            """.trimIndent(),
        )
        assertTrue(v[0] in setOf(4, 6), "x must be in set(2,4,6) and > 3: x=${v[0]}")
    }

    @Test
    fun `sum condition with a set constrains the total to a member`() {
        val v = sat(
            """
            <instance type="CSP"><variables><var id="a"> 0..3 </var><var id="b"> 0..3 </var></variables>
            <constraints><sum><list> a b </list><condition> (in,{5,6}) </condition></sum></constraints></instance>
            """.trimIndent(),
        )
        assertTrue(v[0] + v[1] in setOf(5, 6), "a+b must be in {5,6}: ${v[0]}+${v[1]}")
    }

    @Test
    fun `sum with variable coefficients forms products`() {
        // c0*x0 + c1*x1 = 10 with c0=3, x0=x1=2 forces c1 = 2.
        val v = sat(
            """
            <instance type="CSP"><variables>
              <var id="c0"> 0..3 </var><var id="c1"> 0..3 </var><var id="x0"> 0..3 </var><var id="x1"> 0..3 </var>
            </variables><constraints>
              <sum><list> x0 x1 </list><coeffs> c0 c1 </coeffs><condition> (eq,10) </condition></sum>
              <intension> eq(x0,2) </intension><intension> eq(x1,2) </intension><intension> eq(c0,3) </intension>
            </constraints></instance>
            """.trimIndent(),
        )
        assertEquals(2, v[1], "c1 must be 2")
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
    fun `binPacking with loads equates each bin's total to its load variable`() {
        val v = sat(
            """
            <instance type="CSP"><variables>
              <array id="x" size="[3]"> 0..1 </array><array id="y" size="[2]"> 0..20 </array>
            </variables><constraints>
              <binPacking><list> x[] </list><sizes> 4 4 4 </sizes><loads> y[] </loads></binPacking>
              <intension> eq(x[0],0) </intension><intension> eq(x[1],0) </intension><intension> eq(x[2],1) </intension>
            </constraints></instance>
            """.trimIndent(),
        )
        assertEquals(8, v[3]) // y[0] = total size in bin 0
        assertEquals(4, v[4]) // y[1] = total size in bin 1
    }

    @Test
    fun `binPacking with per-bin limits caps each bin separately`() {
        val v = sat(
            """
            <instance type="CSP"><variables><array id="x" size="[3]"> 0..1 </array></variables>
            <constraints><binPacking><list> x[] </list><sizes> 4 4 4 </sizes><limits> 8 4 </limits></binPacking></constraints></instance>
            """.trimIndent(),
        )
        val load = IntArray(2)
        for (i in 0..2) load[v[i]] += 4
        assertTrue(load[0] <= 8 && load[1] <= 4, "bins over limit: ${load.toList()}")
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
    fun `nValues objective minimizes the distinct value count`() {
        val parsed = Xcsp3.parse(
            """
            <instance type="COP"><variables><array id="x" size="[3]"> 1..3 </array></variables>
            <constraints><sum><list> x[] </list><condition> (ge,5) </condition></sum></constraints>
            <objectives><minimize type="nValues"><list> x[] </list></minimize></objectives></instance>
            """.trimIndent(),
        )
        val r = BacktrackSolver(parsed.problem).minimize(requireNotNull(parsed.objective), BacktrackParams())
        assertTrue(r is MinimizeResult.Optimal, "expected Optimal, got $r")
        assertEquals(1.0, r.objective)
    }

    @Test
    fun `precedence forces a value to appear only after its predecessor`() {
        val v = sat(
            """
            <instance type="CSP"><variables><array id="x" size="[3]"> 0..2 </array></variables>
            <constraints>
              <precedence><list> x[] </list><values> 0 1 2 </values></precedence>
              <intension> eq(x[2],2) </intension>
            </constraints></instance>
            """.trimIndent(),
        )
        assertEquals(listOf(0, 1, 2), v.take(3))
    }

    @Test
    fun `precedence accepts the list as direct content without a list wrapper`() {
        // Symmetry-breaking shorthand `<precedence> x[] </precedence>`: no <list>, no <values>
        // (values default to the sorted domain union).
        val v = sat(
            """
            <instance type="CSP"><variables><array id="x" size="[3]"> 0..2 </array></variables>
            <constraints>
              <precedence class="symmetry-breaking"> x[] </precedence>
              <intension> eq(x[2],2) </intension>
            </constraints></instance>
            """.trimIndent(),
        )
        assertEquals(listOf(0, 1, 2), v.take(3))
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
    fun `noOverlap in two dimensions separates boxes`() {
        // box0 at (0,0) with a 2x2 footprint; box1 must clear it in x or y.
        val v = sat(
            """
            <instance type="CSP"><variables>
              <var id="x0"> 0..3 </var><var id="y0"> 0..3 </var><var id="x1"> 0..3 </var><var id="y1"> 0..3 </var>
            </variables><constraints>
              <noOverlap><origins> (x0,y0)(x1,y1) </origins><lengths> (2,2)(2,2) </lengths></noOverlap>
              <intension> eq(x0,0) </intension><intension> eq(y0,0) </intension>
            </constraints></instance>
            """.trimIndent(),
        )
        assertTrue(v[2] >= 2 || v[3] >= 2, "box1 must clear box0's 2x2 footprint: (${v[2]},${v[3]})")
    }

    @Test
    fun `noOverlap in two dimensions rejects overlapping boxes`() {
        // Two 2x2 boxes pinned to (0,0) and (1,1) share cell (1,1) — infeasible.
        val r = BacktrackSolver(
            Xcsp3.parse(
                """
                <instance type="CSP"><variables>
                  <var id="x0"> 0..0 </var><var id="y0"> 0..0 </var><var id="x1"> 1..1 </var><var id="y1"> 1..1 </var>
                </variables><constraints>
                  <noOverlap><origins> (x0,y0)(x1,y1) </origins><lengths> (2,2)(2,2) </lengths></noOverlap>
                </constraints></instance>
                """.trimIndent(),
            ).problem,
        ).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "overlapping boxes must be rejected: $r")
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
    fun `element with a condition constrains the selected value`() {
        // t = [1,3,1]; only index 1 selects a value > 2, so the condition forces i = 1.
        val v = sat(
            """
            <instance type="CSP"><variables><array id="t" size="[3]"> 1..3 </array><var id="i"> 0..2 </var></variables>
            <constraints>
              <instantiation><list> t[] </list><values> 1 3 1 </values></instantiation>
              <element><list> t[] </list><index> i </index><condition> (gt,2) </condition></element>
            </constraints></instance>
            """.trimIndent(),
        )
        assertEquals(1, v[3], "only index 1 selects a value > 2")
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
    fun `circuit forms a subcircuit over the participating nodes`() {
        val xml = """
            <instance type="CSP">
              <variables><array id="s" size="[3]"> 0..2 </array></variables>
              <constraints><circuit><list> s[] </list></circuit></constraints>
            </instance>
        """.trimIndent()
        // XCSP3 circuit is subcircuit semantics: self-loops (s[i]=i) are excluded nodes.
        assertTrue(Xcsp3.parse(xml).problem.factors.any { it is Circuit && it.subcircuit })
        val v = sat(xml)
        val included = (0..2).filter { v[it] != it }
        assertTrue(included.isNotEmpty(), "circuit must have size > 1: $v")
        val seen = HashSet<Int>()
        var cur = included.first()
        while (cur !in seen) {
            seen.add(cur)
            cur = v[cur]
        }
        assertEquals(included.toSet(), seen, "participating nodes form exactly one cycle: $v")
    }

    @Test
    fun `circuit admits a proper subcircuit with self-loops`() {
        // 0 and 1 form a 2-cycle; node 2 self-loops (excluded). A Hamiltonian encoding would
        // wrongly reject this — XCSP3 circuit (Semantics 46) accepts it.
        val v = sat(
            """
            <instance type="CSP">
              <variables><var id="a"> 1..1 </var><var id="b"> 0..0 </var><var id="c"> 2..2 </var></variables>
              <constraints><circuit><list> a b c </list></circuit></constraints>
            </instance>
            """.trimIndent(),
        )
        assertEquals(listOf(1, 0, 2), v.take(3))
    }

    @Test
    fun `channel with unequal lengths is a one-way implication`() {
        // |X| < |Y| (Semantics 32): x_i=j ⇒ y_j=i, but y entries beyond X's range stay free.
        // Pinning y[2]=0 is satisfiable; the previous biconditional wrongly forced x[0]=2 → UNSAT.
        val v = sat(
            """
            <instance type="CSP">
              <variables><array id="x" size="[2]"> 0..2 </array><array id="y" size="[3]"> 0..2 </array></variables>
              <constraints>
                <channel><list> x[] </list><list> y[] </list></channel>
                <intension> eq(x[0],0) </intension><intension> eq(x[1],1) </intension>
                <intension> eq(y[2],0) </intension>
              </constraints>
            </instance>
            """.trimIndent(),
        )
        assertEquals(0, v[2], "x[0]=0 ⇒ y[0]=0")
        assertEquals(1, v[3], "x[1]=1 ⇒ y[1]=1")
        assertEquals(0, v[4], "y[2] is unconstrained and may be 0")
    }

    @Test
    fun `cardinality closed forbids values outside the cover`() {
        // closed=true forces x ∈ {0,1}; x fixed to 2 ⇒ UNSAT. The open (buggy) form accepted it.
        val r = BacktrackSolver(
            Xcsp3.parse(
                """
                <instance type="CSP"><variables><var id="x"> 2..2 </var></variables>
                <constraints><cardinality><list> x </list>
                  <values closed="true"> 0 1 </values><occurs> 0..1 0..1 </occurs></cardinality></constraints></instance>
                """.trimIndent(),
            ).problem,
        ).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "closed cover forbids x=2: $r")
    }

    @Test
    fun `nValues excludes the except values from the count`() {
        // z=[0,0,3,5]; distinct excluding {0} = {3,5} = 2. Counting 0 (the bug) gives 3 ≠ 2 → UNSAT.
        val v = sat(
            """
            <instance type="CSP"><variables><array id="z" size="[4]"> 0..5 </array></variables>
            <constraints>
              <instantiation><list> z[] </list><values> 0 0 3 5 </values></instantiation>
              <nValues><list> z[] </list><except> 0 </except><condition> (eq,2) </condition></nValues>
            </constraints></instance>
            """.trimIndent(),
        )
        assertEquals(listOf(0, 0, 3, 5), v.take(4))
    }

    @Test
    fun `cumulative ends binds each task end to start plus duration`() {
        val v = sat(
            """
            <instance type="CSP">
              <variables><array id="o" size="[2]"> 0..5 </array><array id="e" size="[2]"> 0..10 </array></variables>
              <constraints>
                <cumulative><origins> o[] </origins><lengths> 2 3 </lengths><heights> 1 1 </heights>
                  <ends> e[] </ends><condition> (le,1) </condition></cumulative>
                <intension> eq(o[0],0) </intension>
              </constraints></instance>
            """.trimIndent(),
        )
        assertEquals(2, v[2], "e[0] = o[0] + 2")
        assertEquals(v[1] + 3, v[3], "e[1] = o[1] + 3")
    }

    @Test
    fun `element matrix honors separate row and column start indices`() {
        // startRowIndex/startColIndex = 1: index (2,2) selects matrix cell [1][1] = 21.
        val v = sat(
            """
            <instance type="CSP"><variables><var id="i"> 1..2 </var><var id="j"> 1..2 </var><var id="v"> 0..99 </var></variables>
            <constraints>
              <element startRowIndex="1" startColIndex="1">
                <matrix> (10,11)(20,21) </matrix><index> i j </index><value> v </value></element>
              <intension> eq(i,2) </intension><intension> eq(j,2) </intension>
            </constraints></instance>
            """.trimIndent(),
        )
        assertEquals(21, v[2])
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
    fun `lex over a matrix orders both rows and columns`() {
        val decl = "<instance type=\"CSP\"><variables><array id=\"x\" size=\"[2][2]\"> 0..2 </array></variables>"
        val lexCons = "<lex><matrix> x[][] </matrix><operator> le </operator></lex>"
        // lex2: one row-pair chain + one column-pair chain over the 2x2 matrix.
        val p = Xcsp3.parse("$decl<constraints>$lexCons</constraints></instance>").problem
        assertEquals(2, p.factors.count { it is LexLess }, "one row-pair and one column-pair")
        // Row 0 = [1,0] lexicographically exceeds row 1 = [0,0] ⇒ rejected.
        val bad = "$decl<constraints>$lexCons" +
            "<instantiation><list> x[][] </list><values> 1 0 0 0 </values></instantiation></constraints></instance>"
        assertTrue(
            BacktrackSolver(Xcsp3.parse(bad).problem).solve(BacktrackParams()) is SolveResult.Unsat,
            "a row-descending matrix must be rejected",
        )
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
    fun `cumulative with variable heights bounds the overlapping demand`() {
        // Both tasks run over [0,2); with capacity 3 and h0=2, the variable height h1 must be 1.
        val v = sat(
            """
            <instance type="CSP"><variables>
              <array id="o" size="[2]"> 0..0 </array><var id="h0"> 1..3 </var><var id="h1"> 1..3 </var>
            </variables><constraints>
              <cumulative><origins> o[] </origins><lengths> 2 2 </lengths><heights> h0 h1 </heights>
                <condition> (le,3) </condition></cumulative>
              <intension> eq(h0,2) </intension>
            </constraints></instance>
            """.trimIndent(),
        )
        assertEquals(1, v[3], "h1 must be 1 (2 + h1 <= 3)")
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
        assertEquals(listOf(2L, 2L, 2L, 2L), cumulative.durations.toList())
        sat(xml)
    }

    @Test
    fun `intension notin forbids the listed values`() {
        val xml = """
            <instance type="CSP">
              <variables><var id="x"> 0..3 </var></variables>
              <constraints>
                <intension> notin(x,{1,2}) </intension>
                <intension> ge(x,1) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        // x >= 1 and x notin {1,2} forces x = 3.
        assertEquals(3, sat(xml)[0])
    }

    @Test
    fun `div and mod are truncated toward zero for negative operands`() {
        fun eval(fn: String, x: Int, k: Int): Int {
            val xml = """
                <instance type="CSP">
                  <variables><var id="x"> $x..$x </var><var id="res"> -1000..1000 </var></variables>
                  <constraints><intension> eq(res,$fn(x,$k)) </intension></constraints>
                </instance>
            """.trimIndent()
            return sat(xml)[Xcsp3.parse(xml).intVarNames.getValue("res")]
        }
        // Reference XCSP3 semantics: Java `/` and `%` — quotient truncates toward zero, the
        // remainder takes the dividend's sign.
        assertEquals(-2, eval("div", -7, 3))
        assertEquals(-1, eval("mod", -7, 3))
        assertEquals(-3, eval("div", 7, -2))
        assertEquals(1, eval("mod", 7, -2))
        assertEquals(3, eval("div", -7, -2))
        assertEquals(-1, eval("mod", -7, -2))
        assertEquals(2, eval("div", 7, 3))
        assertEquals(1, eval("mod", 7, 3))
    }

    @Test
    fun `div and mod by a positive variable divisor with non-negative dividend`() {
        val xml = """
            <instance type="CSP">
              <variables>
                <var id="a"> 6..6 </var><var id="b"> 4..4 </var>
                <var id="q"> -9..9 </var><var id="r"> -9..9 </var>
              </variables>
              <constraints>
                <intension> eq(q,div(a,b)) </intension>
                <intension> eq(r,mod(a,b)) </intension>
              </constraints>
            </instance>
        """.trimIndent()
        val names = Xcsp3.parse(xml).intVarNames
        val v = sat(xml)
        // 6 div 4 = 1, 6 mod 4 = 2.
        assertEquals(1, v[names.getValue("q")])
        assertEquals(2, v[names.getValue("r")])
    }

    @Test
    fun `allDifferent with except lets the exempt value repeat but not others`() {
        val decl = "<instance type=\"CSP\"><variables><array id=\"x\" size=\"[3]\"> 0..2 </array></variables>"
        val cons = "<allDifferent><list> x[] </list><except> 0 </except></allDifferent>"
        fun solve(vals: String) = BacktrackSolver(
            Xcsp3.parse(
                "$decl<constraints>$cons<instantiation><list> x[] </list>" +
                    "<values> $vals </values></instantiation></constraints></instance>",
            ).problem,
        ).solve(BacktrackParams())
        assertTrue(solve("0 0 1") is SolveResult.Sat, "two exempt 0s may repeat")
        assertTrue(solve("1 1 2") is SolveResult.Unsat, "non-exempt 1s may not repeat")
    }

    @Test
    fun `ordered with lengths enforces the gap between consecutive entries`() {
        val decl = "<instance type=\"CSP\"><variables><array id=\"s\" size=\"[3]\"> 0..20 </array></variables>"
        val cons = "<ordered><list> s[] </list><lengths> 3 5 </lengths><operator> le </operator></ordered>"
        fun solve(vals: String) = BacktrackSolver(
            Xcsp3.parse(
                "$decl<constraints>$cons<instantiation><list> s[] </list>" +
                    "<values> $vals </values></instantiation></constraints></instance>",
            ).problem,
        ).solve(BacktrackParams())
        assertTrue(solve("0 3 8") is SolveResult.Sat, "gaps 3 and 5 satisfied")
        assertTrue(solve("0 3 7") is SolveResult.Unsat, "s[1] + 5 <= s[2] violated at 7")
    }

    @Test
    fun `array domain aliasing via as reuses the referenced domain`() {
        val decl = "<instance type=\"CSP\"><variables>" +
            "<array id=\"d\" size=\"[2]\"> 5..9 </array><array id=\"e\" size=\"[2]\" as=\"d\"/></variables>"
        fun solve(vals: String) = BacktrackSolver(
            Xcsp3.parse(
                "$decl<constraints><instantiation><list> e[] </list>" +
                    "<values> $vals </values></instantiation></constraints></instance>",
            ).problem,
        ).solve(BacktrackParams())
        assertTrue(solve("5 9") is SolveResult.Sat, "e reuses d's 5..9 domain")
        assertTrue(solve("5 10") is SolveResult.Unsat, "a value outside the aliased domain is rejected")
    }

    @Test
    fun `element over a variable matrix selects the indexed cell`() {
        val xml = """
            <instance type="CSP">
              <variables>
                <array id="m" size="[2][2]"> 0..9 </array>
                <var id="i"> 0..1 </var><var id="j"> 0..1 </var><var id="v"> 0..9 </var>
              </variables>
              <constraints>
                <element><matrix> m[][] </matrix><index> i j </index><value> v </value></element>
                <instantiation><list> m[][] i j </list><values> 1 2 3 4 1 0 </values></instantiation>
              </constraints>
            </instance>
        """.trimIndent()
        // m = [[1,2],[3,4]], i=1, j=0 -> v = m[1][0] = 3.
        assertEquals(3, sat(xml)[Xcsp3.parse(xml).intVarNames.getValue("v")])
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
}
