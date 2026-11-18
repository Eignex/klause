package com.eignex.klause.formats.flatzinc

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class FlatZincSourceStateTest {
    @Test
    fun `float metadata and attached execution should retain the original coordinates`() {
        val program = parseFlatZinc("var 0.0..1.0: x; var 0.0..1.0: y; " +
            "constraint float_times(x,x,y); constraint float_eq(x,0.25); solve satisfy;")
        val attachment = program.sourceAttachment()
        val invocation = program.prepareSourceExecution(SourceSetupRequest(attachment,
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))

        assertSame(program.problem, attachment.originalSource)
        assertSame(program.problem, invocation.source.originalSource)
        assertEquals(0, program.problem.numIntVars)
        assertEquals(2, program.problem.numRealVars)
        assertEquals(0, program.floatVarsByName.getValue("x").varId)
        assertEquals(1, program.floatVarsByName.getValue("y").varId)
    }

    @Test
    fun `an unranged real should retain both open endpoints`() {
        val program = parseFlatZinc("var float: x; solve minimize x;")

        assertEquals(Double.NEGATIVE_INFINITY, program.problem.realLower.single())
        assertEquals(Double.POSITIVE_INFINITY, program.problem.realUpper.single())
        assertEquals(0, program.problem.numIntVars)
    }

    @Test
    fun `a copied program should attach its supplied model`() {
        val program = parseFlatZinc("var 0.0..1.0: x; solve satisfy;")
        val replacement = Problem(0, 0, emptyArray(), emptyArray())
        val copied = program.copy(problem = replacement)

        assertSame(replacement, copied.sourceAttachment().originalSource)
        assertSame(copied, copied.sourceProgram)
    }

    @Test
    fun `solution rendering should reject a foreign coordinate frame`() {
        val program = parseFlatZinc("var 0.0..1.0: x; solve satisfy;")
        val other = parseFlatZinc("var 0.0..1.0: x; solve satisfy;")
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.25))
            .withCoordinateFrame(other.problem)

        assertFailsWith<IllegalArgumentException> { writeFlatZincSolution(program, sample) }
    }
}
