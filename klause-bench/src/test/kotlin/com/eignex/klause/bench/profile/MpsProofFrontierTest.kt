package com.eignex.klause.bench.profile

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MpsProofFrontierTest {
    @Test
    fun `aggregate inspection controls should require an exported selection and enabled probe`() {
        for (missing in listOf("selection=true", "aggregate-probe=true")) {
            val options = listOf("suite=miplib3", "problem=egout", "sha256=unused", "exact=true",
                "aggregate-query=5") + (listOf("selection=true", "aggregate-probe=true") - missing)

            assertFailsWith<IllegalArgumentException> { MpsProofFrontier.parseOptions(options.toTypedArray()) }
        }
    }

    @Test
    fun `aggregate inspection controls should reject invalid query ids and expanded grants`() {
        for (control in listOf("aggregate-query=-1", "aggregate-query=2147483648", "aggregate-work=-1",
            "aggregate-work=20000001", "aggregate-allocation=-1", "aggregate-allocation=67108865")) {
            val options = listOf("suite=miplib3", "problem=egout", "sha256=unused", "exact=true",
                "selection=true", "aggregate-probe=true", control)

            assertFailsWith<IllegalArgumentException> { MpsProofFrontier.parseOptions(options.toTypedArray()) }
        }
    }
}
