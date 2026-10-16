package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.Cancellation
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
