package com.eignex.klause.bench.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfilerTest {
    @Test
    fun `operation caller paths should preserve captured order beyond utilities`() {
        val callers = Profiler.cliOperationCallers(listOf(
            "java.util.HashMap.getNode",
            "com.eignex.klause.util.OrCancellation.isCancelled",
            "com.eignex.klause.theory.qflra.LiveQfLraSystem.install",
            "com.eignex.klause.solver.search.SearchSession.initialize",
        ))

        assertEquals(
            "com.eignex.klause.theory.qflra.LiveQfLraSystem.install <- " +
                "com.eignex.klause.solver.search.SearchSession.initialize",
            callers,
        )
    }

    @Test
    fun `operation caller paths should retain at most three frames`() {
        val stack = listOf("First", "Second", "Third", "Fourth").map { "com.eignex.klause.solver.$it.run" }

        val callers = Profiler.cliOperationCallers(stack)

        assertEquals(
            "com.eignex.klause.solver.First.run <- com.eignex.klause.solver.Second.run <- " +
                "com.eignex.klause.solver.Third.run",
            callers,
        )
    }

    @Test
    fun `stacks without operation callers should keep an explicit unknown label`() {
        for (stack in listOf(emptyList(), listOf("java.util.HashMap.getNode"), listOf(
            "com.eignex.klause.util.OrCancellation.isCancelled",
            "com.eignex.klause.bench.tools.Profiler.summarizeCli",
        ))) {
            val callers = Profiler.cliOperationCallers(stack)

            assertEquals("no captured Klause operation", callers)
        }
    }

    @Test
    fun `resumed initialization and restart frames should retain their phases`() {
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
    fun `standalone greedy seeding should take precedence over move application`() {
        for (frame in listOf(
            "com.eignex.klause.localsearch.movesource.GreedyInit\$Pass.advance",
            "LocalSearchEngine\$greedyRepairPass\$1.invokeSuspend",
        )) {
            val phase = Profiler.cliPhase(listOf("LocalSearchState.apply", frame))

            assertEquals("local-search seeding", phase, frame)
        }
    }

    @Test
    fun `greedy seeding should retain visible ALNS bootstrap attribution`() {
        val phase = Profiler.cliPhase(listOf(
            "LocalSearchState.apply",
            "LocalSearchEngine\$greedyRepairPass\$1.invokeSuspend",
            "com.eignex.klause.meta.alns.Alns.bootstrapIncumbent",
        ))

        assertEquals("ALNS bootstrap: local-search seeding", phase)
    }

    @Test
    fun `shared search stacks should retain the operation phase`() {
        for ((frame, expected) in listOf(
            "PreparedSearchComponents.<init>" to "shared source preparation",
            "PreparedSearchComponents.prepareSource" to "shared source preparation",
            "QfLiraSolverKt.sharedExactLiraSource" to "shared source preparation",
            "ComponentPlanKt.componentPlan" to "shared source preparation",
            "PreparedSourcePlanKt.prepareExecutionPlan" to "shared source preparation",
            "ExactLiraSearchComponent.installSource" to "shared theory source installation",
            "ExactLiraSearchComponent.assertSource" to "shared theory source installation",
            "SearchSession.prepareInitialization" to "shared root initialization",
            "SearchSession.propagate" to "shared propagation",
            "SearchSession.check" to "shared model check",
            "PreparedSearchRegistrations.open" to "shared session opening",
            "SearchRun.next" to "shared search",
            "SearchRun.advanceFrame" to "shared search",
            "SearchRun.backtrack" to "shared search",
        )) {
            val phase = Profiler.cliPhase(listOf("java.util.HashMap.getNode", frame))

            assertEquals(expected, phase, frame)
        }
    }

    @Test
    fun `shared root initialization should retain nested propagation cost`() {
        val phase = Profiler.cliPhase(listOf(
            "com.eignex.klause.propagation.MutatorsKt.excludeIntValueImpl",
            "SearchSession.propagate",
            "SearchSession.initialize",
            "SearchRun.next",
        ))

        assertEquals("shared root initialization", phase)
    }

    @Test
    fun `presolve and backtrack callers should retain their phase ownership`() {
        for ((frame, expected) in listOf(
            "com.eignex.klause.presolve.RootBaker.prepare" to "presolve",
            "com.eignex.klause.backtrack.BacktrackSolver.solve" to "backtrack search",
        )) {
            val phase = Profiler.cliPhase(listOf("SearchSession.initialize", frame))

            assertEquals(expected, phase, frame)
        }
    }

    @Test
    fun `utility frames without a shared caller should remain unclassified`() {
        val phase = Profiler.cliPhase(listOf(
            "java.util.HashMap.getNode",
            "OrCancellation.isCancelled",
            "SearchSession.checkBudgetExhausted",
        ))

        assertEquals("other or unclassified", phase)
    }
}
