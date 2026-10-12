package com.eignex.klause.compile

import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CompiledAffineObjectiveTest {
    @Test
    fun `compiled real affine objective retains the declared coordinate beside product auxiliaries`() {
        val x = FloatHandle("x", -1.0, 1.0)
        val y = FloatHandle("y", 0.0, 1.0)
        val source = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-1.0, 1.0, 3), "y" to FloatSpec(0.0, 1.0, 3),
            "square" to NamedConstraint((x * x) eq y),
        )).compile()
        val objective = source.minimize(y)

        val exact = objective.toQuadraticObjectiveSpec()

        assertEquals(QuadraticVariable.Real(assertNotNull(source.sourceRealId(y))), exact.terms.single().first)
    }
}
