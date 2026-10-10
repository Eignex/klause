package com.eignex.klause.formats.xcsp3

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A source that names a global gets that global posted rather than a decomposition of it. Each case
 * also enumerates the model, because posting the wrong global would still parse: the solution set is
 * what says the constraint survived the change.
 */
class Xcsp3GlobalPostingTest {

    private fun parse(constraints: String, vars: String): Xcsp3Problem = Xcsp3.parse(
        "<instance><variables>$vars</variables><constraints>$constraints</constraints></instance>",
    )

    private fun factorsOf(parsed: Xcsp3Problem): List<Factor> = parsed.problem.factors.toList()

    /** Every solution's values for the first [n] integer variables, as sorted tuples. */
    private fun solutions(parsed: Xcsp3Problem, n: Int): Set<List<Long>> = BruteForceSolver(parsed.problem.bake())
        .enumerate(BruteForceParams(randomSeed = 0L))
        .map { s -> (0 until n).map { s.ints[it] } }
        .toSet()

    private val threeVars = """<var id="x">1..3</var><var id="y">1..3</var><var id="z">1..3</var>"""

    @Test
    fun `an ascending ordered chain posts one Increasing over the sequence`() {
        val parsed = parse("<ordered><list>x y z</list><operator>le</operator></ordered>", threeVars)

        val chain = factorsOf(parsed).single() as Increasing
        assertContentEquals(intArrayOf(0, 1, 2), chain.xs)
        assertEquals(false, chain.strict)
        assertTrue(solutions(parsed, 3).all { it[0] <= it[1] && it[1] <= it[2] })
        assertEquals(10, solutions(parsed, 3).size, "non-decreasing triples over 1..3")
    }

    @Test
    fun `an ordered chain with lengths keeps its rows`() {
        val parsed = parse(
            "<ordered><list>x y z</list><lengths>1 1</lengths><operator>le</operator></ordered>",
            threeVars,
        )

        assertTrue(factorsOf(parsed).none { it is Increasing }, "a gap chain is not plain increasing")
        assertEquals(setOf(listOf(1L, 2L, 3L)), solutions(parsed, 3))
    }

    @Test
    fun `precedence posts ValuePrecede per adjacent value pair`() {
        val parsed = parse(
            "<precedence><list>x y z</list><values>1 2</values></precedence>",
            threeVars,
        )

        val posted = factorsOf(parsed).filterIsInstance<ValuePrecede>()
        assertEquals(1, posted.size)
        assertEquals(1L to 2L, posted[0].s to posted[0].t)
        // Value 2 may not appear before the first 1.
        for (s in solutions(parsed, 3)) {
            val firstOne = s.indexOf(1L)
            val firstTwo = s.indexOf(2L)
            if (firstTwo >= 0) assertTrue(firstOne in 0 until firstTwo, "2 precedes 1 in $s")
        }
    }

    @Test
    fun `two-dimensional noOverlap posts one Diffn`() {
        val parsed = parse(
            "<noOverlap><origins>(x,y)(z,w)</origins><lengths>(2,2)(2,2)</lengths></noOverlap>",
            """<var id="x">0..2</var><var id="y">0..2</var><var id="z">0..2</var><var id="w">0..2</var>""",
        )

        val boxes = factorsOf(parsed).filterIsInstance<Diffn>()
        assertEquals(1, boxes.size)
        assertEquals(2, boxes[0].n)
        // Unit boxes of side 2 in a 0..2 grid must separate on some axis by at least 2.
        for (s in solutions(parsed, 4)) {
            val separated = (s[0] - s[2] >= 2 || s[2] - s[0] >= 2 || s[1] - s[3] >= 2 || s[3] - s[1] >= 2)
            assertTrue(separated, "overlapping boxes admitted: $s")
        }
    }

    @Test
    fun `one-dimensional noOverlap still posts Cumulative`() {
        val parsed = parse(
            "<noOverlap><origins>x y</origins><lengths>2 2</lengths></noOverlap>",
            """<var id="x">0..3</var><var id="y">0..3</var>""",
        )

        assertEquals(1, factorsOf(parsed).filterIsInstance<Cumulative>().size)
    }

    @Test
    fun `variable matrix selection preserves source values with axis offsets`() {
        for ((rowOffset, colOffset) in listOf(0L to 0L, -3L to 5L, Int.MAX_VALUE.toLong() to 0L)) {
            val parsed = parse(
                """
                <element startRowIndex="$rowOffset" startColIndex="$colOffset">
                  <matrix>m[][]</matrix><index>i j</index><value>v</value>
                </element>
                """.trimIndent(),
                """
                <array id="m" size="[2][2]">
                  <domain for="m[0][0]">1</domain><domain for="m[0][1]">2</domain>
                  <domain for="m[1][0]">3</domain><domain for="m[1][1]">4</domain>
                </array>
                <var id="i">$rowOffset..${rowOffset + 1L}</var>
                <var id="j">$colOffset..${colOffset + 1L}</var><var id="v">1..4</var>
                """.trimIndent(),
            )

            assertEquals(1, factorsOf(parsed).filterIsInstance<Element>().size)
            val expected = (0..1).flatMap { row ->
                (0..1).map { col ->
                    listOf(1L, 2L, 3L, 4L, rowOffset + row, colOffset + col, (2 * row + col + 1).toLong())
                }
            }.toSet()
            assertEquals(expected, solutions(parsed, 7))
        }
    }

    @Test
    fun `variable matrix selection preserves repeated cells and result aliases`() {
        for (value in listOf("v", "a")) {
            val parsed = parse(
                "<element><matrix>(a,b)(b,a)</matrix><index>i j</index><value>$value</value></element>",
                """
                <var id="a">0..1</var><var id="b">0..1</var>
                <var id="i">0..1</var><var id="j">0..1</var>
                ${if (value == "v") "<var id=\"v\">0..1</var>" else ""}
                """.trimIndent(),
            )
            val expected = mutableSetOf<List<Long>>()
            for (a in 0L..1L) for (b in 0L..1L) for (row in 0L..1L) for (col in 0L..1L) {
                val selected = if (row == col) a else b
                if (value == "v") expected += listOf(a, b, row, col, selected)
                else if (a == selected) expected += listOf(a, b, row, col)
            }

            assertEquals(expected, solutions(parsed, if (value == "v") 5 else 4))
        }
    }

    @Test
    fun `coordinate moves select matrix cells without an index repair`() {
        val parsed = parse(
            "<element><matrix>(a,b)(b,a)</matrix><index>i j</index><value>v</value></element>",
            """
            <var id="a">0</var><var id="b">1</var>
            <var id="i">0..1</var><var id="j">0..1</var><var id="v">1</var>
            """.trimIndent(),
        )
        val problem = parsed.problem
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars))
        val state = LocalSearchState(problem.bake(), Random(5))
        val row = parsed.intVarNames.getValue("i")
        val col = parsed.intVarNames.getValue("j")
        state.assignment.setInt(row, 0)
        state.assignment.setInt(col, 1)
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        state.recompute()

        state.apply(Move.IntSet(row, 1))
        state.apply(Move.IntSet(col, 0))

        assertEquals(0L, state.cost)
    }

    @Test
    fun `out of range matrix axes cannot select an aliased flat position`() {
        for ((row, col) in listOf(-1 to 2, 1 to -1)) {
            val parsed = parse(
                "<element><matrix>(a,b)(b,a)</matrix><index>i j</index><value>a</value></element>",
                """
                <var id="a">0..1</var><var id="b">0..1</var>
                <var id="i">$row</var><var id="j">$col</var>
                """.trimIndent(),
            )

            assertTrue(solutions(parsed, 4).isEmpty())
        }
    }

    @Test
    fun `affine matrix coordinates preserve source solutions`() {
        for (equality in listOf("eq(add(day,1),row)", "eq(row,add(day,1))")) {
            val parsed = parse(
                """
                <intension>$equality</intension>
                <element startRowIndex="1"><matrix>(a,b)(b,a)</matrix><index>row col</index><value>v</value></element>
                """.trimIndent(),
                """
                <var id="a">0</var><var id="b">9</var><var id="day">0..1</var>
                <var id="row">1..2</var><var id="col">0..1</var><var id="v">9</var>
                """.trimIndent(),
            )

            assertEquals(
                setOf(listOf(0L, 9L, 0L, 1L, 1L, 9L), listOf(0L, 9L, 1L, 2L, 0L, 9L)),
                solutions(parsed, 6),
            )
        }
    }

    @Test
    fun `an element repair moves through declared affine coordinate chains`() {
        val parsed = parse(
            """
            <intension>eq(add(day,1),shift)</intension><intension>eq(row,add(shift,1))</intension>
            <element startRowIndex="2"><matrix>(a,b)(b,a)</matrix><index>row col</index><value>v</value></element>
            """.trimIndent(),
            """
            <var id="a">0</var><var id="b">9</var><var id="day">0..1</var><var id="shift">1..2</var>
            <var id="row">2..3</var><var id="col">0..1</var><var id="v">9</var>
            """.trimIndent(),
        )
        val problem = parsed.problem
        val names = parsed.intVarNames
        val day = names.getValue("day")
        val col = names.getValue("col")
        val state = LocalSearchState(problem.bake(), Random(5), Assumptions(ints = mapOf(col to 0L)))
        state.assignment.setInt(names.getValue("b"), 9)
        state.assignment.setInt(day, 0)
        state.assignment.setInt(names.getValue("shift"), 1)
        state.assignment.setInt(names.getValue("row"), 2)
        state.assignment.setInt(col, 0)
        state.assignment.setInt(names.getValue("v"), 9)
        state.invariants = assertNotNull(
            DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars),
        ).network(problem.numIntVars, problem.numBoolVars)
        state.recompute()
        val element = problem.factors.indexOfFirst { it is Element }

        state.factors[element].proposeRepairMoves(state, element, state.moveSink)
        state.apply(state.moveSink.list.single())

        assertEquals(1L, state.assignment.intValue(day))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `an element repair moves through a materialized affine index`() {
        val parsed = parse(
            "<element startIndex=\"1\"><list>0 9</list><index>add(day,1)</index><value>v</value></element>",
            "<var id=\"day\">0..1</var><var id=\"v\">9</var>",
        )
        val problem = parsed.problem
        val day = parsed.intVarNames.getValue("day")
        val elementId = problem.factors.indexOfFirst { it is Element }
        val element = problem.factors[elementId] as Element
        val state = LocalSearchState(problem.bake(), Random(5))
        state.assignment.setInt(day, 0)
        state.assignment.setInt(element.idx, 1)
        state.assignment.setInt(element.result, 9)
        state.invariants = assertNotNull(
            DefinitionalSweep.infer(problem.factors, problem.numIntVars, parsed.definedVars),
        ).network(problem.numIntVars, problem.numBoolVars)
        state.recompute()

        state.factors[elementId].proposeRepairMoves(state, elementId, state.moveSink)
        state.apply(state.moveSink.list.single())

        assertEquals(1L, state.assignment.intValue(day))
        assertEquals(0L, state.cost)
    }
}
