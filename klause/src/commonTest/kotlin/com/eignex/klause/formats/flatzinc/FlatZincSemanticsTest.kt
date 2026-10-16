package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver

import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.ir.UndeclaredRealBounds
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincSemanticsTest {
    @Test
    fun `default integer declarations should retain the declared FlatZinc range`() {
        for (declaration in listOf("var int: x;", "array[1..2] of var int: x;")) {
            val program = parseFlatZinc("$declaration solve satisfy;")

            for (id in 0 until program.problem.numIntVars) {
                assertEquals(-1_000_000L, program.problem.intBounds.lower(id))
                assertEquals(1_000_000L, program.problem.intBounds.upper(id))
            }
            assertFalse(program.semantics.hasOverrides)
        }
    }

    @Test
    fun `caller integer endpoints should replace the default rather than clamp to it`() {
        val overrides = SemanticOverrides(
            undeclaredIntegers = BoundsOverride(BoundOverride.Value(-2_000_000), BoundOverride.Value(3_000_000)),
        )
        for (declaration in listOf("var int: x;", "array[1..2] of var int: x;")) {
            val program = parseFlatZinc("$declaration solve satisfy;", semanticOverrides = overrides)

            for (id in 0 until program.problem.numIntVars) {
                assertEquals(-2_000_000L, program.problem.intBounds.lower(id))
                assertEquals(3_000_000L, program.problem.intBounds.upper(id))
            }
            assertEquals(
                UndeclaredIntegerBounds(-2_000_000, 3_000_000),
                program.semantics.effective.undeclaredIntegers,
            )
            assertTrue(program.semantics.hasOverrides)
        }
    }

    @Test
    fun `widened integer policy should admit values beyond the default range`() {
        val program = parseFlatZinc(
            "var int: x; constraint int_eq(x, 2000001); solve satisfy;",
            semanticOverrides = SemanticOverrides(
                undeclaredIntegers = BoundsOverride(upper = BoundOverride.Value(3_000_000)),
            ),
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val assignment = assertIs<SolveResult.Sat>(result).assignment
        assertEquals(2_000_001L, assignment.intValue(program.intVarsByName.getValue("x")))
    }

    @Test
    fun `a finite minimum word endpoint should remain distinct from an open endpoint`() {
        val program = parseFlatZinc(
            "var int: x; solve satisfy;",
            semanticOverrides = SemanticOverrides(
                undeclaredIntegers = BoundsOverride(lower = BoundOverride.Value(Long.MIN_VALUE)),
            ),
        )

        assertTrue(program.problem.intBounds.hasLower(0))
        assertEquals(Long.MIN_VALUE, program.problem.intBounds.lower(0))
    }

    @Test
    fun `legacy integer arguments should use the same replacement contract`() {
        val program = parseFlatZinc(
            "var int: x; solve satisfy;", unboundedIntLo = -2_000_000, unboundedIntHi = 3_000_000,
        )

        assertEquals(-2_000_000L, program.problem.intBounds.lower(0))
        assertEquals(3_000_000L, program.problem.intBounds.upper(0))
        assertTrue(program.semantics.hasOverrides)
    }

    @Test
    fun `an explicit open integer endpoint should remain open in the source model`() {
        val overrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(upper = BoundOverride.Open))

        val program = parseFlatZinc("var int: x; solve satisfy;", semanticOverrides = overrides)

        assertEquals(-1_000_000L, program.problem.intBounds.lower(0))
        assertFalse(program.problem.intBounds.hasUpper(0))
        assertEquals(UndeclaredIntegerBounds(-1_000_000, null), program.semantics.effective.undeclaredIntegers)
    }

    @Test
    fun `explicit source ranges should remain unchanged by undeclared range overrides`() {
        val overrides = SemanticOverrides(
            undeclaredIntegers = BoundsOverride(BoundOverride.Value(-20), BoundOverride.Value(30)),
        )

        val program = parseFlatZinc("var -2..5: x; solve satisfy;", semanticOverrides = overrides)

        assertEquals(-2L, program.problem.intBounds.lower(0))
        assertEquals(5L, program.problem.intBounds.upper(0))
    }

    @Test
    fun `a ranged alias should close open endpoints without losing its source domain`() {
        val overrides = SemanticOverrides(
            undeclaredIntegers = BoundsOverride(BoundOverride.Open, BoundOverride.Open),
        )
        for (range in listOf("-2..5", "{-2, 5}")) {
            val program = parseFlatZinc(
                "var int: x; var $range: y = x; solve satisfy;", semanticOverrides = overrides,
            )

            val id = program.intVarsByName.getValue("x")
            assertEquals(-2L, program.problem.intBounds.lower(id))
            assertEquals(5L, program.problem.intBounds.upper(id))
            if (range.startsWith("{")) assertFalse(0L in checkNotNull(program.problem.intDomainOrNull(id)))
        }
    }

    @Test
    fun `string streamed and execution entries should retain the same effective integer policy`() {
        val source = "var int: x; solve satisfy;"
        val overrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(lower = BoundOverride.Value(-2_000_000)))

        val programs = listOf(
            parseFlatZinc(source, semanticOverrides = overrides),
            parseFlatZinc(StringCharSource(source), semanticOverrides = overrides),
            parseFlatZincExecution(source, semanticOverrides = overrides).program,
            parseFlatZincExecution(StringCharSource(source), semanticOverrides = overrides).program,
        )

        for (program in programs) {
            assertEquals(-2_000_000L, program.problem.intBounds.lower(0))
            assertEquals(1_000_000L, program.problem.intBounds.upper(0))
            assertEquals(programs.first().semantics.effective, program.semantics.effective)
        }
    }

    @Test
    fun `typed grid windows should retain representable subnormal endpoints`() {
        val endpoint = SemanticRational.of(BIG_ONE, BIG_ONE shl 1074)
        val bounds = UndeclaredRealBounds(endpoint, endpoint)
        val overrides = SemanticOverrides(floats = FloatSemantics.Grid(8, 1_000_000, bounds))

        val program = parseFlatZinc("var float: x; solve minimize x;", semanticOverrides = overrides)

        val variable = program.floatVarsByName.getValue("x")
        assertEquals(Double.MIN_VALUE, variable.lo)
        assertEquals(Double.MIN_VALUE, variable.hi)
    }

    @Test
    fun `a typed grid window should decline rather than round an unrepresentable endpoint`() {
        val lower = SemanticRational.of(bigIntOf(1), bigIntOf(10))
        val bounds = UndeclaredRealBounds(lower, SemanticRational.ONE)
        val overrides = SemanticOverrides(floats = FloatSemantics.Grid(8, 1_000_000, bounds))

        val error = assertFailsWith<IllegalArgumentException> {
            parseFlatZinc("var float: x; solve satisfy;", semanticOverrides = overrides)
        }

        assertTrue("cannot be represented exactly" in error.message.orEmpty())
    }

    @Test
    fun `typed float windows should reach declaration planning without certifying singleton choices`() {
        val bounds = UndeclaredRealBounds(SemanticRational.ofLong(2), SemanticRational.ofLong(2))
        val overrides = SemanticOverrides(floats = FloatSemantics.Grid(8, 1_000_000, bounds))
        val source = """
            var float: x;
            var float: y;
            var float: z;
            constraint float_times(x, y, z);
            solve satisfy;
        """.trimIndent()

        val program = parseFlatZinc(source, semanticOverrides = overrides)

        for (variable in program.floatVarsByName.values) {
            assertFalse(variable.lpOnly)
            assertEquals(2.0, variable.lo)
            assertEquals(2.0, variable.hi)
        }
        assertTrue(program.semantics.isApproximate)
    }
}
