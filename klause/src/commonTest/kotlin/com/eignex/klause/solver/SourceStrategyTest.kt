package com.eignex.klause.solver

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ObjectiveSense
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
import com.eignex.klause.solver.evidence.SourceValue
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
    fun `finite affine optimization should preserve complete decision limits`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(Clause(
            intArrayOf(Lit.make(0, true), Lit.make(1, true)),
        )))
        val objective = LinearObjective(boolWeights = longArrayOf(1, 2), constant = 7)
        val strategy = BacktrackSolver.source(source)
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(maxDecisions = 0L, nativeSat = false),
        ))

        val stopped = assertIs<OpenTheoryExecution.Optimize>(strategy.optimizeOpen(objective, params)).result
        val completed = assertIs<OpenTheoryExecution.Optimize>(strategy.optimizeOpen(objective)).result

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryOptimum.Bounded>(stopped).reason)
        assertEquals(BigFraction.ofLong(8), assertIs<OpenTheoryOptimum.Optimal>(completed).value)
    }

    @Test
    fun `finite affine optimization should keep assumption scopes isolated`() {
        val source = Problem(1, 0, emptyArray(), emptyArray())
        val objective = LinearObjective(boolWeights = longArrayOf(1), constant = 7)
        val strategy = BacktrackSolver.source(source)
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to true))),
        ))

        val pinned = assertIs<OpenTheoryExecution.Optimize>(strategy.optimizeOpen(objective, params)).result
        val unpinned = assertIs<OpenTheoryExecution.Optimize>(strategy.optimizeOpen(objective)).result

        assertEquals(BigFraction.ofLong(8), assertIs<OpenTheoryOptimum.Optimal>(pinned).value)
        assertEquals(BigFraction.ofLong(7), assertIs<OpenTheoryOptimum.Optimal>(unpinned).value)
    }

    @Test
    fun `finite affine optimization should retain a heuristic starting point`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 100)), emptyArray())
        val initial = Sample(booleanArrayOf(), longArrayOf(73))
        val objective = LinearObjective(constant = 7)
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(initialAssignment = initial, maxFlips = 1L, randomSeed = 17L),
        ))

        val execution = LocalSearchSolver.source(source).optimizeOpen(objective, params)

        val result = assertIs<OpenTheoryOptimum.Bounded>(assertIs<OpenTheoryExecution.Optimize>(execution).result)
        assertEquals("73", assertNotNull(result.incumbent).intValue(0))
        assertEquals(BigFraction.ofLong(7), result.value)
        assertEquals(TerminationReason.BudgetExhausted, result.reason)
        assertEquals(73L, initial.intValue(0))
    }

    @Test
    fun `finite affine heuristic optimization should complete rational coordinates without a supplied start`() {
        val source = Problem(0, 1, arrayOf(IntDomain(1, 2)),
            arrayOf(Linear(intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), doubleArrayOf(3.0),
                LinearOp.EQ, 0.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        for (sense in ObjectiveSense.entries) {
            val budget = NodeBudget(1000)
            val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
                localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17, nodeBudget = budget),
            ))

            val execution = assertIs<OpenTheoryExecution.Optimize>(LocalSearchSolver.source(source).optimizeOpen(
                objective, params, TheoryParams(randomSeed = 17, nodeBudget = budget), sense,
            ))

            val result = assertIs<OpenTheoryOptimum.Bounded>(execution.result)
            val point = assertNotNull(result.incumbent)
            val exact = assertIs<SourceValue.Rational>(assertNotNull(execution.acceptedIncumbent).value).value
            assertEquals(exact.toString(), point.realValue(0))
            assertTrue(point.intValue(0) in setOf("1", "2"))
            assertEquals(BigFraction.of(parseBigInt(point.intValue(0)), BIG_ONE), exact * BigFraction.ofLong(3))
            assertEquals(if (sense == ObjectiveSense.MAXIMIZE) exact.negated() else exact, result.value)
            assertEquals("ls", result.stats.run.backend)
        }
    }

    @Test
    fun `affine optimization should retain original direction after source bound closure`() {
        val source = Problem(
            0, 1, arrayOf(IntDomain(0, 3)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)),
            openIntHi = booleanArrayOf(true),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(3), constant = 7)
        val strategy = BacktrackSolver.source(source)

        for (sense in ObjectiveSense.entries) {
            val execution = strategy.optimizeOpen(objective, sense = sense)

            val completed = assertIs<OpenTheoryExecution.Optimize>(execution)
            val result = assertIs<OpenTheoryOptimum.Optimal>(completed.result)
            val maximize = sense == ObjectiveSense.MAXIMIZE
            assertEquals(if (maximize) "2" else "0", result.assignment.intValue(0))
            assertEquals(BigFraction.ofLong(if (maximize) -13 else 7), result.value)
            val publication = assertNotNull(completed.acceptedIncumbent)
            assertEquals(BigFraction.ofLong(if (maximize) 13 else 7),
                assertIs<SourceValue.Rational>(publication.value).value)
        }
    }

    @Test
    fun `affine optimization should retain a source preparation refutation`() {
        val source = Problem(
            0, 1, arrayOf(IntDomain(-3, 3)),
            arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            ),
            openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true),
        )

        val execution = BacktrackSolver.source(source).optimizeOpen(LinearObjective(intCoefficients = longArrayOf(1)))

        assertIs<OpenTheoryOptimum.Infeasible>(assertIs<OpenTheoryExecution.Optimize>(execution).result)
    }

    @Test
    fun `finite source satisfaction should preserve typed complete decision limits`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(Clause(
            intArrayOf(Lit.make(0, true), Lit.make(1, true)),
        )))
        val strategy = BacktrackSolver.source(source)

        val stopped = strategy.solve(BacktrackParams(maxDecisions = 0L, nativeSat = false))
        val completed = strategy.solve(BacktrackParams(randomSeed = 17L))

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(stopped).reason)
        val point = assertIs<OpenTheoryResult.Sat>(completed).assignment
        assertTrue(point.boolValue(0) || point.boolValue(1))
    }

    @Test
    fun `finite source satisfaction should preserve a supplied heuristic starting point`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 100)), emptyArray())
        val initial = Sample(booleanArrayOf(), longArrayOf(73))
        val strategy = LocalSearchSolver.source(source)

        val result = strategy.solve(LocalSearchParams(initialAssignment = initial, maxFlips = 1L, randomSeed = 17L))

        assertEquals("73", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(0))
        assertEquals(73L, initial.intValue(0))
    }

    @Test
    fun `finite source satisfaction should keep complete assumption scopes isolated`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val strategy = BacktrackSolver.source(source)
        val pinned = BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to false)))

        val refuted = strategy.solve(pinned)
        val unpinned = strategy.solve(BacktrackParams())

        assertIs<OpenTheoryResult.Unsat>(refuted)
        assertTrue(assertIs<OpenTheoryResult.Sat>(unpinned).assignment.boolValue(0))
    }

    @Test
    fun `source optimize should close open integer bounds before exact continuous preparation`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2).excludeValue(1)),
            arrayOf(
                RealProduct(0, 0, 1, 1.0, 1.0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                AllDifferent(intArrayOf(0, 1), 0, 3),
            ),
            openIntHi = booleanArrayOf(true, false),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 0.0),
            realUpper = doubleArrayOf(1.0, 1.0),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0))

        val result = BacktrackSolver.source(source).optimize(objective).result

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result)
        assertEquals(BigFraction.ZERO, optimum.incumbent.value.rational)
        assertEquals(0L, optimum.incumbent.sample.intValue(0))
        assertEquals(2L, optimum.incumbent.sample.intValue(1))
        assertTrue(source.intBounds.isOpenUpper(0))
    }

    @Test
    fun `source solve should keep exact products beyond double integer precision`() {
        val integer = 9007199254740993L
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(integer, integer)),
            arrayOf(RealProduct(0, 0, 1, 1.0, 1.0)),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 9007199254740992.0),
            realUpper = doubleArrayOf(1.0, 9007199254740994.0),
        )

        val result = BacktrackSolver.source(source).solve()

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals(integer.toString(), witness.intValue(0))
        assertEquals(integer.toString(), witness.realValue(1))
    }

    @Test
    fun `source solve should keep finite holes beside an open linear integer`() {
        val source = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 2).excludeValue(1), IntDomain(0, 2), IntDomain(0, 1)),
            arrayOf(
                RealProduct(0, 0, 1, 1.0, 1.0),
                AllDifferent(intArrayOf(0, 1), 0, 3),
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.GE, 0),
            ),
            openIntHi = booleanArrayOf(false, false, true),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 2.0),
            realUpper = doubleArrayOf(1.0, 2.0),
        )
        val params = PolynomialTheoryParams(
            maximumWork = 0,
            strategy = PolynomialStrategyParams(
                localSearch = LocalSearchParams(maxFlips = 128, randomSeed = 17),
            ),
        )

        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve(params)).assignment

            assertEquals("2", witness.intValue(0))
            assertTrue(witness.intValue(1).toLong() in 0L..1L)
            assertTrue(witness.intValue(2).toLong() >= 0)
        }
        assertTrue(source.intBounds.isOpenUpper(2))
    }

    @Test
    fun `source solve should share one integer across product checks`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf(
                RealProduct(0, 0, 1, 1.0, 1.0),
                RealProduct(0, 0, 2, 1.0, 1.0),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.GE, 0),
            ),
            openIntHi = booleanArrayOf(false, true),
            numRealVars = 3,
            realLower = doubleArrayOf(1.0, 0.0, 1.0),
            realUpper = doubleArrayOf(1.0, 0.0, 1.0),
        )

        val result = BacktrackSolver.source(source).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `source solve should complete finitely with zero polynomial work allowance`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2).excludeValue(1)),
            arrayOf(
                RealProduct(0, 0, 1, 1.0, 1.0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                AllDifferent(intArrayOf(0, 1), 0, 3),
            ),
            openIntHi = booleanArrayOf(true, false),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 1.0),
            realUpper = doubleArrayOf(1.0, 1.0),
        )
        val params = PolynomialTheoryParams(
            maximumWork = 0,
            strategy = PolynomialStrategyParams(
                localSearch = LocalSearchParams(maxFlips = 128, randomSeed = 17),
            ),
        )

        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve(params)).assignment

            assertEquals("1", witness.intValue(0))
            assertTrue(witness.intValue(1).toLong() in listOf(0L, 2L))
        }
        assertTrue(source.intBounds.isOpenUpper(0))
    }

    @Test
    fun `source solve should report budget exhausted when continuous completion needs polynomial work`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-2.0, 1.0),
            realUpper = doubleArrayOf(2.0, 1.0),
        )

        val result = BacktrackSolver.source(source).solve(PolynomialTheoryParams(maximumWork = 0))

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(result).reason)
    }

    @Test
    fun `source solve should open an inert propagation projection and check its witness`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 2)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)),
        )
        var openings = 0
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider {
                PropagationProjection(
                    PropagationCapability.INERT,
                    AssignmentCheckCapability.SOURCE_CERTIFICATION,
                    "The original source is checked at complete points.",
                ) {
                    openings++
                    NoPropagator
                }
            },
        )

        val result = BacktrackSolver.source(source).withProjectionProviders(providers)
            .solve(BacktrackParams(randomSeed = 1, maxDecisions = 10))

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertTrue(witness.intValue(0).toLong() in 1L..2L)
        assertTrue(openings > 0)
        assertEquals(IntDomain(0, 2), source.intDomainOrNull(0))
    }

    @Test
    fun `scorer refusal should only make local search unsupported`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0))))
        val providers = FactorProjectionProviders(scoring = ScoringProjectionProvider { null })

        val complete = BacktrackSolver.source(source).withProjectionProviders(providers).solve()
        val local = LocalSearchSolver.source(source).withProjectionProviders(providers).solve()

        assertTrue(assertIs<OpenTheoryResult.Sat>(complete).assignment.boolValue(0))
        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(local).reason)
    }

    @Test
    fun `custom scorer should accept cumulative starts beyond Int range`() {
        val lower = Int.MAX_VALUE.toLong() + 10
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(lower, lower + 2)),
            arrayOf(Cumulative(intArrayOf(0), longArrayOf(1), longArrayOf(1), 1)),
        )
        val providers = FactorProjectionProviders(
            scoring = ScoringProjectionProvider {
                ScoringProjection(ScoringCapability.GRADED, AssignmentCheckCapability.EXACT) { _, _ ->
                    object : Invariant {
                        override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = false
                    }
                }
            },
        )

        val result = LocalSearchSolver.source(source).withProjectionProviders(providers)
            .solve(LocalSearchParams(randomSeed = 1, maxFlips = 1))

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertTrue(witness.intValue(0).toLong() in lower..lower + 2)
    }

    @Test
    fun `compiled square should yield exact algebraic coordinates for both strategies`() {
        val x = FloatHandle("x", -2.0, 2.0)
        val compiled = SchemaDef<SchemaEntry>(
            mapOf(
                "x" to FloatSpec(-2.0, 2.0, 5),
                "square" to NamedConstraint((x * x) eq 2.0),
            ),
        ).compile()
        val strategies = listOf(BacktrackSolver.source(compiled), LocalSearchSolver.source(compiled))

        for (strategy in strategies) {
            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve()).assignment
            val exact = assertNotNull(witness.algebraicRealValue(compiled.realVarIdByName.getValue("x")))

            assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), exact))
        }
    }

    @Test
    fun `compiled product should keep exact coordinates of both operands`() {
        val x = FloatHandle("x", 0.0, 2.0)
        val y = FloatHandle("y", 0.0, 2.0)
        val compiled = SchemaDef<SchemaEntry>(
            mapOf(
                "x" to FloatSpec(0.0, 2.0, 5),
                "y" to FloatSpec(0.0, 2.0, 5),
                "product" to NamedConstraint((x * y) eq 1.0),
            ),
        ).compile()
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
    fun `schema product chain should yield exact shared coordinates`() {
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
    fun `source solve should satisfy finite globals beside exact continuous completion`() {
        val source = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(
                Table(intArrayOf(0, 1), longArrayOf(0, 1, 1, 0)),
                ContinuousProduct(0, 0, 1),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(-2.0, 2.0),
            realUpper = doubleArrayOf(2.0, 2.0),
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
    fun `source solve should keep exact reals in finite linear completion`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                Table(intArrayOf(0), longArrayOf(1)),
                Linear(intArrayOf(0), doubleArrayOf(0.1), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.3),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(-1.0),
            realUpper = doubleArrayOf(1.0),
        )
        val params = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(
                localSearch = LocalSearchParams(maxFlips = 16, randomSeed = 17),
            ),
        )
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve(params)).assignment
            val real = assertNotNull(assertIs<OpenTheoryAssignment.Sampled>(witness).sample.exactReals)[0]

            assertEquals("1", witness.intValue(0))
            assertEquals(assertNotNull(BigFraction.ofDouble(0.3)), real + assertNotNull(BigFraction.ofDouble(0.1)))
            assertEquals(real.toString(), witness.realValue(0))
        }
    }

    @Test
    fun `source solve should be unsupported for factors without execution checks`() {
        val unchecked = object : Factor by Clause(intArrayOf(Lit.make(0, true))) {
            override val linearForm: LinearForm? = null
        }
        val source = Problem(1, 0, emptyArray(), arrayOf<Factor>(unchecked))
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val result = strategy.solve()

            assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
        }
    }

    @Test
    fun `strategy solve and direct pipeline should both satisfy the original square`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-2.0, 2.0),
            realUpper = doubleArrayOf(2.0, 2.0),
        )
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
    fun `polynomial assumptions should be scoped to a single solve`() {
        val source = Problem(
            1,
            0,
            emptyArray(),
            arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0),
            realUpper = doubleArrayOf(1.0, 0.0),
        )
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            for (pin in listOf(true, false)) {
                val params = PolynomialTheoryParams(
                    strategy = PolynomialStrategyParams(
                        assumptions = Assumptions(bools = mapOf(0 to pin)),
                        localSearch = LocalSearchParams(maxFlips = 2, randomSeed = 17),
                    ),
                )

                val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve(params)).assignment

                assertEquals(pin, witness.boolValue(0))
            }
        }
    }

    @Test
    fun `cancelled solve should not affect a later solve`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0),
            realUpper = doubleArrayOf(1.0, 0.0),
        )
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val cancelled = strategy.solve(PolynomialTheoryParams(cancellation = Cancellation { true }))
            assertEquals(TerminationReason.Cancelled, assertIs<OpenTheoryResult.Unknown>(cancelled).reason)

            assertIs<OpenTheoryResult.Sat>(strategy.solve())
        }
    }

    @Test
    fun `source solve should keep wide open integer values exact`() {
        val value = parseBigInt("18446744073709551617")
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(-3, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, value),
            ),
            openIntLo = booleanArrayOf(true),
            openIntHi = booleanArrayOf(true),
        )
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val params = PolynomialTheoryParams(
                strategy = PolynomialStrategyParams(
                    localSearch = LocalSearchParams(maxFlips = 4, maxInstructions = 1000, randomSeed = 17),
                ),
            )

            val witness = assertIs<OpenTheoryResult.Sat>(strategy.solve(params)).assignment

            assertEquals(value.toString(), witness.intValue(0))
        }
    }

    @Test
    fun `open source solve should decline boolean pins without changing the model`() {
        val source = Problem(
            1,
            1,
            arrayOf(IntDomain(-3, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 42),
            ),
            openIntLo = booleanArrayOf(true),
            openIntHi = booleanArrayOf(true),
        )
        val strategy = BacktrackSolver.source(source)

        val result = strategy.solve(BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to true))))

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
        assertIs<OpenTheoryResult.Sat>(strategy.solve())
    }

    @Test
    fun `source solve should decline deduction scopes on every route`() {
        val finite = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val open = Problem(
            0,
            1,
            arrayOf(IntDomain(-3, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 42),
            ),
            openIntLo = booleanArrayOf(true),
            openIntHi = booleanArrayOf(true),
        )
        val polynomial = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0),
            realUpper = doubleArrayOf(1.0, 0.0),
        )
        val assumptions = Assumptions.None.withTightenedMin(0, 1)
        for (source in listOf(finite, open, polynomial)) {
            for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
                val common = PolynomialTheoryParams(strategy = PolynomialStrategyParams(assumptions = assumptions))
                val native = PolynomialTheoryParams(
                    strategy = PolynomialStrategyParams(
                        backtrack = BacktrackParams(assumptions = assumptions),
                        localSearch = LocalSearchParams(assumptions = assumptions),
                    ),
                )
                for (params in listOf(common, native)) {
                    val result = strategy.solve(params)

                    assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
                }
            }
        }
    }

    @Test
    fun `source optimize should decline deduction scopes`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(-3, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 42),
            ),
            openIntLo = booleanArrayOf(true),
            openIntHi = booleanArrayOf(true),
        )
        val params = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(
                assumptions = Assumptions.None.withIntHole(0, 0),
            ),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val open = assertIs<OpenTheoryExecution.Optimize>(strategy.optimizeOpen(objective, params)).result
            val polynomial = strategy.optimize(objective, params).result

            assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryOptimum.Bounded>(open).reason)
            assertEquals(
                TerminationReason.Unsupported,
                assertIs<QuadraticOptimizationResult.Unknown>(polynomial).reason,
            )
        }
    }

    @Test
    fun `source solve should decline tolerance checks without invoking them`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.3),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(-1.0),
            realUpper = doubleArrayOf(1.0),
        )
        var calls = 0

        val result = BacktrackSolver.source(source).solve(
            BacktrackParams(toleranceCheck = {
                calls++
                true
            }),
        )

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
        assertEquals(0, calls)
    }

    @Test
    fun `source operations should decline component factories without invoking them`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0),
            realUpper = doubleArrayOf(1.0, 0.0),
        )
        var calls = 0
        val params = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(
                backtrack = BacktrackParams(componentFactory = {
                    calls++
                    emptyList()
                }),
            ),
        )
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val solve = strategy.solve(params)
            val optimize = strategy.optimize(LinearObjective(realCoefficients = doubleArrayOf(0.0, 1.0)), params).result

            assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(solve).reason)
            assertEquals(TerminationReason.Unsupported, assertIs<QuadraticOptimizationResult.Unknown>(optimize).reason)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `polynomial local search should decline event hooks without invoking them`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0),
            realUpper = doubleArrayOf(1.0, 0.0),
        )
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
    fun `source optimize should keep exact products and engine specific optimality`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-1.0, Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(1.0, Double.POSITIVE_INFINITY),
        )
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))
        val params = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(
                localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17),
            ),
        )

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
            } else {
                assertIs<QuadraticOptimizationResult.Optimal>(result)
            }
            assertTrue(execution.nativeWork > 0)
        }
    }

    @Test
    fun `open optimize should keep exact wide integer objective values`() {
        val value = parseBigInt("18446744073709551617")
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(-3, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, value),
            ),
            openIntLo = booleanArrayOf(true),
            openIntHi = booleanArrayOf(true),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val params = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(
                localSearch = LocalSearchParams(maxFlips = 2, maxInstructions = 1000, randomSeed = 17),
            ),
        )

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
    fun `polynomial optimize should decline open integer sources`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(-3, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 42),
            ),
            openIntLo = booleanArrayOf(true),
            openIntHi = booleanArrayOf(true),
        )

        val result = BacktrackSolver.source(source).optimize(LinearObjective(intCoefficients = longArrayOf(1))).result

        assertEquals(TerminationReason.Unsupported, assertIs<QuadraticOptimizationResult.Unknown>(result).reason)
    }
}
