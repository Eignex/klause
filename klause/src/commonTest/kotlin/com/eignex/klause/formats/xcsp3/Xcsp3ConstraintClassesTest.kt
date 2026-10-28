package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.FactorWeightBook
import com.eignex.klause.localsearch.IMPLIED_FACTOR_INITIAL_WEIGHT
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Xcsp3ConstraintClassesTest {
    private val adjacent = "<allDifferent>x[]</allDifferent>"

    private val carriers = listOf(
        """<intension class="C">le(x[0],x[1])</intension>""",
        """<block class="C"><intension>le(x[0],x[1])</intension><intension>le(x[1],x[2])</intension></block>""",
        """<group class="C"><intension>le(%0,%1)</intension><args>x[0] x[1]</args><args>x[1] x[2]</args></group>""",
        """<group><intension class="C">le(%0,%1)</intension><args>x[0] x[1]</args><args>x[1] x[2]</args></group>""",
        """<group class="C"><sum><list>%0 %1</list><condition>(le,4)</condition></sum><args>x[0] x[1]</args></group>""",
        """<slide class="C"><list>x[]</list><intension>le(%0,%1)</intension></slide>""",
        """<slide><list>x[]</list><intension class="C">le(%0,%1)</intension></slide>""",
    )

    private fun streamed(constraints: String): Problem = Xcsp3.parse(
        """<instance><variables><array id="x" size="[4]">0..3</array></variables>
            <constraints>$constraints</constraints></instance>""",
    ).problem

    private fun materialized(constraints: String): Problem {
        val builder = Compiler.Builder()
        builder.declareVar(parseXml("""<array id="x" size="[4]">0..3</array>"""))
        builder.constraint(parseXml("<block>$constraints</block>"))
        return builder.build().problem
    }

    private fun Problem.implied(index: Int): Boolean = impliedFactorMask?.get(index) == true

    private fun Problem.impliedFlags(): List<Boolean> = List(numFactors) { implied(it) }

    private fun Problem.impliedWhere(predicate: (Factor) -> Boolean): Boolean {
        val index = factors.indexOfFirst(predicate)
        assertTrue(index >= 0)
        return implied(index)
    }

    @Test
    fun `symmetry classes should mark exactly the factors their carrier emits`() {
        for (spelling in listOf("symmetryBreaking", "symmetry-breaking")) {
            for (carrier in carriers) {
                for (parse in listOf(::streamed, ::materialized)) {
                    val problem = parse(adjacent + carrier.replace("\"C\"", "\"$spelling\"") + adjacent)

                    assertTrue(problem.factors.any { it !is AllDifferent }, carrier)
                    assertEquals(problem.factors.map { it !is AllDifferent }, problem.impliedFlags(), carrier)
                    assertTrue(problem.hasSymmetryBreaking, carrier)
                }
            }
        }
    }

    @Test
    fun `redundant classes should mark their factors without claiming symmetry breaking`() {
        for (spelling in listOf("redundantConstraints", "redundant-constraints")) {
            for (carrier in carriers) {
                val problem = streamed(adjacent + carrier.replace("\"C\"", "\"$spelling\"") + adjacent)

                assertEquals(problem.factors.map { it !is AllDifferent }, problem.impliedFlags(), carrier)
                assertFalse(problem.hasSymmetryBreaking, carrier)
            }
        }
    }

    @Test
    fun `unknown classes should leave the model unclassified`() {
        for (unknown in listOf("channeling", "redundant", "redundant-constraint", "clues symmetry_breaking")) {
            for (carrier in carriers) {
                val problem = streamed(adjacent + carrier.replace("\"C\"", "\"$unknown\""))

                assertNull(problem.impliedFactorMask, carrier)
                assertFalse(problem.hasSymmetryBreaking, carrier)
            }
        }
    }

    @Test
    fun `nested classes should combine inside their scope and not leak onto siblings`() {
        for (parse in listOf(::streamed, ::materialized)) {
            val problem = parse(
                """<block class="redundant-constraints"><intension class="clues symmetry-breaking">le(x[0],x[1])
                    </intension><intension>le(x[1],x[2])</intension></block><intension>le(x[2],x[3])</intension>""",
            )

            assertEquals(listOf(true, true, false), problem.impliedFlags())
            assertTrue(problem.hasSymmetryBreaking)
        }
    }

    @Test
    fun `a symmetry class over domain restrictions should keep the flag through the bake`() {
        val problem = streamed(
            """<instantiation class="symmetry-breaking"><list>x[0]</list><values>0</values></instantiation>""",
        )

        assertTrue(problem.bake().hasSymmetryBreaking)
    }

    @Test
    fun `a shared definition should stay implied only while every reader is classified`() {
        val indicator = { factor: Factor -> factor is ReifiedLinear && factor.vars.contentEquals(intArrayOf(0)) }
        val division = { factor: Factor -> factor is DivMod }
        for ((readers, owned) in listOf(
            listOf(
                """<sum class="A"><list>eq(x[0],1) eq(x[1],1)</list><condition>(le,1)</condition></sum>""",
                """<sum class="B"><list>eq(x[0],1) x[2]</list><condition>(ge,1)</condition></sum>""",
            ) to indicator,
            listOf(
                """<intension class="A">le(div(x[0],2),x[1])</intension>""",
                """<intension class="B">ge(div(x[0],2),x[2])</intension>""",
            ) to division,
        )) {
            for ((first, second, expected) in listOf(
                Triple("symmetry-breaking", "", false),
                Triple("", "symmetry-breaking", false),
                Triple("symmetry-breaking", "redundant-constraints", true),
            )) {
                val problem = streamed(
                    readers[0].replace("\"A\"", "\"$first\"") + readers[1].replace("\"B\"", "\"$second\""),
                )

                assertEquals(expected, problem.impliedWhere(owned), "$first/$second ${readers[0]}")
            }
        }
    }

    @Test
    fun `a recovered distinctness should be implied only when every source row is`() {
        val rows = listOf("ne(x[0],x[1])", "ne(x[0],x[2])", "ne(x[1],x[2])").map { "<intension>$it</intension>" }
        for ((inside, expected) in listOf(3 to true, 2 to false)) {
            val problem = streamed(
                """<block class="symmetry-breaking">${rows.take(inside).joinToString("")}</block>""" +
                    rows.drop(inside).joinToString(""),
            )

            assertEquals(1, problem.factors.count { it is Distinct })
            assertEquals(expected, problem.impliedWhere { it is Distinct })
        }
    }

    @Test
    fun `local search should seed the implied weight on only the classified factors`() {
        val problem = streamed(
            adjacent + """<group class="redundant-constraints"><intension>le(%0,%1)</intension>
                <args>x[0] x[1]</args><args>x[2] x[3]</args></group>""",
        )

        val weights = FactorWeightBook(problem).factorWeights

        assertEquals(
            problem.factors.map { if (it is AllDifferent) 1.0 else IMPLIED_FACTOR_INITIAL_WEIGHT },
            weights.toList(),
        )
    }

    private val symmetricSource = """<instance><variables><array id="x" size="[3]">0..2</array>
        <var id="y">0..1</var></variables><constraints><allDifferent>x[]</allDifferent>
        <instantiation class="C"><list>y</list><values>0</values></instantiation></constraints></instance>"""

    private fun symmetryFires(classes: String, override: Boolean?): Boolean {
        val overrides = PresolvePass.entries.filter { it != PresolvePass.BREAK_SYMMETRIES }.associateWith { false }
        val config = PresolveConfig(
            overrides = if (override == null) overrides else overrides + (PresolvePass.BREAK_SYMMETRIES to override),
        )
        val problem = Xcsp3.parse(symmetricSource.replace("\"C\"", "\"$classes\"")).problem
        return PresolvePipeline.run(problem, null, config, false).problem.factors.any { it is SymmetryHandling }
    }

    @Test
    fun `automatic symmetry breaking should defer to a classified source`() {
        assertTrue(symmetryFires("channeling", override = null))
        assertFalse(symmetryFires("symmetry-breaking", override = null))
    }

    @Test
    fun `an explicit symmetry override should still run over a classified source`() {
        assertTrue(symmetryFires("symmetry-breaking", override = true))
    }
}
