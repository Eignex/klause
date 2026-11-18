package com.eignex.klause.compile

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.ObjectiveSense
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
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.quadraticSourceResultCapture
import com.eignex.klause.solver.evidence.sourceCondition
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.algebraicRealValue
import com.eignex.klause.solver.pipeline.toSample
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.util.ExecutionControl
import com.eignex.skema.SchemaDef
import com.eignex.util.numbers.BigFraction
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
                val x by optFloatVar(-1.0, 1.0)
                val y by floatVar(0.0, 1.0)
                init {
                    constraint(Not(x.present.toExpr()))
                    constraint((x.value * x.value) eq y)
                }
            }
            val compiled = schema.compile(semanticOverrides = SemanticOverrides(pinAbsentOptionalValues = pinAbsent))
            val helper = compiled.minimize(schema.x.value)

            val result = assertIs<OpenTheoryResult.Sat>(LocalSearchSolver.source(compiled).solve(
                LocalSearchParams(maxFlips = 1000, randomSeed = 17)))
            val point = assertNotNull(compiled.sourceBinding.bindOriginalSample(
                result.assignment.toSample(compiled.problem), ExecutionControl.Never))

            assertSame(compiled.sourceBinding.originalProblem, point.coordinateFrame)
            assertNull(compiled.decode(schema.x, point))
            val x = compiled.decode(schema.x.value, point)
            if (pinAbsent) assertEquals(0.0, x)
            assertEquals(x * x, compiled.decode(schema.y, point))
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
    fun `source solving should retain the exact real half value`() {
        val x = FloatHandle("x", 0.0, 1.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(0.0, 1.0),
            "strict" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.GT, -1.0)),
            "square" to NamedConstraint((x * x) eq 0.25),
        )).compile()

        val result = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(compiled).solve())

        val point = assertNotNull(result.assignment.algebraicRealValue(assertNotNull(compiled.sourceRealId(x)).index))
        assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-1, 2), point))
    }

    @Test
    fun `source adapters should retain the compiled real declarations`() {
        val x = FloatHandle("x", -1.0, 1.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-1.0, 1.0),
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
        assertEquals(1, compatibility.numRealVars)
        assertEquals(0, compatibility.numIntVars)
        assertSame(original, compiled.sourceBinding.attachment().originalSource)
    }

    @Test
    fun `decoding should accept source and unbound frames and reject foreign sources`() {
        val x = FloatHandle("x", -1.0, 1.0)
        val definition = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-1.0, 1.0),
            "different" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.NE, 0.0)),
        ))
        val compiled = definition.compile()
        val other = definition.compile()
        val original = assertNotNull(compiled.sourceBinding.bindOriginalSample(
            Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.25),
                listOf(BigFraction.ofLong(4).reciprocal())), ExecutionControl.Never))
        val unbound = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0))
        val privatePoint = unbound.withCoordinateFrame(compiled.problem)

        assertEquals(0.25, compiled.decode(x, original))
        assertEquals(1.0, compiled.decode(x, privatePoint))
        assertEquals(1.0, compiled.decode(x, unbound))
        assertFailsWith<IllegalArgumentException> {
            compiled.decode(x, unbound.withCoordinateFrame(other.problem))
        }
    }

    @Test
    fun `an interrupted original frame check should withhold the point`() {
        val compiled = SchemaDef<SchemaEntry>(mapOf("x" to FloatSpec(0.0, 1.0))).compile()
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), listOf(BigFraction.ZERO))

        val bound = compiled.sourceBinding.bindOriginalSample(sample, ExecutionControl { true })

        assertNull(bound)
        assertNull(sample.coordinateFrame)
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
    fun `continuous schema policy should retain optional source defaults without grid geometry`() {
        val schema = object : VariableSchema() {
            val x by optFloatVar(-1.0, 1.0)
            init {
                constraint(Not(x.present.toExpr()))
            }
        }
        val compiled = schema.compile(semanticOverrides = SemanticOverrides(floats = FloatSemantics.Continuous))

        val point = BacktrackSolver(compiled.problem.bake()).enumerate().single()

        assertNull(compiled.decode(schema.x, point))
        assertEquals(0.0, compiled.decode(schema.x.value, point))
        assertEquals(0, compiled.problem.numIntVars)
    }
}
