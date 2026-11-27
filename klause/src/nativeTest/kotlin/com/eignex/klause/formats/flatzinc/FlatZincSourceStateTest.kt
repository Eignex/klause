package com.eignex.klause.formats.flatzinc

import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class FlatZincSourceStateTest {
    @Test
    @Suppress("FunctionNaming")
    internal fun `exact optimization should refute a rounded image outside its declared bound`() {
        val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())
        val program = parseFlatZinc(ROUNDED_PRODUCT, semanticOverrides = exact)

        val result = optimize(program)

        if (result !is SourceExecutionResult.Refuted) {
            assertIs<QuadraticOptimizationResult.Infeasible>(
                assertIs<SourceExecutionResult.Quadratic>(result).result.result,
            )
        }
    }

    @Test
    @Suppress("FunctionNaming")
    internal fun `default tolerance should publish a witness for the rounded product`() {
        val program = parseFlatZinc(ROUNDED_PRODUCT)

        val result = assertIs<SourceExecutionResult.Quadratic>(optimize(program))
        val witness = assertNotNull(result.result.acceptedIncumbent).incumbent.sample
        val output = writeFlatZincSolution(program, witness)

        assertTrue("W = 200;" in output.lineSequence(), output)
    }

    private fun optimize(program: FlatZincProgram): SourceExecutionResult {
        val invocation = program.prepareSourceExecution(
            SourceSetupRequest(
                program.sourceAttachment(),
                ExecutionOperation.OPTIMIZE,
                FiniteEngine.FIXED,
                objective = assertNotNull(program.linearObjective()),
            ),
        )
        return SourceSetup.execute(SourceExecutionRequest(invocation))
    }

    private companion object {
        val ROUNDED_PRODUCT = """
            var {50,100,200}: W:: output_var;
            var 0.1591549430918953..3.978873577297384: V:: output_var;
            var 50.0..200.0: Wf;
            var 0.5..2.0: A;
            var 0.3183098861837907..1.989436788648692: B:: output_var;
            constraint float_le(2.0,V);
            constraint float_times(A,B,V);
            constraint int2float(W,Wf);
            constraint float_lin_eq([0.01,-1.0],[Wf,A],-0.0);
            solve minimize V;
        """.trimIndent()
    }
}
