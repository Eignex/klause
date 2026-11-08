package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpsProofFrontierTest {
    @Test
    fun `selection export should have a fresh window after producer cancellation`() {
        val journal = NumericalSelectionSnapshot(Cancellation { true }).journal()

        val snapshot = MpsProofFrontier.selectionSnapshot(journal)

        assertEquals("false", snapshot.getValue("inspectionStopped").jsonPrimitive.content)
    }

    @Test
    fun `profile JVM should honor the declared production heap and collector`() {
        val options = MpsProofFrontier.launchJvmOptions(mapOf("heapMiB" to "2560", "gc" to "default"))

        assertTrue("-Xmx2560m" in options)
        assertTrue("-XX:ActiveProcessorCount=1" in options)
        assertFalse(options.any { it.contains("UseSerialGC") })
    }

    @Test
    fun `fractional order should be accepted as a profile control`() {
        val options = MpsProofFrontier.parseOptions(arrayOf("suite=miplib3", "problem=egout",
            "sha256=unused", "exact=true", "fractionalOrder=true"))

        assertEquals("true", options["fractionalOrder"])
    }

    @Test
    fun `attempt observation should bind the declared production controls`() {
        val options = MpsProofFrontier.parseOptions(arrayOf("suite=miplib3", "problem=egout",
            "sha256=unused", "exact=true", "frontier=true", "pointCuts=true", "aggregateCuts=true",
            "fractionalOrder=true", "fractional-attempt=true", "sourceWork=1000000000",
            "sourceAllocation=536870912", "refutationReuse=false"))

        val params = MpsProofFrontier.controlParams(options, Cancellation.Never)

        assertTrue(params.sourceCuts && params.sourcePointCuts && params.sourceAggregateCuts)
        assertTrue(params.sourceCutFractionalOrder)
        assertEquals(1_000_000_000L, params.sourceLpWorkLimit)
        assertEquals(536_870_912L, params.sourceLpAllocationLimit)
        assertEquals(Int.MAX_VALUE, params.sourceLpOperations)
        assertFalse(params.sourceCutBranching || params.sourceRefutationReuse || params.sourceRefutationPropagation ||
            params.sourceRefutationResolution || params.sourceIncumbentCompletion)
    }

    @Test
    fun `attempt observation should require explicit fractional policy activation`() {
        val activation = listOf("frontier=true", "pointCuts=true", "aggregateCuts=true", "fractionalOrder=true")
        for (missing in activation) {
            val options = listOf("suite=miplib3", "problem=egout", "sha256=unused", "exact=true",
                "fractional-attempt=true") + (activation - missing)

            assertFailsWith<IllegalArgumentException> { MpsProofFrontier.parseOptions(options.toTypedArray()) }
        }
    }

    @Test
    fun `profile controls should reject negative containing allowances`() {
        for (name in listOf("sourceWork", "sourceAllocation")) {
            assertFailsWith<IllegalArgumentException> {
                MpsProofFrontier.controlParams(mapOf(name to "-1"), Cancellation.Never)
            }
        }
    }
}
