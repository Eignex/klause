package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
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
import com.eignex.klause.solver.SolveResult
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SchemaSemanticsTest {
    @Test
    fun `caller absent value policy should replace declared canonical pinning`() {
        val source = object : VariableSchema() {
            val x by optFloatVar(0.0, 1.0)
        }
        val compiled = source.compile(semanticOverrides = SemanticOverrides(pinAbsentOptionalValues = false))
        val assumptions = Assumptions(
            bools = mapOf(compiled.boolVarIdByName.getValue("x__present") to false),
        )

        val result = BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(assumptions = assumptions))

        assertIs<SolveResult.Sat>(result)
        assertEquals(false, compiled.problem.semantics.effective.pinAbsentOptionalValues)
    }

    @Test
    fun `continuous schema policy should preserve an exact linear component`() {
        val source = SchemaDef<SchemaEntry>(mapOf("x" to FloatSpec(0.0, 1.0)))

        val compiled = source.compile(semanticOverrides = SemanticOverrides(floats = FloatSemantics.Continuous))

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
    fun `a declared singleton range should retain its real value`() {
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(3.5, 3.5),
            "strict" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("x"), IntCmpOp.LT, 4.0)),
        )).compile()

        val values = BacktrackSolver(compiled.problem.bake()).enumerate()
            .map { it.approximateRealValue(compiled.realVarIdByName.getValue("x")) }.toSet()

        assertEquals(setOf(3.5), values)
    }
}
