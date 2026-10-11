package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.compile.compile
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class SharedPolynomialModelTest {
    @Test
    fun `compiled continuous products select shared exact completion`() {
        val x = FloatHandle("x", -2.0, 2.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-2.0, 2.0, 5),
            "square" to NamedConstraint((x * x) eq 2.0),
        )).compile()
        val route = assertIs<SourceProblemRoute.PolynomialTheory>(compiled.problem.pipelineRoute())

        val execution = OpenTheoryPipeline.execute(route.request, engine = FiniteEngine.BACKTRACK)

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
        val real = assertNotNull(witness.algebraicRealValue(compiled.realVarIdByName.getValue("x")))
        assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), real))
    }
}
