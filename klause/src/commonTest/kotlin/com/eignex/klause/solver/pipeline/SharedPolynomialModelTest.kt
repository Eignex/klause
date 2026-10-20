package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.Term
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.PbOp
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedPolynomialModelTest {
    @Test
    fun `an ineligible polynomial scope should install no supported prefix in either source order`() {
        for (support in listOf(EncoderSupport.UNSUPPORTED, EncoderSupport.RELAXATION_ONLY,
            EncoderSupport.CONDITIONED)) {
            for (reverse in listOf(false, true)) {
                val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                    LinearOp.GE, BigFraction.ZERO)
                val excluded = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                    LinearOp.LE, BigFraction.ONE)
                val factors = if (reverse) arrayOf<Factor>(excluded, row) else arrayOf<Factor>(row, excluded)
                val source = Problem(1, 0, emptyArray(), factors, numRealVars = 1,
                    realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
                var installed = 0
                var decline: EncoderDecline? = null
                val projections = FactorProjectionProviders(encoder = EncoderProvider { factor, _ ->
                    if (factor === excluded) Encoder(support, requiredBooleans =
                        if (support == EncoderSupport.CONDITIONED) mapOf(SourceBoolId(0) to true) else emptyMap())
                    else Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                        installed++
                        encoding.polynomial(PolynomialRealFormula.Constant(true))
                        null
                    }
                }).prepare(source)

                val model = SharedPolynomialModel.lower(source, null, projections, Cancellation.Never) { decline = it }

                assertNull(model)
                assertEquals(if (support == EncoderSupport.CONDITIONED) EncoderDecline.CONDITIONS
                    else EncoderDecline.UNSUPPORTED, decline)
                assertEquals(0, installed)
            }
        }
    }

    @Test
    fun `polynomial preflight exhaustion should retain spent work without installing a prefix`() {
        val source = Problem(0, 0, emptyArray(), Array<Factor>(3) {
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.GE, BigFraction.ZERO)
        }, numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        var installed = 0
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 2
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val projections = FactorProjectionProviders(encoder = EncoderProvider { _, _ ->
            Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                installed++
                encoding.polynomial(PolynomialRealFormula.Constant(true))
                null
            }
        }).prepare(source)

        val plan = source.preparePolynomialExecutionPlan(projections = projections, cancellation = stop)

        assertNull(plan.polynomialModel)
        assertEquals(PreparedPlanDecline.RESOURCE, plan.decline)
        assertEquals(0, installed)
        assertTrue(charged >= 2)
    }

    @Test
    fun `a polynomial installer observing a transient stop should withhold its completed emissions`() {
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.GE, BigFraction.ZERO)
        val source = Problem(0, 0, emptyArray(), arrayOf(row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        var stopNext = false
        val stop = Cancellation { stopNext.also { stopNext = false } }
        val projections = FactorProjectionProviders(encoder = EncoderProvider { _, _ ->
            Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                encoding.polynomial(PolynomialRealFormula.Constant(true))
                stopNext = true
                encoding.cancellation()
                null
            }
        }).prepare(source)

        val plan = source.preparePolynomialExecutionPlan(projections = projections, cancellation = stop)

        assertNull(plan.polynomialModel)
        assertEquals(PreparedPlanDecline.RESOURCE, plan.decline)
    }

    @Test
    fun `an admitted polynomial scope should still honor actual installer declines`() {
        val source = Problem(0, 0, emptyArray(), Array<Factor>(2) {
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.GE, BigFraction.ZERO)
        }, numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        var installed = 0
        var decline: EncoderDecline? = null
        val projections = FactorProjectionProviders(encoder = EncoderProvider { _, _ ->
            Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                installed++
                encoding.polynomial(PolynomialRealFormula.Constant(true))
                EncoderDecline.UNSUPPORTED.takeIf { encoding.sourceFactorIndex == 1 }
            }
        }).prepare(source)

        val model = SharedPolynomialModel.lower(source, null, projections, Cancellation.Never) { decline = it }

        assertNull(model)
        assertEquals(EncoderDecline.UNSUPPORTED, decline)
        assertEquals(2, installed)
    }

    @Test
    fun `a polynomial installer emitting no relation should not produce a model`() {
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.GE, BigFraction.ZERO)
        val source = Problem(0, 0, emptyArray(), arrayOf(row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        var decline: EncoderDecline? = null
        val projections = FactorProjectionProviders(encoder = EncoderProvider { _, _ ->
            Encoder(EncoderSupport.EQUIVALENT) { _, _ -> null }
        }).prepare(source)

        val model = SharedPolynomialModel.lower(source, null, projections, Cancellation.Never) { decline = it }

        assertNull(model)
        assertEquals(EncoderDecline.UNSUPPORTED, decline)
    }

    @Test
    fun `a polynomial receiver should reject another source capture before installing`() {
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.GE, BigFraction.ZERO)
        val source = Problem(0, 0, emptyArray(), arrayOf(row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val foreign = Problem(0, 0, emptyArray(), arrayOf(row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        var installed = 0
        val projections = FactorProjectionProviders(encoder = EncoderProvider { _, _ ->
            Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                installed++
                encoding.polynomial(PolynomialRealFormula.Constant(true))
                null
            }
        }).prepare(foreign)

        assertFailsWith<IllegalArgumentException> {
            SharedPolynomialModel.lower(source, null, projections, Cancellation.Never) { }
        }
        assertEquals(0, installed)
    }

    @Test
    fun `a polynomial source formula should retain its empty factor scope`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())

        val model = assertNotNull(source.preparePolynomialExecutionPlan(
            PolynomialRealFormula.Constant(false)).polynomialModel)
        val result = model.service.invocation().use { it.check(PolynomialCondition()) }

        assertSame(source, model.source)
        assertEquals(emptyList(), model.encodingAttributions)
        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `compiled continuous products should select shared exact completion`() {
        val x = FloatHandle("x", -2.0, 2.0)
        val compiled = SchemaDef<SchemaEntry>(
            mapOf(
                "x" to FloatSpec(-2.0, 2.0, 5),
                "square" to NamedConstraint((x * x) eq 2.0),
            ),
        ).compile()
        val route = assertIs<SourceProblemRoute.PolynomialTheory>(compiled.problem.pipelineRoute())

        val execution = OpenTheoryPipeline.execute(route.request, engine = FiniteEngine.BACKTRACK)

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
        val real = assertNotNull(witness.algebraicRealValue(compiled.realVarIdByName.getValue("x")))
        assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), real))
    }

    @Test
    fun `shared Boolean indicators remain attributed to every using factor`() {
        for (literal in listOf(0, 1)) {
            val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(
                ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                    LinearOp.GE, BigFraction.ZERO, activator = 0),
                PseudoBoolean(longArrayOf(1), intArrayOf(literal), PbOp.GE, 1),
                PseudoBoolean(longArrayOf(1), intArrayOf(literal), PbOp.GE, 0),
            ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))

            val model = assertNotNull(source.preparePolynomialExecutionPlan().polynomialModel)

            assertEquals(2, model.service.realCount)
            for (index in listOf(1, 2)) {
                val attribution = model.encodingAttributions[index]
                assertEquals(index, attribution.sourceFactorIndex)
                assertEquals(listOf(SourceBoolId(0)), attribution.booleans)
                assertEquals(1, attribution.auxiliaries.single().index)
            }
            assertSame(source, model.source)
            assertEquals(1, source.numBoolVars)
            assertEquals(1, source.numRealVars)
        }
    }

    @Test
    fun `shared indicator definitions enforce each conditioned literal truth`() {
        for (literal in listOf(0, 1)) {
            val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(
                ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                    LinearOp.GE, BigFraction.ZERO, activator = 0),
                PseudoBoolean(longArrayOf(1), intArrayOf(literal), PbOp.GE, 1),
                PseudoBoolean(longArrayOf(1), intArrayOf(literal), PbOp.GE, 0),
            ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
            val model = assertNotNull(source.preparePolynomialExecutionPlan().polynomialModel)
            val required = literal == 0

            model.service.invocation().use { invocation ->
                val accepted = invocation.check(PolynomialCondition(bools = mapOf(0 to required)))
                val rejected = invocation.check(PolynomialCondition(bools = mapOf(0 to !required)))

                assertEquals(required, assertIs<QfNraResult.Satisfiable>(accepted).assignment.boolValue(0))
                assertIs<QfNraResult.Unsatisfiable>(rejected)
            }
        }
    }

    @Test
    fun `opposite Boolean literals retain independent indicator guards`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.GE, BigFraction.ZERO, activator = 0),
            PseudoBoolean(longArrayOf(1, 1), intArrayOf(0, 1), PbOp.EQ, 1),
            PseudoBoolean(longArrayOf(1), intArrayOf(0), PbOp.GE, 0),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))

        val model = assertNotNull(source.preparePolynomialExecutionPlan().polynomialModel)
        val indicators = model.encodingAttributions[1].auxiliaries

        assertEquals(3, model.service.realCount)
        assertEquals(2, indicators.map { it.index }.toSet().size)
        assertEquals(indicators.first(), model.encodingAttributions[2].auxiliaries.single())
        model.service.invocation().use { invocation ->
            for (value in listOf(false, true)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(0 to value)))
                assertEquals(value, assertIs<QfNraResult.Satisfiable>(result).assignment.boolValue(0))
            }
        }
    }

    @Test
    fun `combined captures retain independent indicator coordinate ownership`() {
        val models = listOf(1, 3).map { realCount ->
            val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(
                ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                    LinearOp.GE, BigFraction.ZERO, activator = 0),
                PseudoBoolean(longArrayOf(1), intArrayOf(0), PbOp.GE, 1),
                PseudoBoolean(longArrayOf(1), intArrayOf(0), PbOp.GE, 0),
            ), numRealVars = realCount, realLower = DoubleArray(realCount) { -1.0 },
                realUpper = DoubleArray(realCount) { 1.0 })

            val model = assertNotNull(source.preparePolynomialExecutionPlan().polynomialModel)

            assertEquals(realCount + 1, model.service.realCount)
            assertEquals(realCount, model.encodingAttributions[1].auxiliaries.single().index)
            assertEquals(realCount, model.encodingAttributions[2].auxiliaries.single().index)
            model
        }

        for (model in models) {
            val result = model.service.invocation().use { it.check(PolynomialCondition(bools = mapOf(0 to true))) }
            assertIs<QfNraResult.Satisfiable>(result)
        }
        assertEquals(1, models.first().encodingAttributions[1].auxiliaries.single().index)
    }
}
