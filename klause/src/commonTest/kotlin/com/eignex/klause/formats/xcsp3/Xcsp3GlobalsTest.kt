package com.eignex.klause.formats.xcsp3

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.AllDifferentTuples
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.factor.global.NValue
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
import com.eignex.klause.util.Cancellation
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Xcsp3GlobalsTest {

    private fun parse(constraints: String, vars: String): Xcsp3Problem = Xcsp3.parse(
        "<instance><variables>$vars</variables><constraints>$constraints</constraints></instance>",
    )

    private fun factorsOf(parsed: Xcsp3Problem): List<Factor> = parsed.problem.factors.toList()

    private fun solutions(parsed: Xcsp3Problem, n: Int): Set<List<Long>> = BruteForceSolver(parsed.problem.bake())
        .enumerate(BruteForceParams(randomSeed = 0L))
        .map { s -> (0 until n).map { s.ints[it] } }
        .toSet()

    private fun sweptState(
        parsed: Xcsp3Problem,
        values: Map<String, Long>,
        assumptions: Assumptions = Assumptions.None,
        owned: Int = -1,
    ): LocalSearchState {
        val problem = parsed.problem
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(5), assumptions)
        for ((name, value) in values) state.assignment.setInt(parsed.intVarNames.getValue(name), value)
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, parsed.definedVars))
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        if (owned >= 0) state.moveSink.setOwners(IntArray(problem.numIntVars) { if (it == owned) 7 else -1 })
        sweep.sweep(state.assignment, state.rootDomains, problem.factors)
        state.recompute()
        return state
    }

    private val threeVars = """<var id="x">1..3</var><var id="y">1..3</var><var id="z">1..3</var>"""

    @Test
    fun `inverse channels should preserve list index offsets`() {
        for ((lists, vars, expected) in listOf(
            Triple(
                """<list startIndex="3">x y</list>""",
                """<var id="x">4</var><var id="y">3</var>""",
                listOf(4L, 3L),
            ),
            Triple(
                """<list startIndex="3">x y</list><list startIndex="7">u v</list>""",
                """<var id="x">8</var><var id="y">7</var><var id="u">4</var><var id="v">3</var>""",
                listOf(8L, 7L, 4L, 3L),
            ),
        )) {
            val parsed = parse("<channel>$lists</channel>", vars)

            assertEquals(setOf(expected), solutions(parsed, expected.size))
        }
    }

    @Test
    fun `partial channels should preserve list index offsets`() {
        val parsed = parse(
            """<channel><list startIndex="3">x</list><list startIndex="7">u v</list></channel>""",
            """<var id="x">8</var><var id="u">99</var><var id="v">3..4</var>""",
        )

        assertEquals(setOf(listOf(8L, 99L, 3L)), solutions(parsed, 3))
    }

    @Test
    fun `strict sum conditions should retain thresholds beyond 32-bit limits`() {
        for (condition in listOf("(lt,-2147483648)", "(gt,2147483647)")) {
            val parsed = parse(
                "<sum><list>x</list><condition>$condition</condition></sum>",
                """<var id="x">0</var>""",
            )

            assertEquals(emptySet(), solutions(parsed, 1), condition)
        }
    }

    @Test
    fun `precedence should retain declared values beyond the 32-bit range`() {
        val parsed = parse(
            "<precedence>x y</precedence>",
            """<var id="x">4294967296..4294967297</var><var id="y">4294967296..4294967297</var>""",
        )

        assertEquals(setOf(listOf(4294967296L, 4294967296L), listOf(4294967296L, 4294967297L)), solutions(parsed, 2))
    }

    @Test
    fun `binary one counts should preserve source multiplicities without equality channels`() {
        val parsed = parse(
            "<count><list>x x y</list><values>1</values><condition>(eq,n)</condition></count>",
            """<var id="x">0..1</var><var id="y">0..1</var><var id="n">0..3</var>""",
        )

        assertEquals(3, parsed.problem.numIntVars)
        assertEquals(0, parsed.problem.numBoolVars)
        assertEquals(setOf(listOf(0L, 0L, 0L), listOf(0L, 1L, 1L),
            listOf(1L, 0L, 2L), listOf(1L, 1L, 3L)), solutions(parsed, 3))
    }

    @Test
    fun `counts and equality terms should share one indicator per variable value`() {
        val parsed = parse(
            """
            <count><list>x x y</list><values>2</values><condition>(eq,a)</condition></count>
            <count><list>x y</list><values>2</values><condition>(eq,b)</condition></count>
            <sum><list>eq(x,2) eq(y,2)</list><condition>(eq,b)</condition></sum>
            """.trimIndent(),
            """<var id="x">0..2</var><var id="y">0..2</var><var id="a">0..3</var><var id="b">0..2</var>""",
        )

        assertEquals(6, parsed.problem.numIntVars)
        assertEquals(2, parsed.problem.numBoolVars)
        val expected = (0L..2L).flatMap { x ->
            (0L..2L).map { y ->
                val cx = if (x == 2L) 1L else 0L
                val cy = if (y == 2L) 1L else 0L
                listOf(x, y, 2 * cx + cy, cx + cy)
            }
        }.toSet()
        assertEquals(expected, solutions(parsed, 4))
    }

    @Test
    fun `self referential and clipped count outputs should preserve source solutions`() {
        for ((condition, expected) in listOf(
            "x" to setOf(listOf(0L, 0L, 0L), listOf(0L, 0L, 1L), listOf(1L, 0L, 0L), listOf(1L, 0L, 1L)),
            "n" to setOf(listOf(0L, 0L, 0L), listOf(0L, 1L, 1L), listOf(1L, 0L, 1L)),
        )) {
            val parsed = parse(
                "<count><list>x y</list><values>1</values><condition>(eq,$condition)</condition></count>",
                """<var id="x">0..1</var><var id="y">0..1</var><var id="n">0..1</var>""",
            )

            assertEquals(expected, solutions(parsed, 3))
        }
    }

    @Test
    fun `count outputs and shared indicators should follow source moves through typed definitions`() {
        for (value in listOf(1, 2)) {
            val parsed = parse(
                "<count><list>x x y</list><values>$value</values><condition>(eq,n)</condition></count>",
                """<var id="x">0..$value</var><var id="y">0..$value</var><var id="n">0..3</var>""",
            )
            val problem = parsed.problem
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            val sweep = assertNotNull(DefinitionalSweep.fromSource(problem, parsed.definitionHints))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            state.assignment.setInt(0, 0L)
            state.assignment.setInt(1, 0L)
            sweep.sweep(state.assignment, state.rootDomains, problem.factors)
            state.recompute()

            for ((variable, choice, expected) in listOf(
                Triple(0, value.toLong(), 2L), Triple(1, value.toLong(), 3L), Triple(0, 0L, 1L),
            )) {
                val move = Move.IntSet(variable, choice)
                val predicted = state.netDelta(move)

                state.apply(move)

                assertEquals(expected, state.assignment.intValue(2))
                assertEquals(0L, predicted)
                assertEquals(0L, state.cost)
            }
        }
    }

    @Test
    fun `distinctness should retain one native relation across wide domains`() {
        for (except in listOf("", "<except>0 2</except>")) {
            val parsed = parse(
                "<allDifferent><list>x[]</list>$except</allDifferent>",
                """<array id="x" size="[4]">0..2147483648</array>""",
            )
            val relation = factorsOf(parsed).single() as AllDifferent

            assertContentEquals(intArrayOf(0, 1, 2, 3), relation.vars)
            assertContentEquals(if (except.isEmpty()) longArrayOf() else longArrayOf(0, 2), relation.exceptSet)
            assertEquals(4, parsed.problem.numIntVars)
            assertEquals(0, parsed.problem.numBoolVars)
        }
    }

    @Test
    fun `excepted distinctness should permit only exempt repetitions through reconstruction`() {
        val parsed = parse(
            "<allDifferent><list>x y</list><except>0 2 2</except></allDifferent>",
            """<var id="x">0..2</var><var id="y">0..2</var>""",
        )

        assertEquals((0L..2L).flatMap { x -> (0L..2L).map { y -> listOf(x, y) } }
            .filter { it != listOf(1L, 1L) }.toSet(), solutions(parsed, 2))
    }

    @Test
    fun `repeated distinctness positions should require an excepted value`() {
        val parsed = parse(
            "<allDifferent><list>x x</list><except>0</except></allDifferent>",
            """<var id="x">0..1</var>""",
        )

        assertEquals(setOf(listOf(0L)), solutions(parsed, 1))
    }

    @Test
    fun `singleton distinctness should impose no constraint`() {
        for (except in listOf("", "<except>0</except>")) {
            val parsed = parse("<allDifferent><list>x</list>$except</allDifferent>", """<var id="x">0..1</var>""")

            assertTrue(factorsOf(parsed).isEmpty())
        }
    }

    @Test
    fun `tuple distinctness should preserve repeated coordinates through source reconstruction`() {
        val parsed = parse(
            "<allDifferent><list>x x</list><list>y z</list><list>z x</list></allDifferent>",
            """<var id="x">0..1</var><var id="y">0..1</var><var id="z">0..1</var>""",
        )

        assertEquals(setOf(listOf(0L, 0L, 1L), listOf(0L, 1L, 1L),
            listOf(1L, 0L, 0L), listOf(1L, 1L, 0L)), solutions(parsed, 3))
    }

    @Test
    fun `tuple lowering should retain source-sized data without auxiliary coordinates`() {
        val rows = (0 until 64).joinToString("") { "<list>x[$it][]</list>" }
        val parsed = parse("<allDifferent>$rows</allDifferent>", """<array id="x" size="[64][6]">0..31</array>""")
        val relation = factorsOf(parsed).single() as AllDifferentTuples

        assertEquals(64, relation.tuples.size)
        assertEquals(384, relation.intVars.size)
        assertEquals(384, parsed.problem.numIntVars)
        assertEquals(0, parsed.problem.numBoolVars)
    }

    @Test
    fun `tuple reification attributes should decline unsupported source semantics`() {
        for (attribute in listOf("reifiedBy", "hreifiedFrom", "hreifiedTo")) {
            assertFailsWith<UnsupportedXcsp3Exception> {
                parse("<allDifferent $attribute=\"b\"><list>x x</list><list>y y</list></allDifferent>",
                    """<var id="x">0..1</var><var id="y">0..1</var><var id="b">0..1</var>""")
            }
        }
    }

    @Test
    fun `nValues except should retain one relation across wide source domains`() {
        val parsed = parse(
            "<nValues><list>x y</list><except>0 2</except><condition>(eq,k)</condition></nValues>",
            """<var id="x">-2147483648..2147483647</var><var id="y">0..2</var><var id="k">0..2</var>""",
        )
        val relation = factorsOf(parsed).filterIsInstance<NValue>().single()

        assertContentEquals(longArrayOf(0, 2), relation.exceptSet)
        assertContentEquals(intArrayOf(parsed.intVarNames.getValue("x"), parsed.intVarNames.getValue("y")), relation.xs)
        assertEquals(0, parsed.problem.numBoolVars)
    }

    @Test
    fun `nValues should decline an unrepresentable except list`() {
        assertFailsWith<UnsupportedXcsp3Exception> {
            parse(
                "<nValues><list>x</list><except>x</except><condition>(eq,0)</condition></nValues>",
                """<var id="x">0..1</var>""",
            )
        }
    }

    @Test
    fun `nValues except should count each remaining source value once`() {
        val parsed = parse(
            "<nValues><list>x y</list><except>0</except><condition>(eq,1)</condition></nValues>",
            """<var id="x">0..2</var><var id="y">0..2</var>""",
        )

        assertEquals(setOf(listOf(0L, 1L), listOf(1L, 0L), listOf(1L, 1L),
            listOf(0L, 2L), listOf(2L, 0L), listOf(2L, 2L)), solutions(parsed, 2))
    }

    @Test
    fun `matrix results should follow coordinate and selected cell moves`() {
        val parsed = parse(
            "<element><matrix>(a,b)(b,a)</matrix><index>i j</index><value>v</value></element>",
            """
            <var id="a">0..1</var><var id="b">0..1</var>
            <var id="i">0..1</var><var id="j">0..1</var><var id="v">0..1</var>
            """.trimIndent(),
        )
        val names = parsed.intVarNames
        val state = sweptState(parsed, mapOf("a" to 0L, "b" to 1L, "i" to 0L, "j" to 0L))

        for ((move, selected) in listOf(
            Move.IntSet(names.getValue("i"), 1) to 1L,
            Move.IntSet(names.getValue("b"), 0) to 0L,
        )) {
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(selected, state.assignment.intValue(names.getValue("v")))
            assertEquals(0L, predicted)
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `matrix result repairs should select fixed cells through source coordinates`() {
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
            val names = parsed.intVarNames
            val state = sweptState(parsed, mapOf("a" to 0L, "b" to 1L, "i" to rowOffset, "j" to colOffset))
            state.moveSink.addChannelingIntSet(state, names.getValue("v"), 1)

            state.apply(state.moveSink.list.single())

            assertEquals(rowOffset + 1, state.assignment.intValue(names.getValue("i")))
            assertEquals(colOffset + 1, state.assignment.intValue(names.getValue("j")))
            assertEquals(1L, state.assignment.intValue(names.getValue("v")))
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `matrix result repairs should change an admissible selected cell`() {
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
        val state = sweptState(parsed, mapOf("a" to 0L))
        state.moveSink.addChannelingIntSet(state, parsed.intVarNames.getValue("v"), 1)
        val move = state.moveSink.list.single()
        val before = state.cost
        val predicted = state.netDelta(move)

        state.apply(move)

        assertEquals(1L, state.assignment.intValue(parsed.intVarNames.getValue("a")))
        assertEquals(before + predicted, state.cost)
        assertEquals(0L, state.cost)
    }

    @Test
    fun `matrix result repairs should protect selected cells`() {
        for (protection in listOf("pin", "owner")) {
            val parsed = parse(
                "<element><matrix>(a,b)(b,b)</matrix><index>i j</index><value>v</value></element>",
                """
                <var id="a">0..1</var><var id="b">0</var>
                <var id="i">0</var><var id="j">0</var><var id="v">0..1</var>
                """.trimIndent(),
            )
            val a = parsed.intVarNames.getValue("a")
            val state = if (protection == "pin") {
                sweptState(parsed, mapOf("a" to 0L), Assumptions.None.withInt(a, 0))
            } else {
                sweptState(parsed, mapOf("a" to 0L), owned = a)
            }

            state.moveSink.addChannelingIntSet(state, parsed.intVarNames.getValue("v"), 1)

            assertTrue(state.moveSink.list.isEmpty(), protection)
        }
    }

    @Test
    fun `matrix result repairs should respect source and output protections`() {
        for (protection in listOf(
            "row pin",
            "column pin",
            "output pin",
            "row owner",
            "index owner",
            "output owner",
        )) {
            val parsed = parse(
                "<element><matrix>(a,a)(a,b)</matrix><index>i j</index><value>v</value></element>",
                """
                <var id="a">0</var><var id="b">1</var>
                <var id="i">0..1</var><var id="j">0..1</var><var id="v">0..1</var>
                """.trimIndent(),
            )
            val names = parsed.intVarNames
            val protected = when (protection) {
                "row pin", "row owner" -> names.getValue("i")
                "column pin" -> names.getValue("j")
                "index owner" -> parsed.problem.factors.filterIsInstance<Element>().single().idx
                else -> names.getValue("v")
            }
            val values = mapOf("a" to 0L, "b" to 1L, "i" to 0L, "j" to 0L)
            val state = if (protection.endsWith("pin")) {
                sweptState(parsed, values, Assumptions.None.withInt(protected, 0))
            } else {
                sweptState(parsed, values, owned = protected)
            }

            state.moveSink.addChannelingIntSet(state, names.getValue("v"), 1)

            assertTrue(state.moveSink.list.isEmpty(), protection)
        }
    }

    @Test
    fun `bin packing channels should follow item moves in every capacity form`() {
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
            val state = sweptState(parsed, mapOf("x" to 0L, "y" to 1L, "l0" to 2L, "l1" to 3L))
            val move = Move.IntSet(parsed.intVarNames.getValue("x"), 1)
            val predicted = state.netDelta(move)

            state.apply(move)

            assertEquals(expectedCost, state.cost)
            assertEquals(expectedCost, predicted)
        }
    }

    @Test
    fun `bin packing loads and extremum deviations should follow item moves without violations`() {
        val parsed = parse(
            """
            <binPacking><list>x y</list><sizes>2 3</sizes><loads>l[]</loads></binPacking>
            <maximum><list>sub(l[0],2) sub(2,l[0])</list><condition>(eq,d)</condition></maximum>
            """.trimIndent(),
            """
            <var id="x">0..1</var><var id="y">0..1</var>
            <array id="l" size="[2]">0..5</array><var id="d">0..5</var>
            """.trimIndent(),
        )
        val problem = parsed.problem
        val inferred = DefinitionalSweep.infer(problem, parsed.definedVars)
        for (sweep in listOf(
            DefinitionalSweep.withSource(problem, inferred, parsed.definitionHints, Cancellation.Never),
            DefinitionalSweep.fromSource(problem, parsed.definitionHints),
        ).map { assertNotNull(it) }) {
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(5))
            state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
            sweep.sweep(state.assignment, state.rootDomains, problem.factors)
            state.recompute()
            val names = parsed.intVarNames

            for ((item, period) in listOf("x" to 1L, "y" to 1L, "x" to 0L)) {
                val move = Move.IntSet(names.getValue(item), period)
                val predicted = state.netDelta(move)

                state.apply(move)

                val load = state.assignment.intValue(names.getValue("l[0]"))
                assertEquals(5L - load, state.assignment.intValue(names.getValue("l[1]")))
                assertEquals(abs(load - 2L), state.assignment.intValue(names.getValue("d")))
                assertEquals(0L, predicted)
                assertEquals(0L, state.cost)
            }
        }
    }

    @Test
    fun `bin packing and equality terms should share one indicator per variable value`() {
        val parsed = Xcsp3.parse(
            """
            <instance><variables><var id="x">0..1</var><var id="y">0..1</var>
            <array id="a" size="[2]">0..5</array><array id="b" size="[2]">0..4</array></variables><constraints>
            <binPacking><list>x y</list><sizes>2 3</sizes><loads>a[]</loads></binPacking>
            <binPacking><list>x</list><sizes>4</sizes><loads>b[]</loads></binPacking>
            </constraints><objectives><minimize type="sum">eq(x,1) eq(1,y)</minimize></objectives></instance>
            """.trimIndent(),
        )

        assertEquals(4, parsed.problem.numBoolVars)
        assertEquals(
            setOf(listOf(0L, 0L, 5L, 0L, 4L, 0L), listOf(0L, 1L, 2L, 3L, 4L, 0L),
                listOf(1L, 0L, 3L, 2L, 0L, 4L), listOf(1L, 1L, 0L, 5L, 0L, 4L)),
            solutions(parsed, 6),
        )
    }

    @Test
    fun `an affine packing index should follow a joint coordinate move`() {
        val parsed = parse(
            """
            <intension>eq(index,add(mul(2,day),theater))</intension>
            <binPacking><list>index</list><sizes>2</sizes><limits>3 3 3 3</limits></binPacking>
            """.trimIndent(),
            """<var id="day">0..1</var><var id="theater">0..1</var><var id="index">0..3</var>""",
        )
        val state = sweptState(parsed, mapOf("day" to 0L, "theater" to 0L))
        val move = Move.Compound(
            listOf(
                Move.IntSet(parsed.intVarNames.getValue("day"), 1),
                Move.IntSet(parsed.intVarNames.getValue("theater"), 1),
            ),
        )
        val predicted = state.netDelta(move)

        state.apply(move)

        assertEquals(3L, state.assignment.intValue(parsed.intVarNames.getValue("index")))
        assertEquals(0L, predicted)
        assertEquals(0L, state.cost)
    }

    @Test
    fun `Boolean packing repairs should move source items after binary lowering`() {
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
            val move = state.moveSink.list.minBy { state.netDelta(it) }
            val before = state.cost
            val predicted = state.netDelta(move)

            state.apply(move)

            assertTrue(state.cost < before)
            assertEquals(before + predicted, state.cost)
        }
    }

    @Test
    fun `an ascending ordered chain should post one Increasing over the sequence`() {
        val parsed = parse("<ordered><list>x y z</list><operator>le</operator></ordered>", threeVars)

        val chain = factorsOf(parsed).single() as Increasing
        assertContentEquals(intArrayOf(0, 1, 2), chain.xs)
        assertEquals(false, chain.strict)
        assertTrue(solutions(parsed, 3).all { it[0] <= it[1] && it[1] <= it[2] })
        assertEquals(10, solutions(parsed, 3).size)
    }

    @Test
    fun `an ordered chain with lengths should keep its rows`() {
        val parsed = parse(
            "<ordered><list>x y z</list><lengths>1 1</lengths><operator>le</operator></ordered>",
            threeVars,
        )

        assertTrue(factorsOf(parsed).none { it is Increasing })
        assertEquals(setOf(listOf(1L, 2L, 3L)), solutions(parsed, 3))
    }

    @Test
    fun `precedence should post ValuePrecede per adjacent value pair`() {
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
    fun `two-dimensional noOverlap should post one Diffn`() {
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
    fun `one-dimensional noOverlap should post Cumulative`() {
        val parsed = parse(
            "<noOverlap><origins>x y</origins><lengths>2 2</lengths></noOverlap>",
            """<var id="x">0..3</var><var id="y">0..3</var>""",
        )

        assertEquals(1, factorsOf(parsed).filterIsInstance<Cumulative>().size)
    }

    @Test
    fun `variable matrix selection should preserve source values with axis offsets`() {
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
    fun `variable matrix selection should preserve repeated cells and result aliases`() {
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
            for (a in 0L..1L) {
                for (b in 0L..1L) {
                    for (row in 0L..1L) {
                        for (col in 0L..1L) {
                            val selected = if (row == col) a else b
                            if (value == "v") {
                                expected += listOf(a, b, row, col, selected)
                            } else if (a == selected) {
                                expected += listOf(a, b, row, col)
                            }
                        }
                    }
                }
            }

            assertEquals(expected, solutions(parsed, if (value == "v") 5 else 4))
        }
    }

    @Test
    fun `out of range matrix axes should not select an aliased flat position`() {
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
    fun `affine matrix coordinates should preserve source solutions`() {
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
    fun `an element repair should move through declared affine coordinate chains`() {
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
    fun `an element repair should move through a materialized affine index`() {
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
        assertTrue(state.factors[elementId].isViolated(state, elementId))

        state.factors[elementId].proposeRepairMoves(state, elementId, state.moveSink)
        state.apply(state.moveSink.list.single())

        assertEquals(1L, state.assignment.intValue(day))
        assertEquals(0L, state.cost)
    }
}
