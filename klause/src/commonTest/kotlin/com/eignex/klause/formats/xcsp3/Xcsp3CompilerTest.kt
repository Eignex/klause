package com.eignex.klause.formats.xcsp3

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Xcsp3CompilerTest {
    @Test
    fun `constant distance disequalities should preserve every source pair`() {
        for (constant in listOf(-1, 0, 1, 2)) {
            for (expression in listOf("ne(dist(x,y),$constant)", "ne($constant,dist(y,x))")) {
                val parsed = Xcsp3.parse(
                    """<instance><variables><var id="x">-1..1</var><var id="y">-1..1</var>
                        </variables><constraints><intension>$expression</intension></constraints></instance>""",
                )

                val actual = BruteForceSolver(parsed.problem.bake()).enumerate(BruteForceParams(randomSeed = 0L))
                    .map { it.ints.toList() }.toSet()
                val expected = (-1L..1L).flatMap { x ->
                    (-1L..1L).filter { y -> kotlin.math.abs(x - y) != constant.toLong() }.map { y -> listOf(x, y) }
                }.toSet()

                assertEquals(expected, actual, expression)
            }
        }
    }

    @Test
    fun `top level distance disequalities should retain only exact linear rows`() {
        for ((constant, rows) in listOf(-1 to 0, 0 to 1, 1 to 2)) {
            val parsed = Xcsp3.parse(
                """<instance><variables><var id="x">0..56</var><var id="y">0..56</var>
                    </variables><constraints><intension>ne(dist(x,y),$constant)</intension>
                    </constraints></instance>""",
            )

            assertEquals(2, parsed.problem.numIntVars)
            assertEquals(0, parsed.problem.numBoolVars)
            assertEquals(rows, parsed.problem.factors.size)
            assertTrue(parsed.problem.factors.all { it is Linear && it.op == LinearOp.NE })
        }
    }

    @Test
    fun `negative distance disequalities should still validate arithmetic operands`() {
        assertFailsWith<UnsupportedXcsp3Exception> {
            Xcsp3.parse(
                """<instance><variables><var id="x">0..1</var><var id="y">0..1</var>
                    </variables><constraints><intension>ne(dist(div(x,0),y),-1)</intension>
                    </constraints></instance>""",
            )
        }
    }

    @Test
    fun `distance disequalities should preserve exact signed word boundary comparisons`() {
        for ((x, y, constant, feasible) in listOf(
            listOf(Long.MIN_VALUE, Long.MAX_VALUE, 1L, 1L),
            listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, 1L, 0L),
            listOf(Long.MAX_VALUE, Long.MAX_VALUE, 0L, 0L),
            listOf(Long.MAX_VALUE, Long.MIN_VALUE, Int.MAX_VALUE.toLong(), 1L),
        )) {
            val parsed = Xcsp3.parse(
                """<instance><variables><var id="x">$x</var><var id="y">$y</var>
                    </variables><constraints><intension>ne(dist(x,y),$constant)</intension>
                    </constraints></instance>""",
            )

            val actual = BruteForceSolver(parsed.problem.bake()).enumerate(BruteForceParams(randomSeed = 0L)).toList()

            assertEquals(feasible.toInt(), actual.size)
        }
    }

    @Test
    fun `n-ary equality implications should preserve every source truth value`() {
        for ((constraint, roles) in listOf(
            "<intension>imp(eq(x[0],x[1],x[2]),ne(x[0],x[3]))</intension>" to listOf(0, 1, 2, 3),
            """<group><intension>imp(eq(%0,%1,%2),ne(%0,%3))</intension>
                <args>x[0..3]</args></group>""" to listOf(0, 1, 2, 3),
            """<group><intension>imp(eq(%0,%1,%2),ne(%0,%3))</intension>
                <args>x[3] x[0..2]</args></group>""" to listOf(3, 0, 1, 2),
        )) {
            val baked = Xcsp3.parse(
                """<instance><variables><array id="x" size="[4]">0..3</array></variables>
                    <constraints>$constraint</constraints></instance>""",
            ).problem.bake()

            for (nonzero in listOf(1L, 3L)) {
                for (mask in 0 until 16) {
                    val values = List(4) { if (mask and (1 shl it) == 0) 0L else nonzero }
                    val antecedent = roles.take(3).map { values[it] }.distinct().size == 1
                    val consequent = values[roles[0]] != values[roles[3]]

                    val result = baked.propagate(Assumptions(ints = values.indices.associateWith { values[it] }))

                    assertEquals(!antecedent || consequent, result is PropagationResult.Implied, "$constraint: $values")
                }
            }
        }
    }

    @Test
    fun `Boolean product terms should preserve their source truth values`() {
        for ((expression, trueInputs) in listOf(
            "eq(x,1)" to setOf(1L),
            "not(eq(x,1))" to setOf(0L, 2L),
            "or(eq(x,1),eq(x,2))" to setOf(1L, 2L),
            "eq(0,1)" to emptySet(),
            "eq(1,1)" to setOf(0L, 1L, 2L),
        )) {
            val parsed = Xcsp3.parse(
                """<instance><variables>
                    <var id="x">0..2</var><var id="y">0..2</var><var id="z">0..2</var>
                    </variables><constraints><intension>eq(mul($expression,y),z)</intension>
                    </constraints></instance>""",
            )

            val actual = BruteForceSolver(parsed.problem.bake())
                .enumerate(BruteForceParams(randomSeed = 0L))
                .map { s -> (0 until 3).map { s.ints[it] } }.toSet()

            val expected = (0L..2L).flatMap { x ->
                (0L..2L).map { y -> listOf(x, y, if (x in trueInputs) y else 0L) }
            }.toSet()
            assertEquals(expected, actual, expression)
        }
    }

    @Test
    fun `a sum condition should construct its declared variables and normalized row`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><var id="x">1..3</var><var id="y">2..4</var></variables>
                <constraints><sum><list>x y</list><coeffs>2 -1</coeffs><condition>(eq, 1)</condition></sum></constraints>
                </instance>""",
        )

        assertEquals(mapOf("x" to 0, "y" to 1), parsed.intVarNames)
        assertEquals(1L, parsed.problem.intDomainOrNull(0)!!.min)
        assertEquals(4L, parsed.problem.intDomainOrNull(1)!!.max)
        val row = parsed.problem.factors.single() as Linear
        assertContentEquals(intArrayOf(0, 1), row.vars)
        assertContentEquals(longArrayOf(2, -1), row.integerConstants!!.coeffs)
        assertEquals(LinearOp.EQ, row.op)
        assertEquals(1L, row.integerConstants!!.bound)
    }

    @Test
    fun `a malformed streamed closing tag should report an XCSP3 error`() {
        val error = assertFailsWith<UnsupportedXcsp3Exception> {
            Xcsp3.parse("<instance><variables></constraints></instance>")
        }

        assertTrue(error.message.orEmpty().contains("mismatched closing tag"))
    }

    @Test
    fun `a processing instruction between streamed children should be ignored`() {
        val parsed = Xcsp3.parse(
            "<instance><variables><?tool ignore?><var id=\"x\">0..1</var></variables></instance>",
        )

        assertEquals(mapOf("x" to 0), parsed.intVarNames)
    }

    @Test
    fun `each domain child of a mixed array should type only the cells it lists`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><array id="x" size="[2][2]">
                <domain for="x[0][] x[1][1]"> 0 1 </domain><domain for="x[1][0..0]"> 5..9 </domain>
                </array></variables></instance>""",
        )

        val bounds = parsed.intVarNames.mapValues { (_, v) ->
            parsed.problem.intDomainOrNull(v)!!.let { it.min to it.max }
        }

        assertEquals(
            mapOf(
                "x[0][0]" to (0L to 1L),
                "x[0][1]" to (0L to 1L),
                "x[1][0]" to (5L to 9L),
                "x[1][1]" to (0L to 1L),
            ),
            bounds,
        )
    }

    @Test
    fun `others should type every mixed array cell no other domain child lists`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><array id="x" size="[3]">
                <domain for="others"> 4 </domain><domain for="x[1]"> 0..2 </domain>
                </array></variables></instance>""",
        )

        val bounds = parsed.intVarNames.mapValues { (_, v) ->
            parsed.problem.intDomainOrNull(v)!!.let { it.min to it.max }
        }

        assertEquals(mapOf("x[0]" to (4L to 4L), "x[1]" to (0L to 2L), "x[2]" to (4L to 4L)), bounds)
    }

    @Test
    fun `a mixed array cell no domain child lists should not be declared`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><array id="x" size="[3]"><domain for="x[0] x[2]"> 0..1 </domain></array>
                </variables><constraints><sum><list>x[]</list><condition>(ge, 2)</condition></sum></constraints>
                </instance>""",
        )

        assertEquals(setOf("x[0]", "x[2]"), parsed.intVarNames.keys)
        assertContentEquals(intArrayOf(0, 1), (parsed.problem.factors.single() as Linear).vars)
    }
}
