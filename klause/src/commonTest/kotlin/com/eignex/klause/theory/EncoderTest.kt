package com.eignex.klause.theory

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.BuiltInPropagationProjectionProvider
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition
import com.eignex.klause.solver.pipeline.ExactNeighborhoodResult
import com.eignex.klause.solver.pipeline.ExecutionStrategy
import com.eignex.klause.solver.pipeline.FactorOwner
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FiniteExactNeighborhood
import com.eignex.klause.solver.pipeline.IntVariableOwner
import com.eignex.klause.solver.pipeline.OpenExactNeighborhood
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.PreparedExecutionKind
import com.eignex.klause.solver.pipeline.PreparedPlanDecline
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.pipeline.polynomialRequest
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncoderTest {
    @Test
    fun `finite encoder only relations participate in polynomial completion`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)
        val source = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(
            row, RealProduct(0, 0, 1, 1.0, 1.0),
            Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 2),
        ), numRealVars = 2, realLower = doubleArrayOf(1.0, 0.0), realUpper = doubleArrayOf(1.0, 0.0))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { factor ->
            if (factor === row) null else BuiltInPropagationProjectionProvider.select(factor)
        })
        val plan = source.prepareExecutionPlan(providers)

        val result = OpenTheoryPipeline.execute(plan.polynomialRequest(), engine = FiniteEngine.BACKTRACK).result

        assertTrue(plan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        assertEquals(listOf(0, 1), assertNotNull(plan.polynomialModel).encodingAttributions
            .map { it.sourceFactorIndex })
        assertEquals(listOf(2), plan.polynomialModel!!.finiteFactorIndices)
        assertNull(plan.polynomialIntegerCoordinate(1))
        assertEquals(1, plan.finiteIntegerCoordinate(1))
        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `normal source routing admits a required finite polynomial encoder without native propagation`() {
        val relation = object : Factor by Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1) {
            override val linearForm: LinearForm? = null
        }
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(relation))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { _, target ->
            if (target != EncodingTarget.POLYNOMIAL) Encoder(EncoderSupport.UNSUPPORTED)
            else Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                encoding.polynomial(PolynomialRealFormula.Atom(encoding.integer(SourceIntId(0)),
                    PolynomialRelation.EQ, PolynomialExpression.Constant.of(bigIntOf(1))))
                null
            }
        })

        val result = BacktrackSolver.source(source).withProjectionProviders(providers).solve()

        assertEquals("1", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(0))
    }

    @Test
    fun `a required polynomial peer enforces an opaque finite filter in the same search`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)
        val relation = object : Factor by row { override val linearForm: LinearForm? = null }
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(relation,
            RealProduct(0, 0, 1, 1.0, 1.0)), numRealVars = 2,
            realLower = doubleArrayOf(1.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0))
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider { factor ->
                if (factor !== relation) BuiltInPropagationProjectionProvider.select(factor)
                else PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                    "The required arithmetic peer supplies whole-relation enforcement.") { NoPropagator }
            },
            encoder = EncoderProvider { factor, target ->
                if (factor !== relation || target != EncodingTarget.POLYNOMIAL) {
                    BuiltInEncoderProvider.select(factor, target)
                } else Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                    encoding.polynomial(PolynomialRealFormula.Atom(encoding.integer(SourceIntId(0)),
                        PolynomialRelation.EQ, PolynomialExpression.Constant.of(bigIntOf(1))))
                    null
                }
            },
        )
        val plan = source.prepareExecutionPlan(providers)

        val result = OpenTheoryPipeline.execute(plan.polynomialRequest(), engine = FiniteEngine.BACKTRACK).result

        assertTrue(plan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        assertEquals(listOf(0), plan.polynomialModel!!.finiteFactorIndices)
        assertEquals("1", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(0))
        val standalone = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(relation))
        assertIs<SolveResult.Unknown>(BacktrackSolver(standalone.bake(providers)).solve())
    }

    @Test
    fun `source polynomial work limits include actual encoder installation`() {
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0))
        for (allowance in listOf(0L, 3L)) {
            var installations = 0
            var charged = 0L
            val caller = object : Cancellation {
                override fun isCancelled(): Boolean = false
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }
            val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
                val selected = BuiltInEncoderProvider.select(factor, target)
                if (target != EncodingTarget.POLYNOMIAL) selected
                else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                    installations++
                    selected.install(view, encoding)
                }
            })

            val result = BacktrackSolver.source(source).withProjectionProviders(providers)
                .solve(PolynomialTheoryParams(maximumWork = allowance, cancellation = caller))

            assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(result).reason)
            assertEquals(if (allowance == 0L) 0 else 1, installations)
            assertEquals(allowance, charged)
        }
    }

    @Test
    fun `polynomial participation correlates finite propagation with arithmetic relations`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
            RealProduct(0, 0, 1, 1.0, 1.0),
            Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 2),
        ), numRealVars = 2, realLower = doubleArrayOf(1.0, 0.0), realUpper = doubleArrayOf(1.0, 0.0))
        val plan = source.prepareExecutionPlan()
        val shared = assertNotNull(plan.polynomialModel)

        val result = shared.service.invocation().use { it.check() }

        assertEquals(listOf(0, 2), shared.finiteFactorIndices)
        assertEquals(listOf(0, 1), shared.encodingAttributions.map { it.sourceFactorIndex })
        assertEquals(0, plan.finiteIntegerCoordinate(0))
        assertNull(plan.polynomialIntegerCoordinate(1))
        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `unavailable finite encodings cannot replace missing polynomial enforcement`() {
        for (support in listOf(EncoderSupport.UNSUPPORTED, EncoderSupport.RELAXATION_ONLY)) {
            val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)
            val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(
                row, RealProduct(0, 0, 1, 1.0, 1.0),
            ), numRealVars = 2, realLower = doubleArrayOf(1.0, 0.0), realUpper = doubleArrayOf(1.0, 0.0))
            val providers = FactorProjectionProviders(
                propagation = PropagationProjectionProvider { factor ->
                    if (factor === row) null else BuiltInPropagationProjectionProvider.select(factor)
                },
                encoder = EncoderProvider { factor, target ->
                    if (factor === row && target == EncodingTarget.POLYNOMIAL) Encoder(support)
                    else BuiltInEncoderProvider.select(factor, target)
                },
            )

            val plan = source.prepareExecutionPlan(providers)

            assertEquals(PreparedExecutionKind.DECLINED, plan.kind)
            assertFalse(plan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        }
    }

    @Test
    fun `compound absolute value encoding enforces its equality alternatives`() {
        val relation = object : Factor by Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
            doubleArrayOf(1.0, 1.0), LinearOp.EQ, 0.0) {
            override val linearForm: LinearForm? = null
        }
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(relation), numRealVars = 2,
            realLower = doubleArrayOf(-2.0, 3.0), realUpper = doubleArrayOf(-2.0, 3.0))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (factor !== relation || target != EncodingTarget.LINEAR) BuiltInEncoderProvider.select(factor, target)
            else Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                val auxiliary = encoding.auxiliary()
                encoding.define(auxiliary, EncodingAffineExpression(listOf(
                    EncodingLinearTerm.source(Term.ofRealVar(0), BigFraction.ONE),
                )))
                val result = encoding.realAuxiliary()
                encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                    EncodingLinearTerm.auxiliary(result, BigFraction.ONE),
                    EncodingLinearTerm.source(Term.ofRealVar(1), BigFraction.MINUS_ONE),
                )), LinearOp.EQ, BigFraction.ZERO))
                val positive = EncodingAffineExpression(listOf(
                    EncodingLinearTerm.auxiliary(result, BigFraction.ONE),
                    EncodingLinearTerm.auxiliary(auxiliary, BigFraction.MINUS_ONE),
                ))
                val negative = EncodingAffineExpression(listOf(
                    EncodingLinearTerm.auxiliary(result, BigFraction.ONE),
                    EncodingLinearTerm.auxiliary(auxiliary, BigFraction.ONE),
                ))
                val alternatives = encoding.guard()
                encoding.define(alternatives, LinearEncodingFormula.Or(listOf(
                    LinearEncodingFormula.Comparison(positive, LinearOp.EQ, BigFraction.ZERO),
                    LinearEncodingFormula.Comparison(negative, LinearOp.EQ, BigFraction.ZERO),
                )))
                encoding.linearFormula(LinearEncodingFormula.And(listOf(
                    LinearEncodingFormula.Comparison(positive, LinearOp.GE, BigFraction.ZERO),
                    LinearEncodingFormula.Comparison(negative, LinearOp.GE, BigFraction.ZERO),
                    LinearEncodingFormula.Guard(alternatives),
                )))
                null
            }
        })
        val prepared = source.prepareExecutionPlan(providers)
        val plan = assertNotNull(prepared.componentPlan)

        val result = plan.search(source, emptyMap()).use {
            if (it.session.initialize() is ComponentResult.Conflict) SearchResult.Exhausted
            else it.session.solve(0)
        }

        assertTrue(prepared.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        assertIs<SearchResult.Exhausted>(result)
        val installed = assertNotNull(plan.encodedTheoryFragment(source))
        assertEquals(3, installed.numRealVars)
        assertEquals(listOf(0, 0), installed.factors.map { (it as EncodedTheoryFactor).attribution.sourceFactorIndex })
        val attribution = (installed.factors.first() as EncodedTheoryFactor).attribution
        assertEquals(listOf(TheoryRealId(2)), attribution.auxiliaries)
        assertEquals(1, attribution.affineAuxiliaries.size)
        assertEquals(1, attribution.linearGuards.size)
    }

    @Test
    fun `private real completion retracts queries and publishes only source coordinates`() {
        val row = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0)
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (target != EncodingTarget.LINEAR) BuiltInEncoderProvider.select(factor, target)
            else Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                val private = encoding.realAuxiliary()
                encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                    EncodingLinearTerm.auxiliary(private, BigFraction.ONE),
                    EncodingLinearTerm.source(Term.ofRealVar(0), BigFraction.MINUS_ONE),
                )), LinearOp.EQ, BigFraction.ZERO))
                encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                    EncodingLinearTerm.auxiliary(private, BigFraction.ONE),
                )), LinearOp.EQ, BigFraction.ONE))
                null
            }
        })
        val plan = source.prepareExecutionPlan(providers)
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = plan)
        val witness = queries.use {
            assertIs<ExactNeighborhoodResult.Refuted>(it.check(ExactNeighborhoodCondition(emptyMap(),
                mapOf(0 to BigFraction.ZERO), mapOf(0 to BigFraction.ZERO)), Cancellation.Never))
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(ExactNeighborhoodCondition(emptyMap(),
                emptyMap(), emptyMap()), Cancellation { true }))
            assertIs<ExactNeighborhoodResult.Witness>(it.check(ExactNeighborhoodCondition(emptyMap(),
                mapOf(0 to BigFraction.ONE), mapOf(0 to BigFraction.ONE)), Cancellation.Never)).sample
        }

        assertEquals(listOf(BigFraction.ONE), witness.exactReals)
        assertEquals(1, witness.reals.size)
        assertEquals(1, source.numRealVars)
    }

    @Test
    fun `scoped auxiliary definitions reject missing duplicate and foreign attribution`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
        ))
        var foreign: EncodingAuxiliary? = null
        for (kind in listOf("missing", "duplicate", "foreign")) {
            val providers = FactorProjectionProviders(encoder = EncoderProvider { _, _ ->
                Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                    val auxiliary = encoding.auxiliary()
                    val definition = EncodingAffineExpression(listOf(
                        EncodingLinearTerm.source(Term.ofIntVar(0), BigFraction.ONE),
                    ))
                    if (kind == "missing") foreign = auxiliary
                    else {
                        encoding.define(auxiliary, definition)
                        encoding.define(if (kind == "duplicate") auxiliary else assertNotNull(foreign), definition)
                    }
                    encoding.linear(assertNotNull(view.linearForm))
                    null
                }
            })

            assertFailsWith<IllegalArgumentException> {
                providers.prepare(source).linearTheoryModel(EncodingTarget.LINEAR)
            }
        }
    }

    @Test
    fun `private real disequalities use scoped branches without publishing private coordinates`() {
        val row = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.NE, 0.0)
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(row), numRealVars = 1,
            realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (target != EncodingTarget.LINEAR) BuiltInEncoderProvider.select(factor, target)
            else Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                val private = encoding.realAuxiliary()
                encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                    EncodingLinearTerm.auxiliary(private, BigFraction.ONE),
                    EncodingLinearTerm.source(Term.ofRealVar(0), BigFraction.MINUS_ONE),
                )), LinearOp.EQ, BigFraction.ZERO))
                encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                    EncodingLinearTerm.auxiliary(private, BigFraction.ONE),
                )), LinearOp.NE, BigFraction.ZERO))
                null
            }
        })
        val plan = source.prepareExecutionPlan(providers)
        val request = OpenTheoryRequest(source, componentPlan = assertNotNull(plan.componentPlan), preparedPlan = plan)
            .withPresolve(PresolveConfig.NONE)

        val result = assertIs<OpenTheoryExecution.Satisfy>(OpenTheoryPipeline.execute(request)).result

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertTrue(witness.realValue(0) != "0")
        assertFailsWith<IndexOutOfBoundsException> { witness.realValue(1) }
    }

    @Test
    fun `affine auxiliary expansion preserves aliases across typed source namespaces`() {
        val row = Linear(intArrayOf(0, 0), doubleArrayOf(1.0, 2.0), intArrayOf(0), doubleArrayOf(-1.0),
            LinearOp.EQ, 3.0)
        val source = Problem(0, 1, arrayOf(IntDomain(2, 2)), arrayOf<Factor>(row), numRealVars = 1,
            realLower = doubleArrayOf(3.0), realUpper = doubleArrayOf(3.0))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (target != EncodingTarget.LINEAR) BuiltInEncoderProvider.select(factor, target)
            else Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                val private = encoding.auxiliary()
                encoding.define(private, EncodingAffineExpression(listOf(
                    EncodingLinearTerm.source(Term.ofIntVar(0), BigFraction.ONE),
                    EncodingLinearTerm.source(Term.ofIntVar(0), BigFraction.ofLong(2)),
                    EncodingLinearTerm.source(Term.ofRealVar(0), BigFraction.MINUS_ONE),
                )))
                encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                    EncodingLinearTerm.auxiliary(private, BigFraction.ONE),
                )), LinearOp.EQ, BigFraction.ofLong(3)))
                null
            }
        })
        val plan = source.prepareExecutionPlan(providers)
        val request = OpenTheoryRequest(source, componentPlan = assertNotNull(plan.componentPlan), preparedPlan = plan)
            .withPresolve(PresolveConfig.NONE)

        val result = assertIs<OpenTheoryExecution.Satisfy>(OpenTheoryPipeline.execute(request)).result

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals("2", witness.intValue(0))
        assertEquals("3", witness.realValue(0))
        assertEquals(IntVariableOwner.CP, plan.componentPlan!!.intOwner(0))
    }

    @Test
    fun `finite numerical completion checks private encodings at the original source point`() {
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (target != EncodingTarget.LINEAR) BuiltInEncoderProvider.select(factor, target)
            else Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                val private = encoding.realAuxiliary()
                encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                    EncodingLinearTerm.auxiliary(private, BigFraction.ONE),
                    EncodingLinearTerm.source(Term.ofRealVar(0), BigFraction.MINUS_ONE),
                )), LinearOp.EQ, BigFraction.ZERO))
                encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                    EncodingLinearTerm.auxiliary(private, BigFraction.ONE),
                )), LinearOp.EQ, BigFraction.ONE))
                null
            }
        })

        val result = BacktrackSolver(source.bake(providers)).solve(BacktrackParams())

        val sample = assertIs<SolveResult.Sat>(result).assignment
        assertEquals(listOf(BigFraction.ONE), sample.exactReals)
        assertEquals(1, sample.reals.size)
    }

    @Test
    fun `finite neighborhood retries a cancelled conditioned installation without leaking auxiliaries`() {
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        var interrupt = true
        var cancelled = false
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (target != EncodingTarget.LINEAR) BuiltInEncoderProvider.select(factor, target)
            else Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                val private = encoding.realAuxiliary()
                encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                    EncodingLinearTerm.auxiliary(private, BigFraction.ONE),
                    EncodingLinearTerm.source(Term.ofRealVar(0), BigFraction.MINUS_ONE),
                )), LinearOp.EQ, BigFraction.ZERO))
                encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                    EncodingLinearTerm.auxiliary(private, BigFraction.ONE),
                )), LinearOp.EQ, BigFraction.ONE))
                if (interrupt) {
                    interrupt = false
                    cancelled = true
                }
                null
            }
        })
        val fixed = Sample(BooleanArray(0), LongArray(0))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        val token = Cancellation { cancelled }
        val queries = FiniteExactNeighborhood(source.bake(providers), fixed, TheoryParams(), null) {}

        val witness = queries.use {
            assertIs<ExactNeighborhoodResult.Undecided>(it.check(condition, token))
            cancelled = false
            assertIs<ExactNeighborhoodResult.Witness>(it.check(condition, token)).sample
        }

        assertEquals(listOf(BigFraction.ONE), witness.exactReals)
        assertEquals(1, witness.reals.size)
    }

    @Test
    fun `guard definitions reject missing duplicate cyclic and foreign attribution`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
        ))
        var foreign: EncodingGuard? = null
        for (kind in listOf("missing", "duplicate", "cyclic", "foreign")) {
            val providers = FactorProjectionProviders(encoder = EncoderProvider { _, _ ->
                Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                    val guard = encoding.guard()
                    val definition = LinearEncodingFormula.Relation(assertNotNull(view.linearForm))
                    when (kind) {
                        "missing" -> foreign = guard
                        "cyclic" -> encoding.define(guard, LinearEncodingFormula.Guard(guard))
                        "duplicate" -> {
                            encoding.define(guard, definition)
                            encoding.define(guard, definition)
                        }
                        else -> encoding.define(assertNotNull(foreign), definition)
                    }
                    encoding.linearFormula(LinearEncodingFormula.Guard(guard))
                    null
                }
            })

            assertFailsWith<IllegalArgumentException> {
                providers.prepare(source).linearTheoryModel(EncodingTarget.LINEAR)
            }
        }
    }

    @Test
    fun `actual preparation discards construction cancelled inside a selected installer`() {
        val source = assertIs<SmtLibInput.Linear>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= x 1))",
        )).problem.model
        var cancelled = false
        var entered = 0
        val token = Cancellation { cancelled }
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (!selected.enforcesSource) selected else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                entered++
                encoding.linear(assertNotNull(view.linearForm))
                cancelled = true
                assertTrue(encoding.cancellation())
                null
            }
        })

        val plan = source.prepareExecutionPlan(providers, cancellation = token)

        assertEquals(1, entered)
        assertEquals(PreparedPlanDecline.RESOURCE, plan.decline)
        assertEquals(PreparedExecutionKind.DECLINED, plan.kind)
        assertFalse(plan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
    }

    @Test
    fun `normal polynomial routing exposes the callers work meter to installation`() {
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        var spent = 0L
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= 4
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (target != EncodingTarget.POLYNOMIAL) selected
            else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                val result = selected.install(view, encoding)
                encoding.cancellation.charge(4)
                result
            }
        })

        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(source.pipelineRoute(providers,
            boundCancellation = token))
        val retry = source.prepareExecutionPlan(providers)

        assertTrue(spent >= 4)
        assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
        assertFalse(route.preparedPlan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        assertEquals(PreparedExecutionKind.POLYNOMIAL, retry.kind)
    }

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
    fun `polynomial installations reject auxiliaries borrowed from another factor scope`() {
        val first = ContinuousProduct(0, 0, 0)
        val second = ContinuousProduct(1, 1, 1)
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(first, second), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        var borrowed: PolynomialExpression? = null
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (target != EncodingTarget.POLYNOMIAL) selected
            else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                if (view === first) {
                    val value = encoding.real(SourceRealId(0))
                    borrowed = encoding.conditional(PolynomialRealFormula.Atom(value, PolynomialRelation.GE,
                        PolynomialExpression.Constant.of(bigIntOf(0))), value, value)
                    selected.install(view, encoding)
                } else {
                    encoding.polynomial(PolynomialRealFormula.Atom(assertNotNull(borrowed), PolynomialRelation.EQ,
                        encoding.real(SourceRealId(1))))
                    null
                }
            }
        })

        assertFailsWith<IllegalArgumentException> { source.prepareExecutionPlan(providers) }
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

        val result = ownership.search(source, emptyMap()).use {
            if (it.session.initialize() is ComponentResult.Conflict) SearchResult.Exhausted
            else it.session.solve(source.numBoolVars)
        }

        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, ownership.theoryPipeline)
        assertIs<SearchResult.Exhausted>(result)
    }

    @Test
    fun `selected difference relations compose beside an unencoded finite global`() {
        val open = Bits(3).also { it.set(2) }
        val source = Problem(0, IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(1, 1, 0),
            null, open), arrayOf<Factor>(
            AllDifferent(intArrayOf(0, 1), 0, 2),
            Linear(intArrayOf(1, -1), intArrayOf(2, 0), LinearOp.EQ, 1),
        ))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (target == EncodingTarget.DIFFERENCE) BuiltInEncoderProvider.select(factor, target)
            else Encoder(EncoderSupport.UNSUPPORTED)
        })
        val prepared = source.prepareExecutionPlan(providers)
        val plan = assertNotNull(prepared.componentPlan)

        val result = plan.search(source, plan.cpSourceDomains(source)).use {
            assertIs<ComponentResult.Consistent>(it.session.initialize())
            it.session.solve(source.numBoolVars)
        }

        assertTrue(prepared.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, plan.theoryPipeline)
        assertEquals(FactorOwner.CP, plan.factorOwner(0))
        assertFalse(plan.factorEncoded(0))
        assertEquals(IntVariableOwner.CP, plan.intOwner(0))
        assertIs<SearchResult.Satisfied>(result)
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

        val result = plan.search(source, plan.cpSourceDomains(source)).use {
            if (it.session.initialize() is ComponentResult.Conflict) SearchResult.Exhausted
            else it.session.solve(source.numBoolVars)
        }

        assertIs<SearchResult.Exhausted>(result)
        assertEquals(listOf(IntVariableOwner.CP, IntVariableOwner.CP), (0..1).map(plan::intOwner))
        assertEquals(listOf(FactorOwner.CP, FactorOwner.CP), (0..1).map(plan::factorOwner))
        assertTrue((0..1).all(plan::factorEncoded))
    }

    @Test
    fun `an enforcing encoder peer completes finite filtering without a duplicate source checker`() {
        val relation = object : Factor by Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1) {
            override fun remap(mapping: VarRemap): Factor {
                require(mapping.int(0) == 0)
                return this
            }
        }
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(relation))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "The mandatory arithmetic peer enforces the complete source relation.") { NoPropagator }
        })
        val prepared = source.prepareExecutionPlan(providers)
        val plan = assertNotNull(prepared.componentPlan)

        val result = plan.search(source, plan.cpSourceDomains(source)).use {
            assertIs<ComponentResult.Consistent>(it.session.initialize())
            it.session.solve(source.numBoolVars)
        }

        assertTrue(prepared.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        assertEquals(FactorOwner.CP, plan.factorOwner(0))
        assertTrue(plan.factorEncoded(0))
        assertIs<SearchResult.Satisfied>(result)
    }

    @Test
    fun `bounded fractional rows admit their retained finite and arithmetic peers`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(
            ReifiedRealLinear(0, intArrayOf(0), doubleArrayOf(0.5), intArrayOf(), doubleArrayOf(),
                LinearOp.GE, 0.5),
            ReifiedRealLinear(0, intArrayOf(0), doubleArrayOf(0.5), intArrayOf(), doubleArrayOf(),
                LinearOp.LE, 0.5),
        ))
        val prepared = source.prepareExecutionPlan()
        val plan = assertNotNull(prepared.componentPlan)

        val result = plan.search(source, plan.cpSourceDomains(source)).use {
            assertIs<ComponentResult.Consistent>(it.session.initialize())
            it.session.solve(source.numBoolVars)
        }

        assertTrue(prepared.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        assertEquals(IntVariableOwner.CP, plan.intOwner(0))
        assertEquals(FactorOwner.CP, plan.factorOwner(0))
        assertTrue(plan.factorEncoded(0))
        val model = assertIs<SearchResult.Satisfied>(result).model
        assertEquals(1L, model.valueOf<Long>(com.eignex.klause.solver.search.SearchIntValue(0)))
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

        val result = plan.search(source, plan.cpSourceDomains(source)).use {
            if (it.session.initialize() is ComponentResult.Conflict) SearchResult.Exhausted
            else it.session.solve(source.numBoolVars)
        }

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

    @Test
    fun `a Boolean relation installed in the difference component is fully enforced`() {
        val input = assertIs<SmtLibInput.Linear>(SmtLibAdmission.parse("(declare-const b Bool) (assert b)"))
        val source = input.problem.model
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { null })
        val plan = assertNotNull(source.prepareExecutionPlan(providers).componentPlan)

        val result = plan.search(source, emptyMap()).use {
            assertIs<ComponentResult.Consistent>(it.session.initialize())
            it.session.solve(source.numBoolVars)
        }

        val model = assertIs<SearchResult.Satisfied>(result).model
        assertEquals(true, model.valueOf<Boolean>(SearchBoolValue(0)))
    }

    @Test
    fun `a resource decline discards an emitted relation before source admission`() {
        val input = assertIs<SmtLibInput.Linear>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= x 1))",
        ))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (!selected.enforcesSource) selected else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                selected.install(view, encoding)
                EncoderDecline.RESOURCE
            }
        })

        val plan = input.problem.model.prepareExecutionPlan(providers)

        assertFalse(plan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
    }

}
