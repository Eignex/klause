package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.model.ContinuousRealEquality
import com.eignex.klause.model.ContinuousRealExpr
import com.eignex.klause.model.FloatLinearConstraint
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.gt
import com.eignex.klause.solver.SolveResult
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SchemaSemanticsTest {
    @Test
    fun `omitted schema bucket counts should use the effective caller default`() {
        val sources = listOf(
            object : VariableSchema() {
                val x by floatVar(0.0, 1.0)
                val positive by constraint { x gt 0.0 }
            },
            object : VariableSchema() {
                val x by optFloatVar(0.0, 1.0)
            },
        )

        for (source in sources) {
            val compiled = source.compile(
                semanticOverrides = SemanticOverrides(floats = FloatSemantics.Grid(7, 1_000_000)),
            )

            assertEquals(7, compiled.floatDecoders.getValue("x").buckets)
            assertEquals(6L, compiled.problem.intBounds.upper(compiled.intVarIdByName.getValue("x")))
            assertTrue(compiled.problem.semantics.hasOverrides)
        }
    }

    @Test
    fun `an explicit schema grid should retain its declared bucket count`() {
        val source = object : VariableSchema() {
            val x by optFloatVar(0.0, 1.0, buckets = 5)
        }

        val compiled = source.compile(semanticOverrides = SemanticOverrides(floats = FloatSemantics.Grid(7, 1_000_000)))

        assertEquals(5, compiled.floatDecoders.getValue("x").buckets)
        assertEquals(4L, compiled.problem.intBounds.upper(compiled.intVarIdByName.getValue("x")))
    }

    @Test
    fun `caller schema bucket defaults should be captured independently for each compilation`() {
        val source = object : VariableSchema() {
            val x by optFloatVar(0.0, 1.0)
        }

        val first = source.compile(semanticOverrides = SemanticOverrides(floats = FloatSemantics.Grid(7, 1_000_000)))
        val second = source.compile(semanticOverrides = SemanticOverrides(floats = FloatSemantics.Grid(9, 1_000_000)))

        assertEquals(7, first.floatDecoders.getValue("x").buckets)
        assertEquals(9, second.floatDecoders.getValue("x").buckets)
        assertEquals(schemaSemantics, first.problem.semantics.declared)
    }

    @Test
    fun `caller absent value policy should replace declared canonical pinning`() {
        val source = object : VariableSchema() {
            val x by optFloatVar(0.0, 1.0, buckets = 5)
        }
        val compiled = source.compile(semanticOverrides = SemanticOverrides(pinAbsentOptionalValues = false))
        val assumptions = Assumptions(
            bools = mapOf(compiled.boolVarIdByName.getValue("x__present") to false),
            ints = mapOf(compiled.intVarIdByName.getValue("x") to 4L),
        )

        val result = BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(assumptions = assumptions))

        assertIs<SolveResult.Sat>(result)
        assertEquals(false, compiled.problem.semantics.effective.pinAbsentOptionalValues)
    }

    @Test
    fun `continuous schema policy should preserve an exact linear component`() {
        val source = SchemaDef<SchemaEntry>(mapOf("x" to FloatSpec(0.0, 1.0)))

        val compiled = source.compile(semanticOverrides = SemanticOverrides(floats = FloatSemantics.Continuous))

        assertFalse(compiled.floatRepresentations.getValue("x").approximate)
        assertEquals(FloatSemantics.Continuous, compiled.problem.semantics.effective.floats)
    }

    @Test
    fun `continuous schema policy should retain strict source rows`() {
        val source = SchemaDef<SchemaEntry>(
            mapOf(
                "x" to FloatSpec(0.0, 1.0),
                "strict" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.GT, 0.0)),
            ),
        )

        val compiled = source.compile(semanticOverrides = SemanticOverrides(floats = FloatSemantics.Continuous))

        assertEquals(1, compiled.problem.numRealVars)
        assertEquals(0, compiled.problem.numIntVars)
        assertFalse(compiled.floatRepresentations.getValue("x").approximate)
    }

    @Test
    fun `schema declarations should reject an undeclared integer range override`() {
        val source = SchemaDef<SchemaEntry>(emptyMap())

        val error = assertFailsWith<IllegalArgumentException> {
            source.compile(semanticOverrides = SemanticOverrides(
                undeclaredIntegers = BoundsOverride(upper = BoundOverride.Value(7)),
            ))
        }

        assertTrue("undeclared integer bounds do not apply" in error.message.orEmpty())
    }

    @Test
    fun `float linear precision should change the interpreted grid assignments`() {
        val source = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(0.0, 1.0, buckets = 2),
            "grid" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.GT, -0.5)),
            "limited" to NamedConstraint(
                FloatLinearConstraint(doubleArrayOf(2.49e-7), listOf("x"), IntCmpOp.LE, 1e-7),
            ),
        ))
        for (scale in listOf(1_000_000L, 10_000_000L)) {
            val compiled = source.compile(
                semanticOverrides = SemanticOverrides(floats = FloatSemantics.Grid(2, scale)),
            )

            val assignments = BacktrackSolver(compiled.problem.bake()).enumerate(BacktrackParams(randomSeed = 0))
                .map { it.intValue(compiled.intVarIdByName.getValue("x")) }.toSet()

            assertEquals(if (scale == 1_000_000L) setOf(0L, 1L) else setOf(0L), assignments)
        }
    }

    @Test
    fun `grid affine equalities should use the effective float linear scale`() {
        val source = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(0.0, 1.0, buckets = 2),
            "z" to FloatSpec(0.0, 1.0, buckets = 2),
            "grid" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.GT, -0.5)),
            "zero" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("z"), IntCmpOp.EQ, 0.0)),
            "affine" to NamedConstraint(ContinuousRealEquality(
                ContinuousRealExpr.Affine(mapOf("z" to 1.0)),
                ContinuousRealExpr.Affine(mapOf("x" to 2.49e-7)),
            )),
        ))
        for (scale in listOf(1_000_000L, 10_000_000L)) {
            val compiled = source.compile(
                semanticOverrides = SemanticOverrides(floats = FloatSemantics.Grid(2, scale)),
            )

            val assignments = BacktrackSolver(compiled.problem.bake()).enumerate(BacktrackParams(randomSeed = 0))
                .map { it.intValue(compiled.intVarIdByName.getValue("x")) }.toSet()

            assertEquals(if (scale == 1_000_000L) setOf(0L, 1L) else setOf(0L), assignments)
        }
    }

    @Test
    fun `wide float linear scales should preserve a feasible grid row`() {
        val source = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(0.0, 1.0, buckets = 2),
            "strict" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.GT, 0.5)),
        ))
        val compiled = source.compile(
            semanticOverrides = SemanticOverrides(floats = FloatSemantics.Grid(2, 3_000_000_000L)),
        )

        val result = BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 0))

        assertEquals(1L, assertIs<SolveResult.Sat>(result).assignment.intValue(compiled.intVarIdByName.getValue("x")))
    }

    @Test
    fun `a rounded private grid scale should retain original source declarations`() {
        val source = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(0.0, 1.0),
            "strict" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.GT, 0.0)),
        ))

        val compiled = source.compile(
            semanticOverrides = SemanticOverrides(floats = FloatSemantics.Grid(7, 9_007_199_254_740_993L)),
        )

        assertEquals(1, compiled.sourceBinding.originalProblem.numRealVars)
        assertEquals(0, compiled.sourceBinding.originalProblem.numIntVars)
        assertFailsWith<IllegalStateException> { compiled.problem }
    }

    @Test
    fun `legacy float rows should preserve truncation of scaled coefficients`() {
        val source = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(0.0, 1.0, 2),
            "grid" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.GT, -0.5)),
            "limited" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(6e-7), listOf("x"), IntCmpOp.LE, 4e-8)),
        ))
        val compiled = source.compile(semanticOverrides = SemanticOverrides(floats = FloatSemantics.Grid(2, 1_000_000)))

        val values = BacktrackSolver(compiled.problem.bake()).enumerate()
            .map { it.intValue(compiled.intVarIdByName.getValue("x")) }.toSet()

        assertEquals(setOf(0L, 1L), values)
    }

    @Test
    fun `a declared singleton range should retain its requested compatibility bucket count`() {
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(3.5, 3.5, 5),
            "strict" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.LT, 4.0)),
        )).compile()

        val values = BacktrackSolver(compiled.problem.bake()).enumerate()
            .map { it.intValue(compiled.intVarIdByName.getValue("x")) }.toSet()

        assertEquals((0L..4L).toSet(), values)
        assertEquals(5, compiled.floatDecoders.getValue("x").buckets)
    }
}
