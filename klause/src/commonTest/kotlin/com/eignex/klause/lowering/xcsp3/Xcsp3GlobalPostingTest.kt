package com.eignex.klause.lowering.xcsp3

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.solver.Factor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
    fun `a strict ordered chain posts a strict Increasing`() {
        val parsed = parse("<ordered><list>x y z</list><operator>lt</operator></ordered>", threeVars)

        assertEquals(true, (factorsOf(parsed).single() as Increasing).strict)
        assertEquals(setOf(listOf(1L, 2L, 3L)), solutions(parsed, 3))
    }

    @Test
    fun `a descending ordered chain posts Increasing over the reversed sequence`() {
        val parsed = parse("<ordered><list>x y z</list><operator>ge</operator></ordered>", threeVars)

        val chain = factorsOf(parsed).single() as Increasing
        assertContentEquals(intArrayOf(2, 1, 0), chain.xs)
        assertTrue(solutions(parsed, 3).all { it[0] >= it[1] && it[1] >= it[2] })
        assertEquals(10, solutions(parsed, 3).size, "non-increasing triples over 1..3")
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
    fun `precedence over defaulted values posts a ValuePrecede chain`() {
        val parsed = parse("<precedence><list>x y z</list></precedence>", threeVars)

        // Values default to the union of the domains, so the chain is one link per adjacent pair.
        assertEquals(2, factorsOf(parsed).filterIsInstance<ValuePrecede>().size)
        assertEquals(5, solutions(parsed, 3).size, "the clause decomposition admits the same five")
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
}
