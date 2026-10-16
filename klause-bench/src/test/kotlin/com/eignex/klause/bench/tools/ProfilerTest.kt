package com.eignex.klause.bench.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfilerTest {
    @Test
    fun `context markers should recognize concrete entry frames`() {
        for ((frame, expected) in listOf(
            "solver.pipeline.ResumableOpenTheory.runSlice" to "open-theory entry",
            "solver.pipeline.OpenTheoryEngine\$OpenTheoryRun.advance" to "open-theory entry",
            "solver.pipeline.RetainedLinearQueries.check" to "retained-query entry",
            "solver.pipeline.RetainedLinearQueries.decide" to "retained-query entry",
            "solver.pipeline.RetainedLinearQueries.prepared" to "retained-query entry",
            "solver.pipeline.RetainedLinearQueries.prepareBooleanCenter" to "retained-query entry",
            "solver.pipeline.ExactNeighborhood.advance" to "neighborhood entry",
            "solver.pipeline.OpenExactNeighborhood.check" to "neighborhood entry",
            "lp.LpProbeSolver.probe" to "presolve probe entry",
            "theory.qflra.QfLiraSolverKt.sharedExactLiraSource" to "source preparation entry",
            "solver.pipeline.PreparedSearchComponents.prepareSource" to "source preparation entry",
        )) {
            val markers = Profiler.cliContextMarkers(listOf("com.eignex.klause.$frame"))

            assertEquals("observed: $expected", markers, frame)
        }
    }

    @Test
    fun `missing or truncated context ancestors should remain unknown`() {
        for (stack in listOf(emptyList(), listOf("java.util.HashMap.getNode"), listOf(
            "com.eignex.klause.lp.LpPropagator.solve",
            "com.eignex.klause.solver.search.ExactLiraSearchComponent.relax",
        ))) {
            val markers = Profiler.cliContextMarkers(stack)

            assertEquals("unknown: no captured context marker", markers)
        }
    }

    @Test
    fun `context markers should preserve nested method frames`() {
        for (frame in listOf(
            "com.eignex.klause.solver.pipeline.ExactNeighborhood\$advance\$1.invokeSuspend",
            "com.eignex.klause.solver.pipeline.ExactNeighborhood.advance\$default",
        )) {
            val markers = Profiler.cliContextMarkers(listOf(frame))

            assertEquals("observed: neighborhood entry", markers, frame)
        }
    }

    @Test
    fun `repeated context frames should contribute one marker`() {
        val markers = Profiler.cliContextMarkers(listOf(
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.check",
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.decide",
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.check",
        ))

        assertEquals("observed: retained-query entry", markers)
    }

    @Test
    fun `common engine ancestors should preserve multiple captured contexts`() {
        val stack = listOf(
            "com.eignex.klause.solver.pipeline.ExactNeighborhood.advance",
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.decide",
            "com.eignex.klause.solver.pipeline.ResumableOpenTheory.runSlice",
        )

        for (frames in listOf(stack, stack.reversed())) {
            val markers = Profiler.cliContextMarkers(frames)

            assertEquals("multiple observed: open-theory entry + retained-query entry + neighborhood entry", markers)
        }
    }

    @Test
    fun `context matching should reject similar package class and method prefixes`() {
        for (frame in listOf(
            "other.com.eignex.klause.solver.pipeline.ExactNeighborhood.advance",
            "com.eignex.klause.solver.pipeline.ExactNeighborhoodExtra.advance",
            "com.eignex.klause.solver.pipeline.ExactNeighborhood.advanceExtra",
            "com.eignex.klause.solver.pipeline.OtherExactNeighborhood.advance",
            "com.eignex.klause.solver.pipeline.OpenTheoryEngine\$OpenTheoryRun.advanceExtra",
            "com.eignex.klause.lp.LpProbeSolver.probeExtra",
        )) {
            val markers = Profiler.cliContextMarkers(listOf(frame))

            assertEquals("unknown: no captured context marker", markers, frame)
        }
    }

    @Test
    fun `model caller paths should retain source ancestors beyond mechanism packages`() {
        val callers = Profiler.cliModelCallers(listOf(
            "java.util.HashMap.getNode",
            "com.eignex.klause.util.OrCancellation.isCancelled",
            "com.eignex.klause.bench.tools.Profiler.summarizeCli",
            "com.eignex.klause.lp.engine.LpExactState.<init>",
            "com.eignex.klause.lp.lattice.IntegerEchelon.reduce",
            "com.eignex.klause.simplex.exact.BigFraction.plus",
            "com.eignex.klause.arithmetic.difference.DifferenceGraph.potentials",
            "com.eignex.klause.theory.qflra.LiveQfLraSystem.assertComparison",
            "com.eignex.klause.solver.search.SearchSession.propagate",
        ))

        assertEquals(
            "com.eignex.klause.theory.qflra.LiveQfLraSystem.assertComparison <- " +
                "com.eignex.klause.solver.search.SearchSession.propagate",
            callers,
        )
    }

    @Test
    fun `model caller paths should retain at most four captured frames`() {
        val stack = listOf("First", "Second", "Third", "Fourth", "Fifth")
            .map { "com.eignex.klause.lp.$it.run" }

        val callers = Profiler.cliModelCallers(stack)

        assertEquals(
            "com.eignex.klause.lp.First.run <- com.eignex.klause.lp.Second.run <- " +
                "com.eignex.klause.lp.Third.run <- com.eignex.klause.lp.Fourth.run",
            callers,
        )
    }

    @Test
    fun `package filters should preserve model-facing arithmetic and LP owners`() {
        for (frame in listOf(
            "com.eignex.klause.factor.arithmetic.Linear.computeViolation",
            "com.eignex.klause.lp.relaxation.CpToLpRelaxation.prepare",
            "com.eignex.klause.bound.KnapsackLagrangianBound.compute",
        )) {
            val callers = Profiler.cliModelCallers(listOf(
                "com.eignex.klause.simplex.exact.BigFraction.plus",
                frame,
            ))

            assertEquals(frame, callers)
        }
    }

    @Test
    fun `stacks without model callers should retain an explicit unknown label`() {
        for (stack in listOf(emptyList(), listOf("java.util.HashMap.getNode"), listOf(
            "com.eignex.klause.util.OrCancellation.isCancelled",
            "com.eignex.klause.lp.engine.LpExactState.<init>",
            "com.eignex.klause.lp.lattice.IntegerEchelon.reduce",
            "com.eignex.klause.simplex.exact.BigFraction.plus",
            "com.eignex.klause.arithmetic.difference.DifferenceGraph.potentials",
        ))) {
            val callers = Profiler.cliModelCallers(stack)

            assertEquals("no captured model-facing caller", callers)
        }
    }

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
