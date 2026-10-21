package com.eignex.klause.compile

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.model.FloatLinearConstraint
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.Not
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.quadraticSourceResultCapture
import com.eignex.klause.solver.evidence.sourceCondition
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryOptimum
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.algebraicRealValue
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.skema.SchemaDef
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class CompiledSourceBindingTest {
    @Test
    fun `local source witnesses should retain optional values after private helper capture`() {
        for (pinAbsent in listOf(true, false)) {
            val schema = object : VariableSchema() {
                val x by optFloatVar(-1.0, 1.0, 2)
                val y by floatVar(0.0, 1.0, 2)
                init {
                    constraint(Not(x.present.toExpr()))
                    constraint((x.value * x.value) eq y)
                }
            }
            val compiled = schema.compile(semanticOverrides = SemanticOverrides(pinAbsentOptionalValues = pinAbsent))
            val helper = compiled.minimize(schema.x.value)

            val result = assertIs<OpenTheoryResult.Sat>(LocalSearchSolver.source(compiled).solve(
                LocalSearchParams(maxFlips = 1000, randomSeed = 17)))
            val point = assertIs<OpenTheoryAssignment.Sampled>(result.assignment).sample

            assertSame(compiled.sourceBinding.originalProblem, point.coordinateFrame)
            assertNull(compiled.decode(schema.x, point))
            assertEquals(if (pinAbsent) 0.0 else 1.0, abs(compiled.decode(schema.x.value, point)))
            assertEquals(if (pinAbsent) 0.0 else 1.0, compiled.decode(schema.y, point))
            assertEquals(QuadraticVariable.Real(assertNotNull(compiled.sourceRealId(schema.x.value))),
                compiled.sourceObjective(helper).terms.single().first)
        }
    }

    @Test
    fun `a captured helper objective should retain its producing source evidence scope`() {
        val x = FloatHandle("x", 2.0, 4.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf("x" to FloatSpec(2.0, 4.0))).compile()
        val helper = compiled.maximize(x).copy(constant = 7)
        val objective = compiled.sourceObjective(helper)
        val strategy = BacktrackSolver.source(compiled)
        val scope = SourceScope(SourceRevision("compiled-helper", "klause-ir-exact-v1"),
            Assumptions.None.sourceCondition(), SourceObjective("cost", ObjectiveSense.MINIMIZE))
        val capture = assertNotNull(strategy.source.quadraticSourceResultCapture(scope, objective))
        val result = strategy.optimize(helper).result

        val retained = capture.quadratic(result, "compiled-helper")

        assertIs<QuadraticOptimizationResult.Optimal>(retained)
        assertEquals(scope, assertNotNull(retained.evidence).scope)
    }

    @Test
    fun `a complete original source arm should retain a point outside its compatibility grid`() {
        val x = FloatHandle("x", 0.0, 1.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(0.0, 1.0, 2),
            "strict" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.GT, -1.0)),
            "square" to NamedConstraint((x * x) eq 0.25),
        )).compile()

        val result = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(compiled).solve())

        val point = assertNotNull(result.assignment.algebraicRealValue(assertNotNull(compiled.sourceRealId(x)).index))
        assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-1, 2), point))
    }

    @Test
    fun `private grid exhaustion should retain an incomplete source result`() {
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(0.0, 1.0, 2),
            "strict" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.GT, -1.0)),
            "half" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.EQ, 0.5)),
        )).compile()

        val result = assertIs<OpenTheoryResult.Unknown>(LocalSearchSolver.source(compiled).solve())

        assertEquals(TerminationReason.Unsupported, result.reason)
    }

    @Test
    fun `source adapters should retain real declarations beside a selected compatibility grid`() {
        val x = FloatHandle("x", -1.0, 1.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-1.0, 1.0, 3),
            "different" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.NE, 0.0)),
        )).compile()
        val original = compiled.sourceBinding.originalProblem
        val compatibility = compiled.problem

        for (strategy in listOf(BacktrackSolver.source(compiled), LocalSearchSolver.source(compiled))) {
            assertSame(original, strategy.source)
            assertSame(original, strategy.withProjectionProviders(FactorProjectionProviders.Default).source)
            assertSame(compiled, strategy.compiledSchema)
        }
        assertEquals(SourceRealId(0), compiled.sourceRealId(x))
        assertEquals(1, original.numRealVars)
        assertEquals(0, original.numIntVars)
        assertEquals(0, compatibility.numRealVars)
        assertEquals(1, compatibility.numIntVars)
        assertSame(original, compiled.sourceBinding.attachment().originalSource)
    }

    @Test
    fun `decoding should distinguish original private and unbound frames by identity`() {
        val x = FloatHandle("x", -1.0, 1.0)
        val definition = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-1.0, 1.0, 3),
            "different" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.NE, 0.0)),
        ))
        val compiled = definition.compile()
        val other = definition.compile()
        val original = assertNotNull(compiled.sourceBinding.bindOriginalSample(
            Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.25),
                listOf(BigFraction.ofLong(4).reciprocal())), Cancellation.Never))
        val unbound = Sample(booleanArrayOf(), longArrayOf(2))
        val privatePoint = unbound.withCoordinateFrame(compiled.problem)

        assertEquals(0.25, compiled.decode(x, original))
        assertEquals(1.0, compiled.decode(x, privatePoint))
        assertEquals(1.0, compiled.decode(x, unbound))
        assertFailsWith<IllegalArgumentException> {
            compiled.decode(x, unbound.withCoordinateFrame(other.problem))
        }
        assertFailsWith<IllegalArgumentException> {
            compiled.sourceBinding.bindOriginalSample(privatePoint, Cancellation.Never)
        }
    }

    @Test
    fun `an interrupted original frame check should withhold the point`() {
        val compiled = SchemaDef<SchemaEntry>(mapOf("x" to FloatSpec(0.0, 1.0))).compile()
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), listOf(BigFraction.ZERO))

        val bound = compiled.sourceBinding.bindOriginalSample(sample, Cancellation { true })

        assertNull(bound)
        assertNull(sample.coordinateFrame)
    }

    @Test
    fun `an off grid absent default should decode and evaluate consistently in the finite facade`() {
        val schema = object : VariableSchema() {
            val x by optFloatVar(-1.0, 1.0, 2)
            init { constraint(Not(x.present.toExpr())) }
        }
        val compiled = schema.compile()
        val point = BacktrackSolver(compiled.problem.bake()).enumerate().single()
        val original = compiled.sourceBinding.originalProblem
        val column = assertNotNull(compiled.sourceBinding.legacyColumn(schema.x.value.name))

        assertNull(compiled.decode(schema.x, point))
        assertEquals(0.0, compiled.decode(schema.x.value, point))
        assertEquals(BigFraction.ZERO, column.value(point.intValue(column.coordinate.variable)))
        assertEquals(2, column.selector().coordinate.buckets)
        assertEquals(0, original.numIntVars)
        assertEquals(1, original.numRealVars)
    }

    @Test
    fun `disabled absent pinning should leave every requested grid value available`() {
        val schema = object : VariableSchema() {
            val x by optFloatVar(-1.0, 1.0, 2)
            init { constraint(Not(x.present.toExpr())) }
        }
        val compiled = schema.compile(semanticOverrides = SemanticOverrides(pinAbsentOptionalValues = false))

        val points = BacktrackSolver(compiled.problem.bake()).enumerate()
            .map { compiled.decode(schema.x.value, it) }.toSet()

        assertEquals(setOf(-1.0, 1.0), points)
        assertEquals(0, compiled.sourceBinding.originalProblem.numIntVars)
    }

    @Test
    fun `a copied float helper should retain exact source coefficients and constants`() {
        val x = FloatHandle("x", 2.0, 4.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(2.0, 4.0, 3),
            "different" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.NE, 3.0)),
        )).compile()
        val objective = compiled.minimize(x).copy(intCoefficients = longArrayOf(9_007_199_254_740_993L), constant = 7)

        val exact = compiled.sourceObjective(objective)

        assertEquals(QuadraticVariable.Real(SourceRealId(0)), exact.terms.single().first)
        assertEquals(QuadraticCoefficient.ofLong(9_007_199_254_740_993L), exact.terms.single().coefficient)
        assertEquals(QuadraticCoefficient.ofLong(7), exact.constant)
        assertSame(exact, compiled.sourceBinding.originalObjective(objective))
        assertSame(exact, compiled.sourceObjective(objective))
        assertNull(compiled.sourceBinding.openObjective(objective))
        assertFailsWith<IllegalArgumentException> { objective.toQuadraticObjectiveSpec() }
    }

    @Test
    fun `a foreign compiled helper should reject before source execution`() {
        val x = FloatHandle("x", 0.0, 1.0)
        val definition = SchemaDef<SchemaEntry>(mapOf("x" to FloatSpec(0.0, 1.0)))
        val first = definition.compile()
        val second = definition.compile()
        val objective = first.minimize(x)

        for (strategy in listOf(BacktrackSolver.source(second),
                LocalSearchSolver.source(second), BacktrackSolver.source(second.sourceBinding.originalProblem),
                LocalSearchSolver.source(second.sourceBinding.originalProblem))) {
            assertFailsWith<IllegalArgumentException> { strategy.optimize(objective) }
            assertFailsWith<IllegalArgumentException> { strategy.optimizeOpen(objective) }
        }
    }

    @Test
    fun `exact source optimization should preserve a negated helper and explicit maximization sense`() {
        val x = FloatHandle("x", 2.0, 4.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(2.0, 4.0, 3),
            "different" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.NE, 3.0)),
        )).compile()
        val weight = 9_007_199_254_740_993L
        val objective = compiled.maximize(x).copy(intCoefficients = longArrayOf(-weight), constant = 7)

        val result = assertIs<QuadraticOptimizationResult.Optimal>(
            BacktrackSolver.source(compiled).optimize(objective, sense = ObjectiveSense.MAXIMIZE).result)

        assertEquals(BigFraction.ofLong(7 - 2 * weight), result.incumbent.value.rational)
        assertEquals(2.0, compiled.decode(x, result.incumbent.sample))
        assertSame(compiled.sourceBinding.originalProblem, result.incumbent.sample.coordinateFrame)
        assertEquals(ObjectiveSense.MAXIMIZE, result.sense)
    }

    @Test
    fun `the open objective interface should explicitly decline a rounded real weight`() {
        val x = FloatHandle("x", 2.0, 4.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(2.0, 4.0, 3),
            "different" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.NE, 3.0)),
        )).compile()
        val objective = compiled.minimize(x).copy(intCoefficients = longArrayOf(9_007_199_254_740_993L))

        val result = assertIs<OpenTheoryExecution.Optimize>(BacktrackSolver.source(compiled).optimizeOpen(objective))

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryOptimum.Bounded>(result.result).reason)
    }

    @Test
    fun `continuous schema policy should retain optional source defaults without grid geometry`() {
        val schema = object : VariableSchema() {
            val x by optFloatVar(-1.0, 1.0, 2)
            init { constraint(Not(x.present.toExpr())) }
        }
        val compiled = schema.compile(semanticOverrides = SemanticOverrides(floats = FloatSemantics.Continuous))

        val point = BacktrackSolver(compiled.problem.bake()).enumerate().single()

        assertNull(compiled.decode(schema.x, point))
        assertEquals(0.0, compiled.decode(schema.x.value, point))
        assertEquals(0, compiled.problem.numIntVars)
    }
}
