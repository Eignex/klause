package com.eignex.klause.theory

import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.FactorOwner
import com.eignex.klause.solver.pipeline.IntVariableOwner
import com.eignex.klause.solver.pipeline.ExecutionStrategy
import com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition
import com.eignex.klause.solver.pipeline.ExactNeighborhoodResult
import com.eignex.klause.solver.pipeline.OpenExactNeighborhood
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PreparedExecutionKind
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.search
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
    @Test
    fun `guarded provider installation retracts a refuted query before its sibling`() {
        val input = assertIs<SmtLibInput.Linear>(SmtLibAdmission.parse(
            "(declare-const b Bool) (declare-const x Real) " +
                "(assert (or (not b) (>= x 1))) (assert (= x 0))",
        ))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (!selected.enforcesSource) selected else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                selected.install(view, encoding)
            }
        })
        val source = input.problem.model
        val plan = source.prepareExecutionPlan(providers)
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = plan)

        queries.use {
            val refuted = it.check(ExactNeighborhoodCondition(mapOf(0 to true), emptyMap(), emptyMap()),
                Cancellation.Never)
            val sibling = it.check(ExactNeighborhoodCondition(mapOf(0 to false), emptyMap(), emptyMap()),
                Cancellation.Never)

            assertIs<ExactNeighborhoodResult.Refuted>(refuted)
            assertFalse(assertIs<ExactNeighborhoodResult.Witness>(sibling).sample.boolValue(0))
        }
    }

    @Test
    fun `difference provider replacement combines factors in one graph`() {
        val input = assertIs<SmtLibInput.Linear>(SmtLibAdmission.parse(
            "(declare-const x Int) (declare-const y Int) " +
                "(assert (<= (- x y) 0)) (assert (<= (- y x) (- 1)))",
        ))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (target != EncodingTarget.DIFFERENCE) Encoder(EncoderSupport.UNSUPPORTED)
            else BuiltInEncoderProvider.select(factor, target)
        })
        val source = input.problem.model
        val plan = source.prepareExecutionPlan(providers)
        val ownership = assertNotNull(plan.componentPlan)

        val result = ownership.search(source, emptyMap()).use { it.session.solve(source.numBoolVars) }

        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, ownership.theoryPipeline)
        assertIs<SearchResult.Exhausted>(result)
    }

    @Test
    fun `bounded linear propagation and encoding retain one finite choice owner`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, -1),
        ))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "The selected arithmetic peer and original point check enforce the relation.") { NoPropagator }
        })
        val plan = assertNotNull(source.prepareExecutionPlan(providers).componentPlan)

        val result = plan.search(source, plan.cpSourceDomains(source)).use { it.session.solve(source.numBoolVars) }

        assertIs<SearchResult.Exhausted>(result)
        assertEquals(listOf(IntVariableOwner.CP, IntVariableOwner.CP), (0..1).map(plan::intOwner))
        assertEquals(listOf(FactorOwner.CP, FactorOwner.CP), (0..1).map(plan::factorOwner))
        assertTrue((0..1).all(plan::factorEncoded))
    }

    @Test
    fun `real completion respects holes beside a retained finite global`() {
        val holes = assertNotNull(IntDomain(0, 2).excludeValues(longArrayOf(1)))
        val source = Problem(0, 2, arrayOf(holes, IntDomain(0, 2)), arrayOf<Factor>(
            AllDifferent(intArrayOf(0, 1), 0, 3),
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val plan = assertNotNull(source.prepareExecutionPlan().componentPlan)

        val result = plan.search(source, plan.cpSourceDomains(source)).use { it.session.solve(source.numBoolVars) }

        assertIs<SearchResult.Exhausted>(result)
        assertEquals(IntVariableOwner.CP, plan.intOwner(1))
        assertEquals(FactorOwner.CP, plan.factorOwner(0))
        assertFalse(plan.factorEncoded(0))
        assertEquals(holes, source.intDomainOrNull(0))
    }

    @Test
    fun `decimal MPS source checks retain finite integer ownership`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E LINK\n E FIX\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                " X LINK 0.1\n M1 'MARKER' 'INTEND'\n R LINK -0.3\n R FIX 1\n" +
                "RHS\n B LINK 0\n B FIX 1\nBOUNDS\n LI B X 0\n UI B X 4\n FR B R\nENDATA",
        ).toExactSourceProblem()
        val source = compiled.model
        val plan = source.prepareExecutionPlan()
        val ownership = assertNotNull(plan.componentPlan)
        val request = OpenTheoryRequest(source, componentPlan = ownership, preparedPlan = plan)
            .withPresolve(PresolveConfig.NONE)

        val result = assertIs<OpenTheoryExecution.Satisfy>(OpenTheoryPipeline.execute(request)).result

        assertEquals("3", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(0))
        assertEquals(IntVariableOwner.CP, ownership.intOwner(0))
        assertEquals(listOf(BigFraction.ofLong(3), BigFraction.ONE),
            compiled.sourceWitness(listOf(BigFraction.ofLong(3), BigFraction.ONE)).values)
    }

}
