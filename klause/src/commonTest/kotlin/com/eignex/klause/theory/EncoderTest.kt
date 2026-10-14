package com.eignex.klause.theory

import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.ExecutionStrategy
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PreparedExecutionKind
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.pipeline.polynomialRequest
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncoderTest {
    @Test
    fun `selected linear encoders detect correlated source contradiction`() {
        val input = assertIs<SmtLibInput.Linear>(SmtLibAdmission.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (<= x y)) (assert (< y x))",
        ))
        var installations = 0
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (!selected.enforcesSource) selected else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                installations++
                selected.install(view, encoding)
            }
        })
        val model = input.problem.model
        val plan = model.prepareExecutionPlan(providers)
        val request = OpenTheoryRequest(model, componentPlan = assertNotNull(plan.componentPlan), preparedPlan = plan)
            .withPresolve(PresolveConfig.NONE)

        val result = assertIs<OpenTheoryExecution.Satisfy>(OpenTheoryPipeline.execute(request)).result

        assertIs<OpenTheoryResult.Unsat>(result)
        assertTrue(installations >= model.numFactors)
    }

    @Test
    fun `relaxation only encoders cannot authorize open source enforcement`() {
        val input = assertIs<SmtLibInput.Linear>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (> x 0))",
        ))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { _, _ ->
            Encoder(EncoderSupport.RELAXATION_ONLY)
        })

        val admission = input.problem.model.prepareExecutionPlan(providers).capabilities
            .admission(ExecutionStrategy.COMPLETE)

        assertFalse(admission.runnable)
    }

    @Test
    fun `conditioned linear product encoding retains each captured integer value`() {
        val product = RealProduct(0, 0, 1, -2.0, 2.0)
        val source = Problem(0, 1, arrayOf(IntDomain(-1, 1)), arrayOf<Factor>(product), numRealVars = 2,
            realLower = doubleArrayOf(-2.0, -2.0), realUpper = doubleArrayOf(2.0, 2.0))
        val prepared = FactorProjectionProviders.Default.prepare(source)
        val id = SourceIntId(0)

        val first = assertNotNull(prepared.linearTheoryModel(EncodingTarget.LINEAR,
            fixedIntegers = mapOf(id to bigIntOf(-1))))
        val second = assertNotNull(prepared.linearTheoryModel(EncodingTarget.LINEAR,
            fixedIntegers = mapOf(id to bigIntOf(1))))

        assertEquals(EncoderSupport.CONDITIONED, prepared.encoder(0, EncodingTarget.LINEAR).support)
        assertEquals(bigIntOf(1), (first.factors.single().linearForm!!.rows.single().constants as IntegralConstants)
            .exactCoeff(1))
        assertEquals(bigIntOf(-1), (second.factors.single().linearForm!!.rows.single().constants as IntegralConstants)
            .exactCoeff(1))
        assertNull(prepared.linearTheoryModel(EncodingTarget.LINEAR))
        assertNull(product.linearForm)
    }

    @Test
    fun `an interrupted installation publishes no incomplete theory fragment`() {
        val input = assertIs<SmtLibInput.Linear>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= x 1))",
        ))
        val source = input.problem.model
        val prepared = FactorProjectionProviders.Default.prepare(source)

        val fragment = prepared.linearTheoryModel(EncodingTarget.LINEAR, cancellation = Cancellation { true })

        assertNull(fragment)
    }

    @Test
    fun `compiled polynomial factors execute through a replaced provider`() {
        val x = FloatHandle("x", -1.0, 1.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-1.0, 1.0, 3), "square" to NamedConstraint((x * x) eq 0.0),
        )).compile()
        var products = 0
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (factor !is ContinuousProduct || target != EncodingTarget.POLYNOMIAL) selected
            else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                products++
                selected.install(view, encoding)
            }
        })
        val plan = compiled.problem.prepareExecutionPlan(providers)

        val result = OpenTheoryPipeline.execute(plan.polynomialRequest()).result

        assertEquals(PreparedExecutionKind.POLYNOMIAL, plan.kind)
        assertIs<OpenTheoryResult.Sat>(result)
        assertTrue(products > 0)
    }

    @Test
    fun `selected unsupported polynomial encoders decline the existing source route`() {
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (target == EncodingTarget.POLYNOMIAL) Encoder(EncoderSupport.UNSUPPORTED)
            else BuiltInEncoderProvider.select(factor, target)
        })

        val plan = source.prepareExecutionPlan(providers)

        assertEquals(PreparedExecutionKind.DECLINED, plan.kind)
        assertFalse(plan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
    }
}
