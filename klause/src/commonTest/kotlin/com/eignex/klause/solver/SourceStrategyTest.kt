package com.eignex.klause.solver

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.ScoringProjection
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryOptimum
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialStrategyParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.algebraicRealValue
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceStrategyTest {
    @Test
    fun `a source invocation opens selected inert deduction and checks its witness`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)))
        var openings = 0
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "The original source is checked at complete points.") { openings++; NoPropagator }
        })

        val result = BacktrackSolver.source(source).withProjectionProviders(providers)
            .solve(BacktrackParams(randomSeed = 1, maxDecisions = 10))

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertTrue(witness.intValue(0).toLong() in 1L..2L)
        assertTrue(openings > 0)
        assertEquals(IntDomain(0, 2), source.intDomainOrNull(0))
    }

    @Test
    fun `a source scorer refusal does not refuse complete traversal`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0))))
        val providers = FactorProjectionProviders(scoring = ScoringProjectionProvider { null })

        val complete = BacktrackSolver.source(source).withProjectionProviders(providers).solve()
        val local = LocalSearchSolver.source(source).withProjectionProviders(providers).solve()

        assertTrue(assertIs<OpenTheoryResult.Sat>(complete).assignment.boolValue(0))
        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(local).reason)
    }

    @Test
    fun `an alternate scheduling scorer accepts starts outside the dense timeline range`() {
        val lower = Int.MAX_VALUE.toLong() + 10
        val source = Problem(0, 1, arrayOf(IntDomain(lower, lower + 2)),
            arrayOf(Cumulative(intArrayOf(0), longArrayOf(1), longArrayOf(1), 1)))
        val providers = FactorProjectionProviders(scoring = ScoringProjectionProvider {
            ScoringProjection(ScoringCapability.GRADED, AssignmentCheckCapability.EXACT) { _, _ ->
                object : Invariant {
                    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = false
                }
            }
        })

        val result = LocalSearchSolver.source(source).withProjectionProviders(providers)
            .solve(LocalSearchParams(randomSeed = 1, maxFlips = 1))

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertTrue(witness.intValue(0).toLong() in lower..lower + 2)
    }

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
    fun `compiled products keep both original operand namespaces`() {
        val x = FloatHandle("x", 0.0, 2.0)
        val y = FloatHandle("y", 0.0, 2.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(0.0, 2.0, 5), "y" to FloatSpec(0.0, 2.0, 5),
            "product" to NamedConstraint((x * y) eq 1.0),
        )).compile()
        val factor = compiled.problem.factors.filterIsInstance<ContinuousProduct>().single()
        for (strategy in listOf(BacktrackSolver.source(compiled), LocalSearchSolver.source(compiled))) {
            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve()).assignment
            val first = assertNotNull(witness.algebraicRealValue(factor.a))
            val second = assertNotNull(witness.algebraicRealValue(factor.b))
            val result = assertNotNull(witness.algebraicRealValue(factor.result))
            val arithmetic = AlgebraicArithmetic()

            assertEquals(0, arithmetic.compare(arithmetic.multiply(first, second), result))
            assertEquals(BigFraction.ONE, result.rational)
        }
    }

    @Test
    fun `schema chains preserve their shared exact product coordinates`() {
        val schema = object : VariableSchema() {
            val x by floatVar(-2.0, 2.0, 5)
            val cube by constraint { (x * x * x) eq x }
        }

        for (strategy in listOf(BacktrackSolver.source(schema), LocalSearchSolver.source(schema))) {
            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve()).assignment
            val compiled = assertNotNull(strategy.compiledSchema)
            val id = assertNotNull(compiled.sourceRealId(schema.x))
            val exact = assertNotNull(witness.algebraicRealValue(id.index))

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
    fun `finite linear completion preserves exact original continuous coordinates`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf<Factor>(
            Table(intArrayOf(0), longArrayOf(1)),
            Linear(intArrayOf(0), doubleArrayOf(0.1), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.3),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 16, randomSeed = 17),
        ))
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve(params)).assignment
            val real = assertNotNull(assertIs<OpenTheoryAssignment.Sampled>(witness).sample.exactReals)[0]

            assertEquals("1", witness.intValue(0))
            assertEquals(assertNotNull(BigFraction.ofDouble(0.3)), real + assertNotNull(BigFraction.ofDouble(0.1)))
            assertEquals(real.toString(), witness.realValue(0))
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


    @Test
    fun `source factors without execution checks explicitly decline before finite construction`() {
        val unchecked = object : Factor by Clause(intArrayOf(Lit.make(0, true))) {}
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(unchecked))
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val result = strategy.solve()

            assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
        }
    }

    @Test
    fun `factory and direct pipeline witnesses satisfy the same original square`() {
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-2.0, 2.0), realUpper = doubleArrayOf(2.0, 2.0))
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val direct = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = strategy.engine).result
            val facade = strategy.solve()

            for (result in listOf(direct, facade)) {
                val point = assertIs<OpenTheoryResult.Sat>(result).assignment
                val exact = assertNotNull(point.algebraicRealValue(0))
                assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), exact))
            }
        }
    }

    @Test
    fun `polynomial assumptions remain scoped across source operations`() {
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 0.0))
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            for (pin in listOf(true, false)) {
                val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
                    assumptions = Assumptions(bools = mapOf(0 to pin)),
                    localSearch = LocalSearchParams(maxFlips = 2, randomSeed = 17),
                ))

                val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve(params)).assignment

                assertEquals(pin, witness.boolValue(0))
            }
        }
    }

    @Test
    fun `cancellation withholds publication without retaining a cancelled invocation`() {
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 0.0))
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val cancelled = strategy.solve(PolynomialTheoryParams(cancellation = Cancellation { true }))
            assertEquals(TerminationReason.Cancelled, assertIs<OpenTheoryResult.Unknown>(cancelled).reason)

            assertIs<OpenTheoryResult.Sat>(strategy.solve())
        }
    }

    @Test
    fun `wide open integers retain exact values outside an invented finite window`() {
        val value = parseBigInt("18446744073709551617")
        val source = Problem(0, 1, arrayOf(IntDomain(-3, 3)), arrayOf<Factor>(
            Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, value),
        ), openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true))
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
                localSearch = LocalSearchParams(maxFlips = 4, maxInstructions = 1000, randomSeed = 17),
            ))

            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve(params)).assignment

            assertEquals(value.toString(), witness.intValue(0))
        }
    }

    @Test
    fun `open scoped pins explicitly decline without changing the original model`() {
        val source = Problem(1, 1, arrayOf(IntDomain(-3, 3)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 42),
        ), openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true))
        val strategy = BacktrackSolver.source(source)

        val result = strategy.solve(BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to true))))

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
        assertIs<OpenTheoryResult.Sat>(strategy.solve())
    }

    @Test
    fun `deduction scopes explicitly decline on every source route`() {
        val finite = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val open = Problem(0, 1, arrayOf(IntDomain(-3, 3)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 42),
        ), openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true))
        val polynomial = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 0.0))
        val assumptions = Assumptions.None.withTightenedMin(0, 1)
        for (source in listOf(finite, open, polynomial)) {
            for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
                val common = PolynomialTheoryParams(strategy = PolynomialStrategyParams(assumptions = assumptions))
                val native = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
                    backtrack = BacktrackParams(assumptions = assumptions),
                    localSearch = LocalSearchParams(assumptions = assumptions),
                ))
                for (params in listOf(common, native)) {
                    val result = strategy.solve(params)

                    assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
                }
            }
        }
    }

    @Test
    fun `source optimization explicitly declines deduction scopes`() {
        val source = Problem(0, 1, arrayOf(IntDomain(-3, 3)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 42),
        ), openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true))
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            assumptions = Assumptions.None.withIntHole(0, 0),
        ))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val open = assertIs<OpenTheoryExecution.Optimize>(strategy.optimizeOpen(objective, params)).result
            val polynomial = strategy.optimize(objective, params).result

            assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryOptimum.Bounded>(open).reason)
            assertEquals(TerminationReason.Unsupported,
                assertIs<QuadraticOptimizationResult.Unknown>(polynomial).reason)
        }
    }

    @Test
    fun `source satisfaction declines tolerance acceptance before invoking the hook`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf<Factor>(
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.3),
        ), numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        var calls = 0

        val result = BacktrackSolver.source(source).solve(BacktrackParams(toleranceCheck = { calls++; true }))

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
        assertEquals(0, calls)
    }

    @Test
    fun `source operations decline unbound component factories without invoking them`() {
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 0.0))
        var calls = 0
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(componentFactory = { calls++; emptyList() }),
        ))
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val solve = strategy.solve(params)
            val optimize = strategy.optimize(LinearObjective(realCoefficients = doubleArrayOf(0.0, 1.0)), params).result

            assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(solve).reason)
            assertEquals(TerminationReason.Unsupported, assertIs<QuadraticOptimizationResult.Unknown>(optimize).reason)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `exact polynomial local search explicitly declines finite event hooks`() {
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 0.0))
        var calls = 0
        val params = LocalSearchParams(onEvent = { calls++ })
        val strategy = LocalSearchSolver.source(source)

        val solve = strategy.solve(params)
        val optimize = strategy.optimize(LinearObjective(realCoefficients = doubleArrayOf(0.0, 1.0)), params).result

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(solve).reason)
        assertEquals(TerminationReason.Unsupported, assertIs<QuadraticOptimizationResult.Unknown>(optimize).reason)
        assertEquals(0, calls)
    }

    @Test
    fun `source optimization preserves exact original products and heuristic authority`() {
        val source = Problem(0, 0, emptyArray(), arrayOf<Factor>(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(1.0, Double.POSITIVE_INFINITY))
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17),
        ))

        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val execution = strategy.optimize(objective, params)
            val result = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result)
            val point = assertNotNull(result.incumbent.sample.algebraicReals)
            val arithmetic = AlgebraicArithmetic()

            assertEquals(0, arithmetic.compare(arithmetic.multiply(point[0], point[0]), point[1]))
            assertEquals(BigFraction.ofLong(7), result.incumbent.value.rational)
            if (strategy.engine == FiniteEngine.LOCAL_SEARCH) {
                assertIs<QuadraticOptimizationResult.BestFound>(result)
                assertTrue(result.stats.ls.moves.sum > 0)
            } else assertIs<QuadraticOptimizationResult.Optimal>(result)
            assertTrue(execution.nativeWork > 0)
        }
    }


    @Test
    fun `open optimization retains exact wide integer objective values`() {
        val value = parseBigInt("18446744073709551617")
        val source = Problem(0, 1, arrayOf(IntDomain(-3, 3)), arrayOf<Factor>(
            Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, value),
        ), openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 2, maxInstructions = 1000, randomSeed = 17),
        ))

        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val execution = assertIs<OpenTheoryExecution.Optimize>(strategy.optimizeOpen(objective, params))
            val actual = when (val result = execution.result) {
                is OpenTheoryOptimum.Optimal -> result.value
                else -> assertIs<OpenTheoryOptimum.Bounded>(result).value
            }

            assertEquals(BigFraction.of(value, BIG_ONE), actual)
        }
    }

    @Test
    fun `open integer sources explicitly decline bounded polynomial optimization`() {
        val source = Problem(0, 1, arrayOf(IntDomain(-3, 3)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 42),
        ), openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true))

        val result = BacktrackSolver.source(source).optimize(LinearObjective(intCoefficients = longArrayOf(1))).result

        assertEquals(TerminationReason.Unsupported, assertIs<QuadraticOptimizationResult.Unknown>(result).reason)
    }

}
