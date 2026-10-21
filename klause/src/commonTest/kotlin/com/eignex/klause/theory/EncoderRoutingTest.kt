package com.eignex.klause.theory

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.BuiltInScoringProjectionProvider
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.localsearch.ScoringProjection
import com.eignex.klause.localsearch.ScoringProjectionProvider
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
import com.eignex.klause.solver.ScoringCapability
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.ExecutionStrategy
import com.eignex.klause.solver.pipeline.FactorOwner
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.IntVariableOwner
import com.eignex.klause.solver.pipeline.OpenPortfolio
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
import com.eignex.klause.solver.pipeline.admission
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.linearNeighborhoodInvocation
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.pipeline.polynomialRequest
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.toLongExact
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncoderRoutingTest {
    @Test
    fun `finite encoder only relations should participate in polynomial completion`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)
        val source = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 2) },
            arrayOf<Factor>(
                row,
                RealProduct(0, 0, 1, 1.0, 1.0),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 2),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 0.0),
            realUpper = doubleArrayOf(1.0, 0.0),
        )
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider { factor ->
                if (factor === row) null else BuiltInPropagationProjectionProvider.select(factor)
            },
        )
        val plan = source.prepareExecutionPlan(providers)

        val result = OpenTheoryPipeline.execute(plan.polynomialRequest(), engine = FiniteEngine.BACKTRACK).result

        assertTrue(plan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        assertEquals(
            listOf(0, 1),
            assertNotNull(plan.polynomialModel).encodingAttributions
                .map { it.sourceFactorIndex },
        )
        assertEquals(listOf(2), plan.polynomialModel!!.finiteFactorIndices)
        assertNull(plan.polynomialIntegerCoordinate(1))
        assertEquals(1, plan.finiteIntegerCoordinate(1))
        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `normal source routing should admit a required finite polynomial encoder without native propagation`() {
        val relation = object : Factor by Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1) {
            override val linearForm: LinearForm? = null
        }
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(relation))
        val providers = FactorProjectionProviders(
            encoder = EncoderProvider { _, target ->
                if (target != EncodingTarget.POLYNOMIAL) {
                    Encoder(EncoderSupport.UNSUPPORTED)
                } else {
                    Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                        encoding.polynomial(
                            PolynomialRealFormula.Atom(
                                encoding.integer(SourceIntId(0)),
                                PolynomialRelation.EQ,
                                PolynomialExpression.Constant.of(bigIntOf(1)),
                            ),
                        )
                        null
                    }
                }
            },
        )

        val result = BacktrackSolver.source(source).withProjectionProviders(providers).solve()

        assertEquals("1", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(0))
    }

    @Test
    fun `a required polynomial peer should enforce an opaque finite filter in the same search`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)
        val relation = object : Factor by row {
            override val linearForm: LinearForm? = null
        }
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(1, 1)),
            arrayOf<Factor>(
                relation,
                RealProduct(0, 0, 1, 1.0, 1.0),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 1.0),
            realUpper = doubleArrayOf(1.0, 1.0),
        )
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider { factor ->
                if (factor !== relation) {
                    BuiltInPropagationProjectionProvider.select(factor)
                } else {
                    PropagationProjection(
                        PropagationCapability.INERT,
                        AssignmentCheckCapability.SOURCE_CERTIFICATION,
                        "The required arithmetic peer supplies whole-relation enforcement.",
                    ) { NoPropagator }
                }
            },
            scoring = ScoringProjectionProvider { factor ->
                if (factor !== relation) {
                    BuiltInScoringProjectionProvider.select(factor)
                } else {
                    ScoringProjection(
                        ScoringCapability.INERT,
                        AssignmentCheckCapability.SOURCE_CERTIFICATION,
                        "The required arithmetic completion certifies the zero-score proposal.",
                    ) { _, _ -> NoInvariant }
                }
            },
            encoder = EncoderProvider { factor, target ->
                if (factor !== relation || target != EncodingTarget.POLYNOMIAL) {
                    BuiltInEncoderProvider.select(factor, target)
                } else {
                    Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                        encoding.polynomial(
                            PolynomialRealFormula.Atom(
                                encoding.integer(SourceIntId(0)),
                                PolynomialRelation.EQ,
                                PolynomialExpression.Constant.of(bigIntOf(1)),
                            ),
                        )
                        null
                    }
                }
            },
        )
        val plan = source.prepareExecutionPlan(providers)

        for (engine in listOf(FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH)) {
            val result = OpenTheoryPipeline.execute(plan.polynomialRequest(), engine = engine).result

            assertTrue(plan.capabilities.admission(engine).runnable)
            assertEquals("1", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(0))
        }
        assertEquals(listOf(0), plan.polynomialModel!!.finiteFactorIndices)
        val standalone = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(relation))
        assertIs<SolveResult.Unknown>(BacktrackSolver(standalone.bake(providers)).solve())
    }

    @Test
    fun `source polynomial work limits should include actual encoder installation`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 1.0),
            realUpper = doubleArrayOf(1.0, 1.0),
        )
        for (allowance in listOf(0L, 3L)) {
            var installations = 0
            var charged = 0L
            val caller = object : Cancellation {
                override fun isCancelled(): Boolean = false
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }
            val providers = FactorProjectionProviders(
                encoder = EncoderProvider { factor, target ->
                    val selected = BuiltInEncoderProvider.select(factor, target)
                    if (target != EncodingTarget.POLYNOMIAL) {
                        selected
                    } else {
                        Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                            installations++
                            selected.install(view, encoding)
                        }
                    }
                },
            )

            val result = BacktrackSolver.source(source).withProjectionProviders(providers)
                .solve(PolynomialTheoryParams(maximumWork = allowance, cancellation = caller))

            assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(result).reason)
            assertEquals(if (allowance == 0L) 0 else 1, installations)
            assertEquals(allowance, charged)
        }
    }

    @Test
    fun `polynomial participation should correlate finite propagation with arithmetic relations`() {
        val source = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 2) },
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                RealProduct(0, 0, 1, 1.0, 1.0),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 2),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 0.0),
            realUpper = doubleArrayOf(1.0, 0.0),
        )
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
    fun `unavailable finite encodings should not replace missing polynomial enforcement`() {
        for (support in listOf(EncoderSupport.UNSUPPORTED, EncoderSupport.RELAXATION_ONLY)) {
            val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)
            val source = Problem(
                0,
                1,
                arrayOf(IntDomain(0, 2)),
                arrayOf<Factor>(
                    row,
                    RealProduct(0, 0, 1, 1.0, 1.0),
                ),
                numRealVars = 2,
                realLower = doubleArrayOf(1.0, 0.0),
                realUpper = doubleArrayOf(1.0, 0.0),
            )
            val providers = FactorProjectionProviders(
                propagation = PropagationProjectionProvider { factor ->
                    if (factor === row) null else BuiltInPropagationProjectionProvider.select(factor)
                },
                encoder = EncoderProvider { factor, target ->
                    if (factor === row && target == EncodingTarget.POLYNOMIAL) {
                        Encoder(support)
                    } else {
                        BuiltInEncoderProvider.select(factor, target)
                    }
                },
            )

            val plan = source.prepareExecutionPlan(providers)

            assertEquals(PreparedExecutionKind.DECLINED, plan.kind)
            assertFalse(plan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        }
    }

    @Test
    fun `normal polynomial routing should expose the callers work meter to installation`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0),
            realUpper = doubleArrayOf(1.0, 1.0),
        )
        var spent = 0L
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= 4
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }
        val providers = FactorProjectionProviders(
            encoder = EncoderProvider { factor, target ->
                val selected = BuiltInEncoderProvider.select(factor, target)
                if (target != EncodingTarget.POLYNOMIAL) {
                    selected
                } else {
                    Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                        val result = selected.install(view, encoding)
                        encoding.cancellation.charge(4)
                        result
                    }
                }
            },
        )

        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(
            source.pipelineRoute(
                providers,
                boundCancellation = token,
            ),
        )
        val retry = source.prepareExecutionPlan(providers)

        assertTrue(spent >= 4)
        assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
        assertFalse(route.preparedPlan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        assertEquals(PreparedExecutionKind.POLYNOMIAL, retry.kind)
    }

    @Test
    fun `relaxation only encoders should not authorize open source enforcement`() {
        val input = assertIs<Problem>((
            SmtLib.parse(
                "(declare-const x Real) (assert (> x 0))",
            )).source.originalSource)
        val providers = FactorProjectionProviders(
            encoder = EncoderProvider { _, _ ->
                Encoder(EncoderSupport.RELAXATION_ONLY)
            },
        )

        val admission = input.prepareExecutionPlan(providers).capabilities
            .admission(ExecutionStrategy.COMPLETE)

        assertFalse(admission.runnable)
    }

    @Test
    fun `compiled polynomial factors should execute through a replaced provider`() {
        val x = FloatHandle("x", -1.0, 1.0)
        val compiled = SchemaDef<SchemaEntry>(
            mapOf(
                "x" to FloatSpec(-1.0, 1.0, 3),
                "square" to NamedConstraint((x * x) eq 0.0),
            ),
        ).compile()
        var products = 0
        val providers = FactorProjectionProviders(
            encoder = EncoderProvider { factor, target ->
                val selected = BuiltInEncoderProvider.select(factor, target)
                if (factor !is ContinuousProduct || target != EncodingTarget.POLYNOMIAL) {
                    selected
                } else {
                    Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                        products++
                        selected.install(view, encoding)
                    }
                }
            },
        )
        val plan = compiled.problem.prepareExecutionPlan(providers)

        val result = OpenTheoryPipeline.execute(plan.polynomialRequest()).result

        assertEquals(PreparedExecutionKind.POLYNOMIAL, plan.kind)
        assertIs<OpenTheoryResult.Sat>(result)
        assertTrue(products > 0)
    }

    @Test
    fun `difference provider replacement should combine factors in one graph`() {
        val input = assertIs<Problem>((
            SmtLib.parse(
                "(declare-const x Int) (declare-const y Int) " +
                    "(assert (<= (- x y) 0)) (assert (<= (- y x) (- 1)))",
            )).source.originalSource)
        val providers = FactorProjectionProviders(
            encoder = EncoderProvider { factor, target ->
                if (target != EncodingTarget.DIFFERENCE) {
                    Encoder(EncoderSupport.UNSUPPORTED)
                } else {
                    BuiltInEncoderProvider.select(factor, target)
                }
            },
        )
        val source = input
        val plan = source.prepareExecutionPlan(providers)
        val ownership = assertNotNull(plan.componentPlan)

        val result = ownership.search(source, emptyMap()).use {
            if (it.session.initialize() is ComponentResult.Conflict) {
                SearchResult.Exhausted
            } else {
                it.session.solve(source.numBoolVars)
            }
        }

        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, ownership.theoryPipeline)
        assertIs<SearchResult.Exhausted>(result)
    }

    @Test
    fun `selected difference relations should compose beside an unencoded finite global`() {
        val open = Bits(3).also { it.set(2) }
        val source = Problem(
            0,
            IntBounds.fromModelBounds(
                longArrayOf(0, 0, 0),
                longArrayOf(1, 1, 0),
                null,
                open,
            ),
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1), 0, 2),
                Linear(intArrayOf(1, -1), intArrayOf(2, 0), LinearOp.EQ, 1),
            ),
        )
        val providers = FactorProjectionProviders(
            encoder = EncoderProvider { factor, target ->
                if (target == EncodingTarget.DIFFERENCE) {
                    BuiltInEncoderProvider.select(factor, target)
                } else {
                    Encoder(EncoderSupport.UNSUPPORTED)
                }
            },
        )
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
    fun `bounded linear propagation and encoding should retain one finite choice owner`() {
        val source = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 2) },
            arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, -1),
            ),
        )
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider {
                PropagationProjection(
                    PropagationCapability.INERT,
                    AssignmentCheckCapability.SOURCE_CERTIFICATION,
                    "The selected arithmetic peer and original point check enforce the relation.",
                ) { NoPropagator }
            },
        )
        val plan = assertNotNull(source.prepareExecutionPlan(providers).componentPlan)

        val result = plan.search(source, plan.cpSourceDomains(source)).use {
            if (it.session.initialize() is ComponentResult.Conflict) {
                SearchResult.Exhausted
            } else {
                it.session.solve(source.numBoolVars)
            }
        }

        assertIs<SearchResult.Exhausted>(result)
        assertEquals(listOf(IntVariableOwner.CP, IntVariableOwner.CP), (0..1).map(plan::intOwner))
        assertEquals(listOf(FactorOwner.CP, FactorOwner.CP), (0..1).map(plan::factorOwner))
        assertTrue((0..1).all(plan::factorEncoded))
    }

    @Test
    fun `an enforcing encoder peer should complete finite filtering without a duplicate source checker`() {
        val relation = object : Factor by Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1) {
            override fun remap(mapping: VarRemap): Factor {
                require(mapping.int(0) == 0)
                return this
            }
        }
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(relation))
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider {
                PropagationProjection(
                    PropagationCapability.INERT,
                    AssignmentCheckCapability.SOURCE_CERTIFICATION,
                    "The mandatory arithmetic peer enforces the complete source relation.",
                ) { NoPropagator }
            },
        )
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
    fun `bounded fractional rows should admit their retained finite and arithmetic peers`() {
        val source = Problem(
            1,
            1,
            arrayOf(IntDomain(0, 2)),
            arrayOf<Factor>(
                ReifiedRealLinear(
                    0,
                    intArrayOf(0),
                    doubleArrayOf(0.5),
                    intArrayOf(),
                    doubleArrayOf(),
                    LinearOp.GE,
                    0.5,
                ),
                ReifiedRealLinear(
                    0,
                    intArrayOf(0),
                    doubleArrayOf(0.5),
                    intArrayOf(),
                    doubleArrayOf(),
                    LinearOp.LE,
                    0.5,
                ),
            ),
        )
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
        assertEquals(1L, model.valueOf(SearchExactIntValue(SourceIntId(0)))?.toLongExact())
    }

    @Test
    fun `real completion should respect holes beside a retained finite global`() {
        val holes = assertNotNull(IntDomain(0, 2).excludeValues(longArrayOf(1)))
        val source = Problem(
            0,
            2,
            arrayOf(holes, IntDomain(0, 2)),
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1), 0, 3),
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val plan = assertNotNull(source.prepareExecutionPlan().componentPlan)

        val result = plan.search(source, plan.cpSourceDomains(source)).use {
            if (it.session.initialize() is ComponentResult.Conflict) {
                SearchResult.Exhausted
            } else {
                it.session.solve(source.numBoolVars)
            }
        }

        assertIs<SearchResult.Exhausted>(result)
        assertEquals(IntVariableOwner.CP, plan.intOwner(1))
        assertEquals(FactorOwner.CP, plan.factorOwner(0))
        assertTrue(plan.factorEncoded(0))
        assertEquals(holes, source.intDomainOrNull(0))
    }

    @Test
    fun `decimal MPS source checks should retain finite integer ownership`() {
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
        assertEquals(
            listOf(BigFraction.ofLong(3), BigFraction.ONE),
            compiled.sourceWitness(listOf(BigFraction.ofLong(3), BigFraction.ONE)).values,
        )
    }

    @Test
    fun `a Boolean relation installed in the difference component should be fully enforced`() {
        val input = assertIs<Problem>((SmtLib.parse("(declare-const b Bool) (assert b)")).source.originalSource)
        val source = input
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { null })
        val plan = assertNotNull(source.prepareExecutionPlan(providers).componentPlan)

        val result = plan.search(source, emptyMap()).use {
            assertIs<ComponentResult.Consistent>(it.session.initialize())
            it.session.solve(source.numBoolVars)
        }

        val model = assertIs<SearchResult.Satisfied>(result).model
        assertEquals(true, model.valueOf(SearchBoolValue(0)))
    }

    @Test
    fun `open heuristic entry points should control selected encoder construction`() {
        for (entry in listOf("routed", "legacy", "neighborhood", "portfolio")) {
            val source = (
                SmtLib.parse(
                    "(declare-const x Real) (assert (= x 1))",
                )).model
            var cancelled = false
            var installations = 0
            var charged = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = cancelled
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }
            val providers = FactorProjectionProviders(
                encoder = EncoderProvider { factor, target ->
                    val selected = BuiltInEncoderProvider.select(factor, target)
                    if (target != EncodingTarget.LINEAR || !selected.enforcesSource) {
                        selected
                    } else {
                        Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                            installations++
                            val decline = selected.install(view, encoding)
                            encoding.cancellation.charge(1)
                            cancelled = true
                            decline
                        }
                    }
                },
            )
            val model = source.bake(providers)
            val params = TheoryParams(cancellation = stop)

            val result = when (entry) {
                "routed" -> assertIs<OpenTheoryExecution.Satisfy>(
                    OpenTheoryPipeline.searchWithoutTheory(
                        model,
                        params,
                        null,
                        FiniteEngine.LOCAL_SEARCH,
                        1,
                    ),
                ).result

                "legacy" -> assertIs<OpenTheoryExecution.Satisfy>(
                    OpenTheoryPipeline.searchWithoutTheory(
                        model,
                        params,
                        searchesContinuousOnly = true,
                    ),
                ).result

                "neighborhood" -> assertIs<OpenTheoryExecution.Satisfy>(
                    linearNeighborhoodInvocation(
                        model,
                        params,
                        null,
                        adaptive = false,
                        cores = 1,
                    ),
                ).result

                else -> OpenPortfolio(model, null, params, lanes = 1).solve(stop)
            }

            assertEquals(TerminationReason.Cancelled, assertIs<OpenTheoryResult.Unknown>(result).reason, entry)
            assertEquals(1, installations, entry)
            assertTrue(charged > 0, entry)
        }
    }
}
