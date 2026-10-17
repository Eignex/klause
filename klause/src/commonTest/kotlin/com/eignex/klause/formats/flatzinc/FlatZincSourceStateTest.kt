package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.UndeclaredRealBounds
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.plus
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlatZincSourceStateTest {
    @Test
    fun `automatic original or stopped preparation should not force legacy grid geometry`() {
        for (stopped in listOf(false, true)) {
            val program = parseFlatZinc("var 0.0..1.0: x; constraint float_eq(x,0.25); solve satisfy;",
                floatBuckets = 2)
            val original = program.sourceProgram.problem

            val invocation = program.prepareSourceExecution(SourceSetupRequest(program.sourceAttachment(),
                ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK, cancellation = Cancellation { stopped }))

            assertSame(original, invocation.source.originalSource)
            assertNull(invocation.floatProjection)
            assertFalse(assertNotNull(program.retainedSource).materialized)
        }
    }

    @Test
    fun `a copied private search group should retain only original integer hints on source attachment`() {
        val program = parseFlatZinc("""
            var 1..2: i;
            var 0.0..1.0: grid;
            constraint float_le(grid, 0.5);
            solve :: int_search([i, grid], input_order, indomain_max, complete) satisfy;
        """.trimIndent(), floatBuckets = 3)

        val copied = program.copy()

        assertEquals(listOf(0, 1), assertNotNull(copied.searchHints).tiers.single().intVars.toList())
        assertEquals(listOf(0), assertNotNull(copied.sourceAttachment().searchHints).tiers.single().intVars.toList())
        assertSame(program.sourceProgram.problem, copied.sourceProgram.problem)
    }

    @Test
    fun `finite declarations should not acquire an unrepresentable unrelated grid window`() {
        val lower = BIG_ONE shl 2048
        val policy = FloatSemantics.Grid(3, 1000, UndeclaredRealBounds(SemanticRational.of(lower),
            SemanticRational.of(lower + BIG_ONE)))

        val program = parseFlatZinc("var 100.0..102.0: x; constraint float_le(x,101.0); solve satisfy;",
            semanticOverrides = SemanticOverrides(floats = policy))

        assertEquals(100.0, program.sourceProgram.problem.realLower[0])
        assertEquals(102.0, program.sourceProgram.problem.realUpper[0])
        assertEquals(100.0, program.floatVarsByName.getValue("x").lo)
        assertEquals(102.0, program.floatVarsByName.getValue("x").hi)
        assertEquals(0, program.problem.numRealVars)
    }

    @Test
    fun `source capture should retain original real products without materializing private buckets`() {
        val program = parseFlatZinc("""
            var 0.0..1.0: x;
            var 0.0..1.0: y;
            constraint float_times(x, x, y);
            solve satisfy;
        """.trimIndent(), floatBuckets = 3)

        val source = program.sourceProgram
        val attachment = program.sourceAttachment()

        assertEquals(0, source.problem.numIntVars)
        assertEquals(2, source.problem.numRealVars)
        assertIs<ContinuousProduct>(source.problem.factors.single())
        assertSame(source.problem, attachment.originalSource)
        assertFalse(assertNotNull(program.retainedSource).materialized)
        assertTrue(source.floatVarsByName.values.all { it.lpOnly })
    }

    @Test
    fun `a requested legacy view should retain its original source across ordinary copies`() {
        val program = parseFlatZinc("""
            var 0.0..1.0: x;
            var 0.0..1.0: y;
            constraint float_times(x, x, y);
            solve satisfy;
        """.trimIndent(), floatBuckets = 3)
        val source = program.sourceProgram.problem
        val legacy = program.problem

        val copied = program.copy()

        assertEquals(0, legacy.numRealVars)
        assertEquals(2, legacy.numIntVars)
        assertSame(legacy, copied.problem)
        assertSame(source, copied.sourceProgram.problem)
        assertSame(source, copied.sourceAttachment().originalSource)
        assertEquals(program.sourceProgram.floatVarsByName, copied.sourceProgram.floatVarsByName)
        assertEquals(program.sourceProgram.floatRepresentations, copied.sourceProgram.floatRepresentations)
        assertEquals(program, copied)
        assertEquals(program.hashCode(), copied.hashCode())
        assertEquals(program.toString(), copied.toString())
        assertSame(legacy, copied.component1())
        assertEquals(program.definitionHints, copied.component13())
    }

    @Test
    fun `a foreign problem copy should drop its original float correspondence`() {
        val program = parseFlatZinc("""
            var 0.0..1.0: x;
            var 0.0..1.0: y;
            constraint float_times(x, x, y);
            solve satisfy;
        """.trimIndent(), floatBuckets = 3)
        val foreign = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)), emptyArray())

        val copied = program.copy(problem = foreign)

        assertNull(copied.retainedSource)
        assertSame(copied, copied.sourceProgram)
        assertSame(foreign, copied.sourceAttachment().originalSource)
    }

    @Test
    fun `an explicit finite compatibility helper should bind its actual private problem to the original source`() {
        val execution = parseFlatZincExecution("""
            var 0.0..1.0: x;
            var 0.0..1.0: y;
            constraint float_times(x, x, y);
            solve satisfy;
        """.trimIndent(), floatBuckets = 3)

        val invocation = assertNotNull(execution.sourceInvocation)
        val projection = assertNotNull(invocation.floatProjection)

        assertSame(execution.program.sourceProgram.problem, invocation.source.originalSource)
        assertSame(execution.program.problem, projection.privateProblem)
        assertSame(execution.program.problem, invocation.finiteShape().problem)
        assertEquals(2, execution.program.sourceProgram.problem.numRealVars)
        assertEquals(0, execution.program.problem.numRealVars)
    }

    @Test
    fun `a stopped compatibility request should not force the unbounded legacy getter`() {
        val program = parseFlatZinc("""
            var 0.0..1.0: x;
            var 0.0..1.0: y;
            constraint float_times(x, x, y);
            solve satisfy;
        """.trimIndent(), floatBuckets = 3)

        val invocation = program.prepareFiniteCompatibility(SourceSetupRequest(program.sourceAttachment(),
            ExecutionOperation.SATISFY, FiniteEngine.LOCAL_SEARCH, cancellation = Cancellation { true }))

        assertNull(invocation.floatProjection)
        assertFalse(assertNotNull(program.retainedSource).materialized)
    }

    @Test
    fun `a bound foreign frame should not fall back to the legacy FlatZinc decoder convention`() {
        val program = parseFlatZinc("var 0.0..1.0: x; solve satisfy;", floatBuckets = 3)
        val point = Sample(booleanArrayOf(), longArrayOf(1)).withCoordinateFrame(Any())

        assertFailsWith<IllegalArgumentException> { writeFlatZincSolution(program, point) }
    }
}
