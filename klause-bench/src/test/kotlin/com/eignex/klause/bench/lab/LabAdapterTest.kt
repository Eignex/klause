package com.eignex.klause.bench.lab

import com.eignex.lab.bench.BenchRequest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LabAdapterTest {
    @Test fun `unknown solver options fail before corpus discovery`() {
        assertFailsWith<IllegalArgumentException> { KlauseLabSuite.plan(BenchRequest(mapOf("typo" to "true"))) }
    }

    @Test fun `invalid resource budgets fail before corpus discovery`() {
        assertFailsWith<IllegalArgumentException> { KlauseLabSuite.plan(BenchRequest(mapOf("cpus" to "0"))) }
    }
}
