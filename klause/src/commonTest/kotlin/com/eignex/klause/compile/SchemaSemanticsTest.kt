package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.SemanticOverrides
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
    fun `legacy schema bucket defaults should be captured independently for each compilation`() {
        val source = object : VariableSchema() {
            val x by optFloatVar(0.0, 1.0)
        }

        val first = source.compile(KlauseConfig.DEFAULT.copy(floatBuckets = 7))
        val second = source.compile(KlauseConfig.DEFAULT.copy(floatBuckets = 9))

        assertEquals(7, first.floatDecoders.getValue("x").buckets)
        assertEquals(9, second.floatDecoders.getValue("x").buckets)
        assertEquals(schemaSemantics, first.problem.semantics.declared)
    }

    @Test
    fun `caller absent value policy should replace legacy canonical pinning`() {
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
    fun `continuous schema policy should decline a component requiring a grid`() {
        val source = SchemaDef<SchemaEntry>(
            mapOf(
                "x" to FloatSpec(0.0, 1.0),
                "strict" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.GT, 0.0)),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            source.compile(semanticOverrides = SemanticOverrides(floats = FloatSemantics.Continuous))
        }

        assertTrue("cannot use a grid component" in error.message.orEmpty())
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
    fun `schema compilation should decline an unsupported float linear scale`() {
        val source = SchemaDef<SchemaEntry>(emptyMap())

        val error = assertFailsWith<IllegalArgumentException> {
            source.compile(semanticOverrides = SemanticOverrides(floats = FloatSemantics.Grid(7, 10)))
        }

        assertTrue("requires the default scale" in error.message.orEmpty())
    }
}
