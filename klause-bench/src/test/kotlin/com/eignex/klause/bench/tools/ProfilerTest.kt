package com.eignex.klause.bench.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfilerTest {
    @Test
    fun `resumed initialization and restart frames retain their phases`() {
        for ((method, expected) in listOf(
            "newMinimizeState" to "local-search seeding",
            "newSatisfyState" to "local-search seeding",
            "recomputeInitial" to "local-search seeding",
            "restartAndRepair" to "local-search restart seeding",
        )) {
            for (frame in listOf("LocalSearchEngine.$method", "LocalSearchEngine\$$method\$1.invokeSuspend")) {
                val phase = Profiler.cliPhase(listOf("LocalSearchState.apply", frame))

                assertEquals(expected, phase, frame)
            }
        }
    }

    @Test
    fun `standalone greedy seeding takes precedence over move application`() {
        for (frame in listOf(
            "com.eignex.klause.localsearch.movesource.GreedyInit\$Pass.advance",
            "LocalSearchEngine\$greedyRepairPass\$1.invokeSuspend",
        )) {
            val phase = Profiler.cliPhase(listOf("LocalSearchState.apply", frame))

            assertEquals("local-search seeding", phase, frame)
        }
    }

    @Test
    fun `greedy seeding retains visible ALNS bootstrap attribution`() {
        val phase = Profiler.cliPhase(listOf(
            "LocalSearchState.apply",
            "LocalSearchEngine\$greedyRepairPass\$1.invokeSuspend",
            "com.eignex.klause.meta.alns.Alns.bootstrapIncumbent",
        ))

        assertEquals("ALNS bootstrap: local-search seeding", phase)
    }
}
