package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.model.BoolRef
import com.eignex.klause.model.BoolSpec
import com.eignex.klause.model.FloatLinearConstraint
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.Not
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.SolveResult
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SchemaFloatPolicyTest {
    @Test
    fun `independent strict component preserves exact rational equalities`() {
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "exact" to FloatSpec(0.0, 1.0, 2),
            "grid" to FloatSpec(0.0, 1.0, 3),
            "b" to BoolSpec,
            "boolean" to NamedConstraint(Not(BoolRef("b"))),
            "exactRow" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(3.0), listOf("exact"), IntCmpOp.EQ, 1.0)),
            "gridRow" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("grid"), IntCmpOp.GT, 0.25)),
        )).compile()

        val sample = assertIs<SolveResult.Sat>(BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 0))).assignment

        val exactId = compiled.realVarIdByName.getValue("exact")
        val rational = requireNotNull(sample.exactReals)[exactId]
        assertEquals(BigFraction.ONE, rational * BigFraction.ofLong(3))
        val gridSpec = compiled.floatDecoders.getValue("grid")
        val grid = gridSpec.realValue(sample.ints[compiled.intVarIdByName.getValue("grid")].toInt())
        assertTrue(grid > 0.25 && grid <= 1.0)
        assertFalse(compiled.floatRepresentations.getValue("exact").approximate)
        assertTrue(compiled.floatRepresentations.getValue("grid").approximate)
    }

    @Test
    fun `connected strict row selects one representation for all its floats`() {
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(0.0, 1.0, 3), "y" to FloatSpec(0.0, 1.0, 3),
            "row" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0, 1.0), listOf("x", "y"), IntCmpOp.LT, 1.0)),
        )).compile()

        assertEquals(compiled.floatRepresentations.getValue("x"), compiled.floatRepresentations.getValue("y"))
        assertTrue(compiled.floatRepresentations.getValue("x").approximate)
        assertEquals(0, compiled.problem.numRealVars)
    }
}
