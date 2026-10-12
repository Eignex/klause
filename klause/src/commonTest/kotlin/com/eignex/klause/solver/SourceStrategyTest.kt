package com.eignex.klause.solver

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.algebraicRealValue
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SourceStrategyTest {
    @Test
    fun `compiled squares preserve exact original coordinates through both strategies`() {
        val x = FloatHandle("x", -2.0, 2.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-2.0, 2.0, 5),
            "square" to NamedConstraint((x * x) eq 2.0),
        )).compile()
        val strategies = listOf(BacktrackSolver.source(compiled), LocalSearchSolver.source(compiled))

        for (strategy in strategies) {
            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve()).assignment
            val exact = assertNotNull(witness.algebraicRealValue(compiled.realVarIdByName.getValue("x")))

            assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), exact))
        }
    }

    @Test
    fun `schema chains preserve their shared exact product coordinates`() {
        val x = FloatHandle("x", -2.0, 2.0)
        val schema = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-2.0, 2.0, 5),
            "cube" to NamedConstraint((x * x * x) eq x),
        ))

        for (strategy in listOf(BacktrackSolver.source(schema), LocalSearchSolver.source(schema))) {
            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve()).assignment
            val exact = assertNotNull(witness.algebraicRealValue(0))

            assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(0, -1, 0, 1), exact))
        }
    }

    @Test
    fun `finite globals remain in the source beside exact continuous completion`() {
        val source = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(
                Table(intArrayOf(0, 1), longArrayOf(0, 1, 1, 0)),
                ContinuousProduct(0, 0, 1),
            ),
            numRealVars = 2, realLower = doubleArrayOf(-2.0, 2.0), realUpper = doubleArrayOf(2.0, 2.0),
        )

        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve()).assignment
            val first = witness.intValue(0).toLong()
            val second = witness.intValue(1).toLong()
            val exact = assertNotNull(witness.algebraicRealValue(0))

            assertEquals(1L, first + second)
            assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), exact))
        }
    }

    @Test
    fun `finite strategy controls retain original Boolean clauses`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))))

        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve()).assignment

            assertEquals(true, witness.boolValue(0))
        }
    }

}
