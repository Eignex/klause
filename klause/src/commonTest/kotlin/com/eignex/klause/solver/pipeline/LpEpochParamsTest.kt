package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LpEpochParamsTest {
    @Test
    fun `epoch override preserves omission and accepts explicit booleans`() {
        assertNull(backtrackOverride(EngineParams(emptyList()), allowSelectors = true))
        for (enabled in listOf(false, true)) {
            val input = EngineParams(listOf("lp-epochs=$enabled", "lp-root-tidy=$enabled"))
            val edit = assertNotNull(backtrackOverride(input, allowSelectors = true))
            val changed = edit(BacktrackParams(lpEpochs = !enabled, lpRootTidy = !enabled))
            assertEquals(enabled, changed.lpEpochs)
            assertEquals(enabled, changed.lpRootTidy)
            input.finish("cp", BACKTRACK_OVERRIDE_KEYS.joinToString())
        }
    }

    @Test
    fun `epoch override rejects a nonboolean value`() {
        assertFailsWith<PipelineConfigException> {
            backtrackOverride(EngineParams(listOf("lp-epochs=maybe")), allowSelectors = true)
        }
    }
}
