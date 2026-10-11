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
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.BinaryColumnSubstitution
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
    fun `matrix results follow coordinate and selected cell moves`() {
        val parsed = parse(
            "<element><matrix>(a,b)(b,a)</matrix><index>i j</index><value>v</value></element>",
            """
            <var id="a">0..1</var><var id="b">0..1</var>
            <var id="i">0..1</var><var id="j">0..1</var><var id="v">0..1</var>
            """.trimIndent(),
        )
        val problem = parsed.problem
        val names = parsed.intVarNames
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(5))
        state.assignment.setInt(names.getValue("a"), 0)
        state.assignment.setInt(names.getValue("b"), 1)
        state.assignment.setInt(names.getValue("i"), 0)
        state.assignment.setInt(names.getValue("j"), 0)
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        sweep.sweep(state.assignment, state.rootDomains, problem.factors)
        state.recompute()

        for ((move, selected) in listOf(
            Move.IntSet(names.getValue("i"), 1) to 1L,
            Move.IntSet(names.getValue("b"), 0) to 0L,
        )) {
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(selected, state.assignment.intValue(names.getValue("v")))
            assertEquals(0L, predicted)
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `matrix result repairs select fixed cells through source coordinates`() {
        for ((rowOffset, colOffset) in listOf(0L to 0L, -2L to 5L)) {
            val parsed = parse(
                """
                <element startRowIndex="$rowOffset" startColIndex="$colOffset">
                  <matrix>(a,a)(a,b)</matrix><index>i j</index><value>v</value>
                </element>
                <intension>eq(v,1)</intension>
                """.trimIndent(),
                """
                <var id="a">0</var><var id="b">1</var>
                <var id="i">$rowOffset..${rowOffset + 1}</var>
                <var id="j">$colOffset..${colOffset + 1}</var><var id="v">0..1</var>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val names = parsed.intVarNames
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(5))
            state.assignment.setInt(names.getValue("a"), 0)
            state.assignment.setInt(names.getValue("b"), 1)
            state.assignment.setInt(names.getValue("i"), rowOffset)
            state.assignment.setInt(names.getValue("j"), colOffset)
            val sweep = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            sweep.sweep(state.assignment, state.rootDomains, problem.factors)
            state.recompute()
            state.moveSink.addChannelingIntSet(state, names.getValue("v"), 1)
            val move = state.moveSink.list.single()
            val before = state.cost
            val predicted = state.netDelta(move)

            state.apply(move)

            assertEquals(rowOffset + 1, state.assignment.intValue(names.getValue("i")))
            assertEquals(colOffset + 1, state.assignment.intValue(names.getValue("j")))
            assertEquals(1L, state.assignment.intValue(names.getValue("v")))
            assertEquals(before + predicted, state.cost)
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `matrix result repairs change an admissible selected cell`() {
        val parsed = parse(
            """
            <element><matrix>(a,b)(b,b)</matrix><index>i j</index><value>v</value></element>
            <intension>eq(v,1)</intension>
            """.trimIndent(),
            """
            <var id="a">0..1</var><var id="b">0</var>
            <var id="i">0</var><var id="j">0</var><var id="v">0..1</var>
            """.trimIndent(),
        )
        val problem = parsed.problem
        val a = parsed.intVarNames.getValue("a")
        val v = parsed.intVarNames.getValue("v")
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(5))
        state.assignment.setInt(a, 0)
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        sweep.sweep(state.assignment, state.rootDomains, problem.factors)
        state.recompute()
        state.moveSink.addChannelingIntSet(state, v, 1)
        val move = state.moveSink.list.single()
        val before = state.cost
        val predicted = state.netDelta(move)

        state.apply(move)

        assertEquals(1L, state.assignment.intValue(a))
        assertEquals(1L, state.assignment.intValue(v))
        assertEquals(before + predicted, state.cost)
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `matrix result repairs protect selected cells`() {
        for (protection in listOf("pin", "owner")) {
            val parsed = parse(
                "<element><matrix>(a,b)(b,b)</matrix><index>i j</index><value>v</value></element>",
                """
                <var id="a">0..1</var><var id="b">0</var>
                <var id="i">0</var><var id="j">0</var><var id="v">0..1</var>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val a = parsed.intVarNames.getValue("a")
            val assumptions = if (protection == "pin") Assumptions.None.withInt(a, 0) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(5), assumptions)
            state.assignment.setInt(a, 0)
            val sweep = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            if (protection == "owner") {
                state.moveSink.setOwners(IntArray(problem.numIntVars) { if (it == a) 7 else -1 })
            }
            sweep.sweep(state.assignment, state.rootDomains, problem.factors)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, parsed.intVarNames.getValue("v"), 1)

            assertTrue(state.moveSink.list.isEmpty(), protection)
        }
    }

    @Test
    fun `matrix result repairs respect source and output protections`() {
        for (protection in listOf(
            "row pin", "column pin", "output pin", "row owner", "index owner", "output owner",
        )) {
            val parsed = parse(
                "<element><matrix>(a,a)(a,b)</matrix><index>i j</index><value>v</value></element>",
                """
                <var id="a">0</var><var id="b">1</var>
                <var id="i">0..1</var><var id="j">0..1</var><var id="v">0..1</var>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val names = parsed.intVarNames
            val protected = when (protection) {
                "row pin", "row owner" -> names.getValue("i")
                "column pin" -> names.getValue("j")
                "index owner" -> problem.factors.filterIsInstance<Element>().single().idx
                else -> names.getValue("v")
            }
            val assumptions = if (protection.endsWith("pin")) {
                Assumptions.None.withInt(protected, 0)
            } else {
                Assumptions.None
            }
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(5), assumptions)
            state.assignment.setInt(names.getValue("a"), 0)
            state.assignment.setInt(names.getValue("b"), 1)
            state.assignment.setInt(names.getValue("i"), 0)
            state.assignment.setInt(names.getValue("j"), 0)
            val sweep = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            if (protection.endsWith("owner")) {
                state.moveSink.setOwners(IntArray(problem.numIntVars) { if (it == protected) 7 else -1 })
            }
            sweep.sweep(state.assignment, state.rootDomains, problem.factors)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, names.getValue("v"), 1)

            assertTrue(state.moveSink.list.isEmpty(), protection)
        }
    }

    @Test
    fun `bin packing channels follow item moves in every capacity form`() {
        for ((capacity, expectedCost) in listOf(
            "<limits>3 3</limits>" to 2L,
            "<condition>(le,3)</condition>" to 2L,
            "<loads>l0 l1</loads>" to 4L,
        )) {
            val parsed = parse(
                "<binPacking><list>x y</list><sizes>2 3</sizes>$capacity</binPacking>",
                """
                <var id="x">0..1</var><var id="y">0..1</var>
                <var id="l0">0..5</var><var id="l1">0..5</var>
                """.trimIndent(),
            )
            val problem = parsed.problem
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(5))
            val x = parsed.intVarNames.getValue("x")
            state.assignment.setInt(x, 0)
            state.assignment.setInt(parsed.intVarNames.getValue("y"), 1)
            state.assignment.setInt(parsed.intVarNames.getValue("l0"), 2)
            state.assignment.setInt(parsed.intVarNames.getValue("l1"), 3)
            val sweep = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            sweep.sweep(state.assignment, state.rootDomains, problem.factors)
            state.recompute()
            assertEquals(0L, state.cost)

            val move = Move.IntSet(x, 1)
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(expectedCost, state.cost)
            assertEquals(expectedCost, predicted)
            state.recompute()
            assertEquals(expectedCost, state.cost)
        }
    }

    @Test
    fun `an affine packing index follows a joint coordinate move`() {
        val parsed = parse(
            """
            <intension>eq(index,add(mul(2,day),theater))</intension>
            <binPacking><list>index</list><sizes>2</sizes><limits>3 3 3 3</limits></binPacking>
            """.trimIndent(),
            """<var id="day">0..1</var><var id="theater">0..1</var><var id="index">0..3</var>""",
        )
        val problem = parsed.problem
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(5))
        val day = parsed.intVarNames.getValue("day")
        val theater = parsed.intVarNames.getValue("theater")
        state.assignment.setInt(day, 0)
        state.assignment.setInt(theater, 0)
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        sweep.sweep(state.assignment, state.rootDomains, problem.factors)
        state.recompute()
        val move = Move.Compound(listOf(Move.IntSet(day, 1), Move.IntSet(theater, 1)))
        val predicted = state.netDelta(move)

        state.apply(move)

        assertEquals(3L, state.assignment.intValue(parsed.intVarNames.getValue("index")))
        assertEquals(0L, predicted)
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `Boolean packing repairs move source items after binary lowering`() {
        for ((size, capacity) in listOf(1 to 1, 2 to 3, 1 to 2)) {
            val limits = List(33) { capacity }.joinToString(" ")
            val parsed = parse(
                "<binPacking><list>x y z</list><sizes>$size $size $size</sizes><limits>$limits</limits></binPacking>",
                """<var id="x">0..32</var><var id="y">0..32</var><var id="z">0..32</var>""",
            )
            val source = parsed.problem
            val lowered = assertNotNull(
                BinaryColumnSubstitution.substitute(source.bake(), emptySet(), BakeConfig.NONE),
            ).problem
            val state = LocalSearchState(lowered, Random(5))
            for (name in listOf("x", "y", "z")) state.assignment.setInt(parsed.intVarNames.getValue(name), 16)
            val sweep = assertNotNull(DefinitionalSweep.infer(source, parsed.definedVars))
            state.invariants = sweep.network(lowered.numIntVars, lowered.numBoolVars)
            sweep.sweep(state.assignment, state.rootDomains, lowered.factors)
            state.recompute()
            for (id in state.factors.indices) state.factors[id].proposeRepairMoves(state, id, state.moveSink)
            assertTrue(state.moveSink.list.isNotEmpty())
            val move = state.moveSink.list.minBy { state.netDelta(it) }
            val before = state.cost
            val predicted = state.netDelta(move)

            state.apply(move)

            assertTrue(state.cost < before)
            assertEquals(before + predicted, state.cost)
            val committed = state.cost
            state.recompute()
            assertEquals(committed, state.cost)
        }
    }

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
        state.assignment.setInt(parsed.intVarNames.getValue("b"), 1)
        state.assignment.setInt(parsed.intVarNames.getValue("v"), 1)
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
            """
            <sum><list>add(day,1)</list><condition>(eq,index)</condition></sum>
            <element startIndex="1"><list>0 9</list><index>index</index><value>v</value></element>
            """.trimIndent(),
            "<var id=\"day\">0..1</var><var id=\"v\">9</var><var id=\"index\">1..2</var>",
        )
        val problem = parsed.problem
        val day = parsed.intVarNames.getValue("day")
        val elementId = problem.factors.indexOfFirst { it is Element }
        val element = problem.factors[elementId] as Element
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(5))
        state.assignment.setInt(day, 0)
        state.assignment.setInt(element.idx, 1)
        state.assignment.setInt(element.result, 9)
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        sweep.sweep(state.assignment, state.rootDomains, problem.factors)
        state.recompute()
        assertEquals(1L, state.assignment.intValue(element.idx))
        assertEquals(9L, state.assignment.intValue(element.result))
        assertTrue(state.factors[elementId].isViolated(state, elementId))

        state.factors[elementId].proposeRepairMoves(state, elementId, state.moveSink)
        state.apply(state.moveSink.list.single())

        assertEquals(1L, state.assignment.intValue(day))
        assertEquals(0L, state.cost)
    }
}
