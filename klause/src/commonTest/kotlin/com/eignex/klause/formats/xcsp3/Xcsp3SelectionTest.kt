package com.eignex.klause.formats.xcsp3

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Table
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Xcsp3SelectionTest {
    @Test
    fun `nested conditional extrema retain native definitions`() {
        val parsed = Xcsp3.parse("""
            <instance><variables><var id="x">0..1</var><var id="y">0..1</var></variables>
            <constraints><intension>eq(if(gt(x,0),max(y,x),min(y,x)),1)</intension></constraints></instance>
        """.trimIndent())

        val sample = assertIs<SolveResult.Sat>(BacktrackSolver(parsed.problem.bake())
            .solve(BacktrackParams(randomSeed = 0))).assignment

        assertEquals(2, parsed.problem.factors.filterIsInstance<ArrayMinMax>().size)
        assertEquals(1, parsed.problem.factors.filterIsInstance<Element>().size)
        assertEquals(1L, sample.intValue(parsed.intVarNames.getValue("x")))
    }

    @Test
    fun `standard and legacy vector offsets select the same source entry`() {
        for ((parent, list) in listOf("" to "startIndex=\"-2\"", "startIndex=\"-2\"" to "")) {
            val parsed = Xcsp3.parse("""
                <instance><variables><var id="i">-3..0</var><var id="v">9</var></variables><constraints>
                <element $parent><list $list>4 9</list><index>i</index><value>v</value></element>
                </constraints></instance>
            """.trimIndent())

            val sample = assertIs<SolveResult.Sat>(BacktrackSolver(parsed.problem.bake())
                .solve(BacktrackParams(randomSeed = 0))).assignment

            assertEquals(-2, parsed.problem.factors.filterIsInstance<Element>().single().indexOffset)
            assertEquals(-1L, sample.intValue(parsed.intVarNames.getValue("i")))
        }
    }

    @Test
    fun `constant matrix selection prevents a column from aliasing another row`() {
        for ((matrix, invalidRow, invalidColumn) in listOf(Triple("(4,9)(9,4)", 2L, 5L),
                Triple("(4)(9,4)", 2L, 4L))) {
            val parsed = Xcsp3.parse("""
                <instance><variables><var id="i">2..3</var><var id="j">2..5</var><var id="v">9</var></variables>
                <constraints><element startRowIndex="2" startColIndex="3"><matrix>$matrix</matrix>
                <index>i j</index><value>v</value></element></constraints></instance>
            """.trimIndent())
            val solver = BacktrackSolver(parsed.problem.bake())

            val invalid = solver.solve(BacktrackParams(assumptions = Assumptions(ints = mapOf(
                parsed.intVarNames.getValue("i") to invalidRow, parsed.intVarNames.getValue("j") to invalidColumn))))

            assertIs<SolveResult.Unsat>(invalid)
            assertIs<SolveResult.Sat>(solver.solve(BacktrackParams(randomSeed = 0)))
            assertTrue(parsed.problem.factors.none { it is Table })
            assertEquals(3, parsed.problem.factors.filterIsInstance<Element>().size)
        }
    }

    @Test
    fun `indexed extrema constrain the source index without requiring a condition`() {
        for (operation in listOf("minimum", "maximum")) {
            val parsed = Xcsp3.parse("""
                <instance><variables><var id="x">1</var><var id="y">2</var><var id="i">-2..0</var></variables>
                <constraints><$operation><list startIndex="-2">x y x</list><index>i</index></$operation>
                </constraints></instance>
            """.trimIndent())

            val sample = assertIs<SolveResult.Sat>(BacktrackSolver(parsed.problem.bake())
                .solve(BacktrackParams(randomSeed = 0))).assignment

            val index = sample.intValue(parsed.intVarNames.getValue("i"))
            assertEquals(if (operation == "maximum") 2L else 1L, listOf(1L, 2L, 1L)[(index + 2).toInt()])
            assertEquals(1, parsed.problem.factors.filterIsInstance<ArrayMinMax>().size)
            assertEquals(-2, parsed.problem.factors.filterIsInstance<Element>().single().indexOffset)
        }
    }

    @Test
    fun `ranked selection declines rather than ignoring the requested occurrence`() {
        for (rank in listOf("first", "last")) {
            assertFailsWith<UnsupportedXcsp3Exception> {
                Xcsp3.parse("""
                    <instance><variables><var id="i">0..1</var><var id="v">4</var></variables>
                    <constraints><element><list>4 4</list><index rank="$rank">i</index><value>v</value></element>
                    </constraints></instance>
                """.trimIndent())
            }
        }
    }
}
