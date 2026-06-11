package com.eignex.klause.bench.target

import com.eignex.klause.portfolio.CompetitionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CompetitionPresetTest {

    @Test
    fun `there is one parity preset per competition track`() {
        val expected = mapOf(
            "mzn-fixed" to CompetitionMode.FIXED,
            "mzn-free" to CompetitionMode.FREE,
            "mzn-parallel" to CompetitionMode.PARALLEL,
            "mzn-open" to CompetitionMode.OPEN,
            "mzn-ls" to CompetitionMode.LOCAL_SEARCH,
        )
        for ((id, mode) in expected) {
            val target = Targets.get(id)
            assertEquals(MetricKind.PARITY, target.metric, "$id must be a parity preset")
            assertEquals(mode, assertNotNull(target.competition, "$id must carry a competition track").mode)
            assertEquals(300_000, target.budget.timeoutMillis, "$id must use the 300 s challenge budget")
        }
    }
}
