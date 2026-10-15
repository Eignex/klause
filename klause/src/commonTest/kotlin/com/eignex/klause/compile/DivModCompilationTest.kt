package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.div
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.plus
import com.eignex.klause.schema.rem
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class DivModCompilationTest {
    @Test
    fun `existing truncated variable division syntax retains shared outputs and source solutions`() {
        val a = IntHandle("a", 0, 8)
        val b = IntHandle("b", 2, 4)
        val schema = SchemaDef<SchemaEntry>(mapOf(
            "a" to IntSpec(0, 8), "b" to IntSpec(2, 4),
            "quotient" to NamedConstraint((a / b) eq 2), "remainder" to NamedConstraint((a % b) eq 1),
        )).compile().problem
        val fzn = parseFlatZinc("""
            var 0..8: a; var 2..4: b;
            constraint int_div(a,b,2); constraint int_mod(a,b,1); solve satisfy;
        """.trimIndent()).problem
        val xcsp = Xcsp3.parse("""
            <instance><variables><var id="a">0..8</var><var id="b">2..4</var></variables>
            <constraints><intension>eq(div(a,b),2)</intension><intension>eq(mod(a,b),1)</intension>
            </constraints></instance>
        """.trimIndent()).problem
        for ((format, problem) in listOf("schema" to schema, "FlatZinc" to fzn, "XCSP3" to xcsp)) {
            assertEquals(1, problem.factors.filterIsInstance<DivMod>().size, format)
            assertFalse(problem.factors.any { it is Product }, format)
            val samples = BacktrackSolver(problem.bake()).enumerate(BacktrackParams(maxDecisions = 100))
                .map { it.intValue(0) to it.intValue(1) }.toSet()

            assertEquals(setOf(5L to 2L, 7L to 3L), samples, format)
        }
    }

    @Test
    fun `affine operands retain one correlated relation across quotient and remainder uses`() {
        val a = IntHandle("a", 0, 8)
        val b = IntHandle("b", 2, 4)
        val schema = SchemaDef<SchemaEntry>(mapOf(
            "a" to IntSpec(0, 8), "b" to IntSpec(2, 4),
            "quotient" to NamedConstraint(((a + 1) / b) eq 2),
            "remainder" to NamedConstraint(((a + 1) % b) eq 1),
        )).compile().problem
        val xcsp = Xcsp3.parse("""
            <instance><variables><var id="a">0..8</var><var id="b">2..4</var></variables>
            <constraints><intension>eq(div(add(a,1),b),2)</intension>
            <intension>eq(mod(add(a,1),b),1)</intension></constraints></instance>
        """.trimIndent()).problem

        for (problem in listOf(schema, xcsp)) assertEquals(1, problem.factors.filterIsInstance<DivMod>().size)
    }

    @Test
    fun `SMT division retains a shared Euclidean relation with a negative divisor`() {
        val parsed = SmtLib.parse("""
            (declare-const a Int) (assert (<= (- 8) a 8))
            (assert (= (div a (- 3)) 3)) (assert (= (mod a (- 3)) 2))
        """.trimIndent())
        val factor = parsed.model.factors.filterIsInstance<DivMod>().single()
        val samples = BacktrackSolver(parsed.model.bake()).enumerate(BacktrackParams(maxDecisions = 100))
            .map { it.intValue(parsed.intVarNames.getValue("a")) }.toSet()

        assertEquals(DivisionConvention.EUCLIDEAN, factor.convention)
        assertEquals(setOf(-7L), samples)
    }

    @Test
    fun `SMT minimum word division retains its exact over-word quotient`() {
        val parsed = SmtLib.parse("""
            (declare-const a Int) (assert (= a (- 9223372036854775808)))
            (assert (= (div a (- 1)) 9223372036854775808))
            (assert (= (mod a (- 1)) 0))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve(BacktrackParams(maxDecisions = 100))

        assertEquals("-9223372036854775808", assertIs<OpenTheoryResult.Sat>(result).assignment
            .intValue(parsed.intVarNames.getValue("a")))
    }

    @Test
    fun `FlatZinc output aliases retain their source role equations`() {
        for ((operation, expected) in listOf("div" to setOf(0L), "mod" to (-2L..2L).toSet())) {
            val parsed = parseFlatZinc(
                "var -4..4: a; constraint int_$operation(a,3,a); solve satisfy;",
            )
            val assignments = BacktrackSolver(parsed.problem.bake()).enumerate(BacktrackParams(maxDecisions = 100))
                .map { it.intValue(parsed.intVarsByName.getValue("a")) }.toSet()

            assertEquals(expected, assignments, operation)
        }
    }

    @Test
    fun `signed FlatZinc constant division retains truncation`() {
        val parsed = parseFlatZinc("""
            var -8..8: a; constraint int_div(a,-3,2); constraint int_mod(a,-3,-1); solve satisfy;
        """.trimIndent())
        val samples = BacktrackSolver(parsed.problem.bake()).enumerate(BacktrackParams(maxDecisions = 100))
            .map { it.intValue(parsed.intVarsByName.getValue("a")) }.toSet()

        assertEquals(setOf(-7L), samples)
    }
}
