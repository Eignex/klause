package com.eignex.klause.bench.profile

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NumericalFrontierSnapshotTest {
    @Test
    fun `unvisited alternatives should retain their checked ancestor bound without another solve`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val context = SourceProofContext(
            SourceScope(SourceRevision("frontier", "exact"),
                objective = SourceObjective("x", ObjectiveSense.MINIMIZE)),
            setOf(integer),
            mapOf("half" to SourcePremise(SourceInequality(
                mapOf(integer to BigFraction.ofLong(-2)), BigFraction.MINUS_ONE))),
            mapOf(integer to BigFraction.ONE),
        )
        val invocation = NumericalFrontierSnapshot.open(context, TheoryParams(maxDecisions = 0))
        try {
            NumericalFrontierSnapshot.run(invocation) { _, _ -> error("unexpected incumbent") }
            val before = NumericalFrontierSnapshot.stats(invocation).smt.sourceLp.operations

            val result = NumericalFrontierSnapshot(context, Cancellation.Never).capture(invocation)

            val pending = result.getValue("pending").jsonArray
            assertEquals(2, pending.size)
            for (node in pending) {
                assertEquals("unvisited", node.jsonObject.getValue("state").jsonPrimitive.content)
                assertEquals(true, node.jsonObject.getValue("inherited").jsonPrimitive.boolean)
                val bound = node.jsonObject.getValue("bound").jsonObject
                assertEquals("1/2", bound.getValue("nativeValue").jsonPrimitive.content)
                assertEquals(true, bound.getValue("checked").jsonPrimitive.boolean)
            }
            assertEquals(1L, before)
            assertEquals(before, NumericalFrontierSnapshot.stats(invocation).smt.sourceLp.operations)
        } finally {
            (invocation as AutoCloseable).close()
        }
    }
}
