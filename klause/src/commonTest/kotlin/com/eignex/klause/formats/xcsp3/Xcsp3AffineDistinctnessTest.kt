package com.eignex.klause.formats.xcsp3

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lowering.LinRelation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Xcsp3AffineDistinctnessTest {
    @Test
    fun `complete distance groups should recover both diagonal relations`() {
        for (expression in listOf("ne(dist(%0,%1),%2)", "ne(%2,abs(sub(%1,%0)))")) {
            val parsed = Xcsp3.parse(
                """<instance><variables><array id="q" size="[4]">0..3</array></variables>
                    <constraints><group><intension>$expression</intension>
                    <args>q[0] q[1] 1</args><args>q[0] q[2] 2</args><args>q[0] q[3] 3</args>
                    <args>q[1] q[2] 1</args><args>q[1] q[3] 2</args><args>q[2] q[3] 1</args>
                    </group></constraints></instance>""",
            )

            assertEquals(2, parsed.problem.factors.count { it is AllDifferent }, expression)
            assertEquals(10, parsed.problem.numIntVars, expression)
            assertEquals(6, parsed.definitionHints.size, expression)
        }
    }

    @Test
    fun `recovered hall sets should tighten source bounds beyond pairwise propagation`() {
        val source = """<instance><variables><var id="x">1..2</var><var id="y">0..1</var>
            <var id="z">0..2</var></variables><constraints>
            <intension>ne(dist(x,y),1)</intension><intension>ne(dist(x,z),2)</intension>
            <intension>ne(dist(y,z),1)</intension></constraints></instance>"""
        val pairwise = PropagationSession(Xcsp3.parse(source, recoveryCancellation = Cancellation { true }).problem)
        val collective = PropagationSession(Xcsp3.parse(source).problem)

        assertEquals(0L, pairwise.intDomain(2).min)
        assertEquals(1L, collective.intDomain(2).min)
    }

    @Test
    fun `complete affine groups should detect a collective pigeonhole`() {
        val source = """<instance><variables><var id="x">0..1</var><var id="y">-1..0</var>
            <var id="z">1..2</var></variables><constraints>
            <intension>ne(x,add(y,1))</intension><intension>ne(x,sub(z,1))</intension>
            <intension>ne(add(y,1),sub(z,1))</intension></constraints></instance>"""

        assertFalse(PropagationSession(Xcsp3.parse(source, recoveryCancellation = Cancellation { true }).problem).isUnsatAtRoot)
        assertTrue(PropagationSession(Xcsp3.parse(source).problem).isUnsatAtRoot)
    }

    @Test
    fun `affine hall deductions should restore across sibling branches`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><var id="x">1..3</var><var id="y">0..2</var>
                <var id="z">0..2</var></variables><constraints>
                <intension>ne(x,add(y,1))</intension><intension>ne(x,add(z,2))</intension>
                <intension>ne(add(y,1),add(z,2))</intension></constraints></instance>""",
        )
        val session = PropagationSession(parsed.problem)
        assertEquals(0L, session.intDomain(2).min)

        session.pinIntAtMost(0, 2)
        session.pinIntAtMost(1, 1)
        assertEquals(1L, session.intDomain(2).min)
        session.popToLevel(0)
        assertEquals(0L, session.intDomain(2).min)
        session.pinIntAtMost(0, 2)
        session.pinIntAtLeast(1, 2)
        assertEquals(0L, session.intDomain(2).min)
    }

    @Test
    fun `recovered matching reasons should be entailed by affine source channels`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><array id="x" size="[3]">0..3</array></variables>
                <constraints><intension>ne(x[0],add(x[1],1))</intension>
                <intension>ne(x[0],add(x[2],2))</intension>
                <intension>ne(add(x[1],1),add(x[2],2))</intension></constraints></instance>""",
        ).problem
        val global = parsed.factors.filterIsInstance<AllDifferent>().single()
        val problem = Problem(
            numBoolVars = parsed.numBoolVars,
            numIntVars = parsed.numIntVars,
            declaredIntDomains = parsed.declaredIntDomains,
            factors = (listOf(global) + parsed.factors.filter { it !== global }).toTypedArray(),
        )

        PropagationReasonOracle.assertReasonsImply(problem, "affine Hall") { state ->
            state.tightenIntMin(global.vars[0], 1) && state.tightenIntMax(global.vars[0], 2) &&
                state.tightenIntMax(global.vars[1], 2)
        }
    }

    @Test
    fun `recovery should preserve every source assignment with exact offsets`() {
        for (expressions in listOf(
            listOf("ne(x,add(y,1))", "ne(x,sub(z,1))", "ne(add(y,1),sub(z,1))"),
            listOf("ne(dist(x,y),1)", "ne(dist(x,z),2)", "ne(dist(y,z),1)"),
        )) {
            val source = """<instance><variables><var id="x">-1..1</var><var id="y">-1..1</var>
                <var id="z">-1..1</var></variables><constraints>""" +
                expressions.joinToString("") { "<intension>$it</intension>" } + "</constraints></instance>"
            val parsed = Xcsp3.parse(source)
            val actual = BruteForceSolver(parsed.problem.bake()).enumerate(BruteForceParams(randomSeed = 0L))
                .map { it.ints.take(3) }.toSet()
            val pairwise = Xcsp3.parse(source, recoveryCancellation = Cancellation { true })
            val expected = BruteForceSolver(pairwise.problem.bake()).enumerate(BruteForceParams(randomSeed = 0L))
                .map { it.ints.toList() }.toSet()

            assertEquals(expected, actual, expressions.toString())
        }
    }

    @Test
    fun `incomplete inconsistent and guarded groups should retain pairwise semantics`() {
        for (last in listOf("ne(x,z)", "ne(x,add(z,7))", "imp(eq(x,0),ne(x,add(z,2)))")) {
            val parsed = Xcsp3.parse(
                """<instance><variables><var id="x">0..3</var><var id="y">0..3</var>
                    <var id="z">0..3</var></variables><constraints>
                    <intension>ne(x,add(y,1))</intension><intension>ne(y,add(z,1))</intension>
                    <intension>$last</intension></constraints></instance>""",
            )

            assertTrue(parsed.problem.factors.none { it is AllDifferent }, last)
        }
    }

    @Test
    fun `unsupported reification attributes should be rejected before group instantiation`() {
        for (attribute in listOf("reifiedBy=\"b\"", "hreifiedFrom=\"b\"", "hreifiedTo=\"b\"", "type=\"soft\"")) {
            assertFailsWith<UnsupportedXcsp3Exception>(attribute) {
                Xcsp3.parse(
                    """<instance><variables><array id="x" size="[3]">0..1</array></variables>
                        <constraints><group><intension $attribute>ne(%0,%1)</intension>
                        <args>x[0] x[1]</args><args>x[0] x[2]</args><args>x[1] x[2]</args>
                        </group></constraints></instance>""",
                )
            }
        }
    }

    @Test
    fun `recovered channels should have zero objective coefficients`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><array id="x" size="[3]">0..3</array></variables>
                <constraints><intension>ne(x[0],add(x[1],1))</intension>
                <intension>ne(x[0],add(x[2],2))</intension>
                <intension>ne(add(x[1],1),add(x[2],2))</intension></constraints>
                <objectives><minimize type="sum"><list>x[]</list></minimize></objectives></instance>""",
        )

        assertContentEquals(longArrayOf(1, 1, 1, 0, 0), parsed.objective!!.intCoefficients)
    }

    @Test
    fun `unsafe shifted domains should keep their exact source rows`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><array id="x" size="[3]">0..${Long.MAX_VALUE}</array></variables>
                <constraints><intension>ne(sub(x[0],x[1]),1)</intension>
                <intension>ne(sub(x[0],x[2]),2)</intension>
                <intension>ne(sub(x[1],x[2]),1)</intension></constraints></instance>""",
        )

        assertEquals(3, parsed.problem.numIntVars)
        assertTrue(parsed.problem.factors.none { it is AllDifferent })
    }

    @Test
    fun `recognition should stop at its work allowance without emitting a partial group`() {
        val index = Xcsp3AffineDistinctness(workLimit = 1)
        for ((a, b, bound) in listOf(Triple(0, 1, 1L), Triple(0, 2, 2L), Triple(1, 2, 1L))) {
            index.record(LinRelation.LongRel(intArrayOf(a, b), longArrayOf(1, -1), bound), LinearOp.NE)
        }

        assertTrue(index.groups(Cancellation.Never).isEmpty())
    }

    @Test
    fun `duplicate and reversed source rows should retain one affine group`() {
        val index = Xcsp3AffineDistinctness()
        repeat(2) {
            for ((a, b, bound) in listOf(Triple(0, 1, 1L), Triple(0, 2, 2L), Triple(1, 2, 1L))) {
                index.record(LinRelation.LongRel(intArrayOf(b, a), longArrayOf(-1, 1), bound), LinearOp.NE)
            }
        }

        val groups = index.groups(Cancellation.Never)

        assertEquals(1, groups.size)
        assertContentEquals(longArrayOf(0, 1, 2), groups.single().offsets)
    }
}
